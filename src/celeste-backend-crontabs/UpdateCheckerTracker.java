package com.max480.discord.randombots;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListener;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.json.JSONArray;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A service that follows the Update Checker logs and re-posts them to a Discord channel.
 * It also calls frontend APIs to make it aware of database changes, and reload it as necessary.
 */
public class UpdateCheckerTracker implements TailerListener {
    private static class ModInfo implements Serializable {
        private static final long serialVersionUID = -2184804878021343630L;

        public final String type;
        public final int id;
        public final int likes;
        public final int views;
        public final int downloads;
        public final int categoryId;
        public final int createdDate;
        public final Map<String, Object> fullInfo;

        private ModInfo(String type, int id, int likes, int views, int downloads, int categoryId, int createdDate, Map<String, Object> fullInfo) {
            this.type = type;
            this.id = id;
            this.likes = likes;
            this.views = views;
            this.downloads = downloads;
            this.categoryId = categoryId;
            this.createdDate = createdDate;
            this.fullInfo = fullInfo;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(UpdateCheckerTracker.class);

    private static boolean lastLineIsNetworkError = false;
    protected static ZonedDateTime lastLogLineDate = ZonedDateTime.now();

    private static boolean luaCutscenesUpdated = false;

    private static String everestUpdateSha256 = "[first check]";
    private static String fileIdsSha256 = "[first check]";

    private static final Pattern SAVED_MOD_PATTERN = Pattern.compile(".*=> Saved new information to database: Mod\\{name='(.+)', version='([0-9.]+)', url='.+', lastUpdate=([0-9]+),.*");

    /**
     * Method to call to start the watcher thread.
     */
    public static void startThread() {
        TailerListener listener = new UpdateCheckerTracker();
        Tailer tailer = new Tailer(new File("/tmp/update_checker.log"), listener, 59950, true);
        new Thread(tailer).start();
    }

    @Override
    public void init(Tailer tailer) {
        log.info("Starting tracking /tmp/update_checker.log");
    }

    @Override
    public void fileNotFound() {
        log.warn("/tmp/update_checker.log was not found");
    }

    @Override
    public void fileRotated() {
        log.warn("/tmp/update_checker.log was rotated");
    }

    @Override
    public void handle(String line) {
        log.debug("New line in /tmp/update_checker.log: {}", line);
        lastLogLineDate = ZonedDateTime.now();

        if (line != null &&
                !line.contains("=== Started searching for updates") &&
                !line.contains("=== Ended searching for updates.") &&
                !line.contains("Waiting for 15 minute(s) before next update.")) {

            if (line.contains("I/O exception while doing networking operation (try ")) {
                lastLineIsNetworkError = true;
            } else if (!lastLineIsNetworkError || line.contains(" [Thread-1] ")) {
                // this isn't a muted line! truncate it if it is too long for Discord
                if (line.length() > 1998) {
                    line = line.substring(0, 1998);
                }

                lastLineIsNetworkError = false;

                // and post it!
                executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, "`" + line + "`");

                // flag Lua Cutscenes updates
                if (line.contains("name='LuaCutscenes'")) {
                    luaCutscenesUpdated = true;
                }

                // when a mod change or anything Banana Mirror happens, call a webhook.
                if (line.contains("Saved new information to database:")
                        || line.contains("was deleted from the database")
                        || (line.contains("Adding to the excluded files list.") && !line.contains("database already contains more recent file"))
                        || line.contains("Adding to the no yaml files list.")
                        || line.contains("to Banana Mirror")
                        || line.contains("from Banana Mirror")) {

                    String truncatedLine = line;
                    if (truncatedLine.contains(" - ")) {
                        truncatedLine = truncatedLine.substring(truncatedLine.indexOf(" - ") + 3);
                    }
                    if (truncatedLine.startsWith("=> ")) {
                        truncatedLine = truncatedLine.substring(3);
                    }
                    truncatedLine = findEmoji(truncatedLine) + " " + truncatedLine;

                    for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
                        executeWebhook(webhook, truncatedLine);
                    }

                    // send warning messages (error parsing yaml file & no yaml file) to alert hooks
                    if (truncatedLine.startsWith(":warning:")) {
                        HashSet<String> webhooks = new HashSet<>(SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS);
                        SecretConstants.UPDATE_CHECKER_HOOKS.forEach(webhooks::remove); // we just sent the message to those :p
                        webhooks.remove(SecretConstants.UPDATE_CHECKER_LOGS_HOOK); // and this one got the raw log line

                        for (String webhook : webhooks) {
                            // send the message to the Banana Watch list, removing the "adding to list" that only makes sense for the Update Checker.
                            executeWebhook(webhook,
                                    truncatedLine
                                            .replace("Adding to the excluded files list.", "")
                                            .replace("Adding to the no yaml files list.", "")
                                            .trim(),
                                    "https://cdn.discordapp.com/avatars/793432836912578570/0a3f716e15c8c3adca6c461c2d64553e.png?size=128",
                                    "Banana Watch");
                        }

                        executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":information_source: GameBanana managers were notified about this.");
                    }
                }

                // check whether we should send it to the speedrun.com webhook as well.
                Matcher savedNewModMatch = SAVED_MOD_PATTERN.matcher(line);
                if (savedNewModMatch.matches()) {
                    String modName = savedNewModMatch.group(1);
                    String modVersion = savedNewModMatch.group(2);
                    String modUpdatedTime = savedNewModMatch.group(3);

                    try (InputStream is = CloudStorageUtils.getCloudStorageInputStream("src_mod_update_notification_ids.json")) {
                        List<String> srcModIds = new JSONArray(IOUtils.toString(is, UTF_8)).toList()
                                .stream()
                                .map(Object::toString)
                                .collect(Collectors.toCollection(ArrayList::new));

                        if (srcModIds.contains(modName)) {
                            executeWebhook(SecretConstants.SRC_UPDATE_CHECKER_HOOK, "**" + modName + "** was updated to version **" + modVersion + "** on <t:" + modUpdatedTime + ":f>.");
                            executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":information_source: SRC staff was notified about this.");
                        }

                    } catch (IOException e) {
                        log.error("Error while fetching SRC mod update notification ID list", e);
                        executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":x: Error while fetching SRC mod update notification ID list: " + e);
                    }
                }
            }
        }

        if (line != null && line.contains("=== Ended searching for updates.")) {
            // refresh is done! we should tell the frontend about it.
            try {
                String newEverestUpdateHash = hash("uploads/everestupdate.yaml");
                String newFileIdsHash = hash("modfilesdatabase/file_ids.yaml");

                if (!newEverestUpdateHash.equals(everestUpdateSha256)) {
                    log.info("Reloading everest_update.yaml as hash changed: {} -> {}", everestUpdateSha256, newEverestUpdateHash);
                    CloudStorageUtils.sendToCloudStorage("uploads/everestupdate.yaml", "everest_update.yaml", "text/yaml");
                    CloudStorageUtils.sendToCloudStorage("uploads/moddependencygraph.yaml", "mod_dependency_graph.yaml", "text/yaml");

                    HttpURLConnection conn = (HttpURLConnection) new URL("https://max480-random-stuff.appspot.com/celeste/everest-update-reload?key="
                            + SecretConstants.RELOAD_SHARED_SECRET).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(30000);
                    if (conn.getResponseCode() != 200) {
                        throw new IOException("Everest Update Reload API sent non 200 code: " + conn.getResponseCode());
                    }

                    everestUpdateSha256 = newEverestUpdateHash;

                    executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":repeat: everest_update.yaml and mod_dependency_graph.yaml were updated.");
                }

                {
                    // mod_search_database.yaml always changes, as it contains download and view counts.
                    log.info("Reloading mod_search_database.yaml");

                    CloudStorageUtils.sendToCloudStorage("uploads/modsearchdatabase.yaml", "mod_search_database.yaml", "text/yaml");

                    // build the new indices and send them to Cloud Storage
                    buildIndex();
                    pack("/tmp/mod_index", "/tmp/mod_index.zip");
                    CloudStorageUtils.sendToCloudStorage("/tmp/mod_index.zip", "mod_index.zip", "application/zip");
                    Files.delete(Paths.get("/tmp/mod_index.zip"));
                    FileUtils.deleteDirectory(new File("/tmp/mod_index"));

                    HttpURLConnection conn = (HttpURLConnection) new URL("https://max480-random-stuff.appspot.com/celeste/gamebanana-search-reload?key="
                            + SecretConstants.RELOAD_SHARED_SECRET).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(30000);
                    if (conn.getResponseCode() != 200) {
                        throw new IOException("Mod Search Reload API sent non 200 code: " + conn.getResponseCode());
                    }
                }

                if (!newFileIdsHash.equals(fileIdsSha256)) {
                    log.info("Reloading file_ids.yaml as hash changed: {} -> {}", fileIdsSha256, newFileIdsHash);
                    CloudStorageUtils.sendToCloudStorage("modfilesdatabase/file_ids.yaml", "file_ids.yaml", "text/yaml");

                    // if file_ids changed, it means the mod files database changed as well!
                    pack("modfilesdatabase", "/tmp/mod_files_database.zip");
                    CloudStorageUtils.sendToCloudStorage("/tmp/mod_files_database.zip", "mod_files_database.zip", "application/zip");
                    FileUtils.forceDelete(new File("/tmp/mod_files_database.zip"));

                    fileIdsSha256 = newFileIdsHash;

                    executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":repeat: file_ids.yaml and mod_files_database.zip were updated.");
                }

                if (luaCutscenesUpdated) {
                    // also update the Lua Cutscenes documentation mirror.
                    log.info("Re-uploading Lua Cutscenes docs!");
                    LuaCutscenesDocumentationUploader.updateLuaCutscenesDocumentation();
                    luaCutscenesUpdated = false;

                    executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":repeat: Lua Cutscenes documentation was updated.");
                }
            } catch (IOException e) {
                log.error("Error during a call to frontend to refresh databases", e);
                executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":x: Frontend call failed: " + e);
            }
        }
    }

    /**
     * Executes a webhook with the "Everest Update Checker" header, profile picture and name.
     *
     * @param url     The URL of the webhook
     * @param message The message to send
     */
    private void executeWebhook(String url, String message) {
        executeWebhook(url,
                message,
                "https://cdn.discordapp.com/attachments/445236692136230943/878508600509726730/unknown.png",
                "Everest Update Checker");
    }

    /**
     * Executes a webhook, logging but continuing if the call is failing.
     *
     * @param url      The URL of the webhook
     * @param message  The message to send
     * @param avatar   The URL to the avatar to use for the message
     * @param nickname The nickname that will be used for the message
     */
    private void executeWebhook(String url, String message, String avatar, String nickname) {
        try {
            WebhookExecutor.executeWebhook(url, avatar, nickname, message, ImmutableMap.of("X-Everest-Log", "true"));
        } catch (InterruptedException | IOException e) {
            log.error("Error while sending log message", e);
        }
    }

    private static String hash(String filePath) throws IOException {
        try (InputStream is = new FileInputStream(filePath)) {
            return DigestUtils.sha256Hex(is);
        }
    }

    /**
     * Gives the most appropriate emoji for an update checker log line.
     */
    private String findEmoji(String line) {
        if (line.contains("Saved new information to database:")) {
            return ":white_check_mark:";
        } else if (line.contains("was deleted from the database")) {
            return ":x:";
        } else if (line.contains("Adding to the excluded files list.") || line.contains("Adding to the no yaml files list.")) {
            return ":warning:";
        } else if (line.contains("to Banana Mirror")) {
            return ":outbox_tray:";
        } else if (line.contains("from Banana Mirror")) {
            return ":wastebasket:";
        }
        return ":arrow_right:";
    }

    @Override
    public void handle(Exception ex) {
        log.error("Error while tracking /tmp/update_checker.log", ex);
        executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, "`Error while tracking /tmp/update_checker.log: " + ex.toString() + "`");
    }

    /**
     * Zips an entire folder to a zip (thanks https://stackoverflow.com/a/32052016).
     *
     * @param sourceDirPath The path to the directory to compress
     * @param zipFilePath   The path to the destination zip
     * @throws IOException In case an error occurs while zipping the file
     */
    private static void pack(String sourceDirPath, String zipFilePath) throws IOException {
        Path p = Files.createFile(Paths.get(zipFilePath));
        try (ZipOutputStream zs = new ZipOutputStream(Files.newOutputStream(p))) {
            zs.setLevel(Deflater.BEST_COMPRESSION);
            Path pp = Paths.get(sourceDirPath);
            if (!Files.walk(pp)
                    .filter(path -> !Files.isDirectory(path))
                    .allMatch(path -> {
                        ZipEntry zipEntry = new ZipEntry(pp.relativize(path).toString());
                        try {
                            zs.putNextEntry(zipEntry);
                            Files.copy(path, zs);
                            zs.closeEntry();
                            return true;
                        } catch (IOException e) {
                            log.error("Unable to zip a file", e);
                            return false;
                        }
                    })) {

                throw new IOException("Some files failed to zip!");
            }
        }
    }

    private static void buildIndex() throws IOException {
        try (InputStream connectionToDatabase = new FileInputStream("uploads/modsearchdatabase.yaml")) {
            // download the mods
            List<HashMap<String, Object>> mods = new Yaml().load(connectionToDatabase);
            log.debug("There are " + mods.size() + " mods in the search database.");

            new File("/tmp/mod_index").mkdir();

            FSDirectory newDirectory = FSDirectory.open(Paths.get("/tmp/mod_index"));

            // feed the mods to Lucene so that it indexes them
            try (IndexWriter index = new IndexWriter(newDirectory, new IndexWriterConfig(new StandardAnalyzer()))) {
                List<ModInfo> newModDatabaseForSorting = new LinkedList<>();
                Map<Integer, String> newModCategories = new HashMap<>();

                for (HashMap<String, Object> mod : mods) {
                    int categoryId = -1;

                    Document modDocument = new Document();
                    modDocument.add(new TextField("type", mod.get("GameBananaType").toString(), Field.Store.YES));
                    modDocument.add(new TextField("id", mod.get("GameBananaId").toString(), Field.Store.YES));
                    modDocument.add(new TextField("name", mod.get("Name").toString(), Field.Store.YES));
                    modDocument.add(new TextField("author", mod.get("Author").toString(), Field.Store.NO));
                    modDocument.add(new TextField("summary", mod.get("Description").toString(), Field.Store.NO));
                    modDocument.add(new TextField("description", Jsoup.parseBodyFragment(mod.get("Text").toString()).text(), Field.Store.NO));
                    if (mod.get("CategoryName") != null) {
                        modDocument.add(new TextField("category", mod.get("CategoryName").toString(), Field.Store.NO));

                        categoryId = (int) mod.get("CategoryId");
                        newModCategories.put(categoryId, mod.get("CategoryName").toString());
                    }
                    index.addDocument(modDocument);

                    newModDatabaseForSorting.add(new ModInfo(mod.get("GameBananaType").toString(), (int) mod.get("GameBananaId"),
                            (int) mod.get("Likes"), (int) mod.get("Views"), (int) mod.get("Downloads"), categoryId, (int) mod.get("CreatedDate"), mod));
                }

                try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("/tmp/mod_search_database.ser"))) {
                    oos.writeObject(newModDatabaseForSorting);
                    oos.writeObject(newModCategories);
                }
                CloudStorageUtils.sendToCloudStorage("/tmp/mod_search_database.ser", "mod_search_database.ser", "application/octet-stream");
                new File("/tmp/mod_search_database.ser").delete();
            }

            log.debug("Index directory contains " + newDirectory.listAll().length + " files.");

            newDirectory.close();
        }
    }
}
