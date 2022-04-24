package com.max480.discord.randombots;

import com.google.api.gax.paging.Page;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.Payload;
import com.google.cloud.storage.StorageException;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListener;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    private static class LatestUpdatesEntry {
        public boolean isAddition;
        public String name;
        public String version;
        public String url;
        public String date;
        public long timestamp;

        public LatestUpdatesEntry(boolean isAddition, String name, String version, String url, String date, long timestamp) {
            this.isAddition = isAddition;
            this.name = name;
            this.version = version;
            this.url = url;
            this.date = date;
            this.timestamp = timestamp;
        }

        public Map<String, Object> toMap() {
            return ImmutableMap.of(
                    "isAddition", isAddition,
                    "name", name,
                    "version", version,
                    "url", url,
                    "date", date,
                    "timestamp", timestamp);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(UpdateCheckerTracker.class);

    private static boolean lastLineIsNetworkError = false;
    protected static ZonedDateTime lastLogLineDate = ZonedDateTime.now();
    protected static ZonedDateTime lastEndOfCheckForUpdates = ZonedDateTime.now();

    private static boolean luaCutscenesUpdated = false;

    private static String everestUpdateSha256 = "[first check]";
    private static String fileIdsSha256 = "[first check]";

    private static final String MOD_INFO_REGEX = "Mod\\{name='(.+)', version='(.+)', url='(.+)', lastUpdate=([0-9]+), xxHash=\\[(.+)], gameBananaType='(.+)', gameBananaId=([0-9]+), size=([0-9]+)}";
    private static final Pattern MOD_INFO_EXTRACTOR = Pattern.compile(".*" + MOD_INFO_REGEX + ".*");
    private static final Pattern SAVED_NEW_MOD_EXTRACTOR = Pattern.compile(".*=> Saved new information to database: " + MOD_INFO_REGEX + ".*");

    private static Pattern gamebananaLinkRegex = Pattern.compile(".*https://gamebanana.com/mmdl/([0-9]+).*");
    private static List<String> queuedGameBananaModMessages = new ArrayList<>();

    private static ConcurrentLinkedQueue<String> logLines = new ConcurrentLinkedQueue<>();

    private static final Logging logging = LoggingOptions.getDefaultInstance().toBuilder().setProjectId("max480-random-stuff").build().getService();

    /**
     * Method to call to start the watcher thread.
     */
    public static void startThread() {
        TailerListener listener = new UpdateCheckerTracker();
        Tailer tailer = new Tailer(new File("/tmp/update_checker.log"), listener, 59950, true);

        Thread thread = new Thread(tailer);
        thread.setName("Update Checker Tracker");
        thread.start();
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
        logLines.add(line);
    }

    // called from a global "run loop" every minute
    public static void update() {
        while (!logLines.isEmpty()) {
            updateLine(logLines.poll());
        }
    }

    private static void updateLine(String line) {
        log.debug("Handling line from /tmp/update_checker.log: {}", line);
        lastLogLineDate = ZonedDateTime.now();

        if (line != null &&
                !line.contains("=== Started searching for updates") &&
                !line.contains("=== Ended searching for updates.") &&
                !line.contains("Waiting for 15 minute(s) before next update.")) {

            if (line.contains("I/O exception while doing networking operation (try ")) {
                lastLineIsNetworkError = true;
            } else if (!lastLineIsNetworkError || line.contains(" [Everest Update Checker] ")) {
                // this isn't a muted line! truncate it if it is too long for Discord
                if (line.length() > 1998) {
                    line = line.substring(0, 1998);
                }

                lastLineIsNetworkError = false;

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
                        || line.contains("from Banana Mirror")
                        || line.contains("Uncaught error while updating the database.")) {

                    String truncatedLine = line;
                    if (truncatedLine.contains(" - ")) {
                        truncatedLine = truncatedLine.substring(truncatedLine.indexOf(" - ") + 3);
                    }
                    if (truncatedLine.startsWith("=> ")) {
                        truncatedLine = truncatedLine.substring(3);
                    }
                    String emoji = findEmoji(truncatedLine);

                    // if we manage to match all info, format the message differently!
                    Matcher modInfo = MOD_INFO_EXTRACTOR.matcher(line);
                    if (modInfo.matches()) {
                        if (line.contains("Saved new information to database:")) {
                            truncatedLine = "**" + modInfo.group(1) + "** was updated to version **" + modInfo.group(2) + "** on <t:" + modInfo.group(4) + ">.\n" +
                                    ":arrow_right: <https://gamebanana.com/" + modInfo.group(6).toLowerCase(Locale.ROOT) + "s/" + modInfo.group(7) + ">\n" +
                                    ":inbox_tray: <" + modInfo.group(3) + ">";
                        } else if (line.contains("was deleted from the database")) {
                            truncatedLine = "**" + modInfo.group(1) + "** was deleted from the database.";
                        }
                    }

                    truncatedLine = emoji + " " + truncatedLine;

                    for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
                        executeWebhook(webhook, truncatedLine);
                    }

                    // send warning messages (error parsing yaml file & no yaml file) to GameBanana alert hooks
                    if (truncatedLine.startsWith(":warning:")) {
                        // delay the message, because we want the mod files database to be up-to-date, to trace back which mod this file belongs to.
                        queuedGameBananaModMessages.add(
                                truncatedLine
                                        .replace("Adding to the excluded files list.", "")
                                        .replace("Adding to the no yaml files list.", "")
                                        .trim());
                    }
                } else {
                    // post the raw message to our internal webhook.
                    executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, "`" + line + "`");
                }

                // check whether we should send it to the speedrun.com webhook as well.
                Matcher savedNewModMatch = SAVED_NEW_MOD_EXTRACTOR.matcher(line);
                if (savedNewModMatch.matches()) {
                    String modName = savedNewModMatch.group(1);
                    String modVersion = savedNewModMatch.group(2);
                    String modUpdatedTime = savedNewModMatch.group(4);

                    log.debug("{} was updated to version {} on {}", modName, modVersion, modUpdatedTime);

                    try (InputStream is = CloudStorageUtils.getCloudStorageInputStream("src_mod_update_notification_ids.json")) {
                        List<String> srcModIds = new JSONArray(IOUtils.toString(is, UTF_8)).toList()
                                .stream()
                                .map(Object::toString)
                                .collect(Collectors.toCollection(ArrayList::new));

                        if (srcModIds.contains(modName)) {
                            String message = "**" + modName + "** was updated to version **" + modVersion + "** on <t:" + modUpdatedTime + ":f>.";
                            executeWebhook(SecretConstants.SRC_UPDATE_CHECKER_HOOK, message);
                            executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":information_source: Message sent to SRC staff:\n> " + message);
                        }

                    } catch (IOException | StorageException e) {
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

                    CloudStorageUtils.sendStringToCloudStorage(convertModDependencyGraphToEverestYamlFormat(),
                            "mod_dependency_graph_everest.yaml", "text/yaml");

                    HttpURLConnection conn = (HttpURLConnection) new URL("https://max480-random-stuff.appspot.com/celeste/everest-update-reload?key="
                            + SecretConstants.RELOAD_SHARED_SECRET).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(30000);
                    if (conn.getResponseCode() != 200) {
                        throw new IOException("Everest Update Reload API sent non 200 code: " + conn.getResponseCode());
                    }

                    updateModStructureVerifierMaps();

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

                for (String message : queuedGameBananaModMessages) {
                    // try to find out which mod this file belongs to.
                    Matcher extract = gamebananaLinkRegex.matcher(message);
                    if (extract.matches()) {
                        String fileId = extract.group(1);
                        Pair<String, String> mod = GameBananaAutomatedChecks.whichModDoesFileBelongTo(fileId);

                        if (mod != null) {
                            String itemtype = mod.getValue().split("/")[0];
                            String itemid = mod.getValue().split("/")[1];

                            message += " Mod link: https://gamebanana.com/" + itemtype.toLowerCase(Locale.ROOT) + "s/" + itemid;
                        }
                    }

                    HashSet<String> webhooks = new HashSet<>(SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS);
                    SecretConstants.UPDATE_CHECKER_HOOKS.forEach(webhooks::remove); // we just sent the message to those :p

                    for (String webhook : webhooks) {
                        // send the message to the Banana Watch list, removing the "adding to list" that only makes sense for the Update Checker.
                        executeWebhook(webhook, message,
                                "https://cdn.discordapp.com/avatars/793432836912578570/0a3f716e15c8c3adca6c461c2d64553e.png?size=128",
                                "Banana Watch");
                    }

                    executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":information_source: Message sent to GameBanana managers:\n> " + message);
                }

                queuedGameBananaModMessages.clear();

                updateUpdateCheckerStatusInformation();

                lastEndOfCheckForUpdates = ZonedDateTime.now();
            } catch (IOException | StorageException e) {
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
    private static void executeWebhook(String url, String message) {
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
    private static void executeWebhook(String url, String message, String avatar, String nickname) {
        try {
            WebhookExecutor.executeWebhook(url, avatar, nickname, message, ImmutableMap.of("X-Everest-Log", "true"));
        } catch (IOException e) {
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
    private static String findEmoji(String line) {
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
        } else if (line.contains("Uncaught error while updating the database.")) {
            return ":boom:";
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

    /**
     * Converts mod_dependency_graph.yaml to an everest.yaml-like format (see {@link #keyValueToEverestYamlFormat(Map)}).
     */
    private static String convertModDependencyGraphToEverestYamlFormat() throws IOException {
        // read
        Map<String, Map<String, Object>> dependencyGraph;
        try (InputStream is = new FileInputStream("uploads/moddependencygraph.yaml")) {
            dependencyGraph = new Yaml().load(is);
        }

        // convert
        for (Map<String, Object> entry : dependencyGraph.values()) {
            entry.put("Dependencies", keyValueToEverestYamlFormat((Map<String, Object>) entry.get("Dependencies")));
            entry.put("OptionalDependencies", keyValueToEverestYamlFormat((Map<String, Object>) entry.get("OptionalDependencies")));
        }

        // write
        return new Yaml().dumpAs(dependencyGraph, null, DumperOptions.FlowStyle.BLOCK);
    }

    /**
     * Converts a dictionary of
     * DependencyName: DependencyVersion
     * to a list of
     * - Name: DependencyName
     *   Version: DependencyVersion
     */
    private static List<Map<String, Object>> keyValueToEverestYamlFormat(Map<String, Object> keyValue) {
        return keyValue.entrySet().stream()
                .map(entry -> ImmutableMap.of(
                        "Name", entry.getKey(),
                        "Version", entry.getValue()))
                .collect(Collectors.toList());
    }

    // Function<File, List<String>> is no good when we can throw IOException.
    private interface EntryReader {
        List<String> readEntriesFromFile(File file) throws IOException;
    }

    /**
     * Updates the maps used by the Mod Structure Verifier to see in which mod each asset is.
     * Called on startup and each time everest_update.yaml is modified.
     */
    private static void updateModStructureVerifierMaps() throws IOException {
        log.info("Updating Mod Structure Verifier entity maps...");

        Map<String, String> assets = getElementMap("", file -> {
            try (InputStream is = new FileInputStream(file)) {
                return new Yaml().<List<String>>load(is)
                        .stream().filter(e -> e.toLowerCase(Locale.ROOT).startsWith("graphics/atlases/gameplay/bgs/")
                                || e.toLowerCase(Locale.ROOT).startsWith("graphics/atlases/gameplay/decals/"))
                        .collect(Collectors.toList());
            }
        });

        Map<String, String> entities = getEntityMap("Entities");
        Map<String, String> triggers = getEntityMap("Triggers");
        Map<String, String> effects = getEntityMap("Effects");

        ModStructureVerifier.updateAssetToModDictionary(assets, entities, triggers, effects);

        log.info("Mod Structure Verifier entity maps now contain {} assets, {} entities, {} triggers and {} effects.",
                assets.size(), entities.size(), triggers.size(), effects.size());
    }

    private static Map<String, String> getEntityMap(String type) throws IOException {
        Map<String, String> ahornEntities = getElementMap("ahorn_", file -> {
            try (InputStream is = new FileInputStream(file)) {
                Map<String, List<String>> info = new Yaml().load(is);
                return info.get(type);
            }
        });

        Map<String, String> loennEntities = getElementMap("loenn_", file -> {
            try (InputStream is = new FileInputStream(file)) {
                Map<String, List<String>> info = new Yaml().load(is);
                return info.get(type);
            }
        });

        // merge ahornEntities into loennEntities
        for (Map.Entry<String, String> ahornEntity : ahornEntities.entrySet()) {
            if (loennEntities.containsKey(ahornEntity.getKey()) && !ahornEntity.getValue().equals(loennEntities.get(ahornEntity.getKey()))) {
                // entity is present in both Ahorn and Loenn... but in different mods! so we don't want to retain it, this is ambiguous.
                loennEntities.remove(ahornEntity.getKey());
            } else {
                loennEntities.put(ahornEntity.getKey(), ahornEntity.getValue());
            }
        }

        return loennEntities;
    }

    private static Map<String, String> getElementMap(String databasePrefix, EntryReader reader) throws IOException {
        // load the updater database.
        Map<String, Map<String, Object>> updaterDatabase;
        try (InputStream is = new FileInputStream("uploads/everestupdate.yaml")) {
            updaterDatabase = new Yaml().load(is);
        }

        Map<String, String> elementMap = new HashMap<>();
        Set<String> duplicateElements = new HashSet<>();

        // go through the contents of each mod in the database, to list out its assets.
        for (Map.Entry<String, Map<String, Object>> entry : updaterDatabase.entrySet()) {
            String depUrl = (String) entry.getValue().get(com.max480.everest.updatechecker.Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL");

            if (depUrl.matches("https://gamebanana.com/mmdl/[0-9]+")) {
                // to do this, we are going to use the mod files database.
                File modFilesDatabaseFile = new File("modfilesdatabase/" +
                        entry.getValue().get("GameBananaType") + "/" +
                        entry.getValue().get("GameBananaId") + "/" + databasePrefix +
                        depUrl.substring("https://gamebanana.com/mmdl/".length()) + ".yaml");

                if (modFilesDatabaseFile.exists()) {
                    for (String element : reader.readEntriesFromFile(modFilesDatabaseFile)) {
                        element = element.toLowerCase(Locale.ROOT);
                        if (!duplicateElements.contains(element)) {
                            if (elementMap.containsKey(element)) {
                                // we found an element in multiple mods! do not include it, since it is ambiguous.
                                duplicateElements.add(element);
                                elementMap.remove(element);
                            } else {
                                elementMap.put(element, entry.getKey());
                            }
                        }
                    }
                }
            }
        }

        return elementMap;
    }

    private static void updateUpdateCheckerStatusInformation() throws IOException {
        Pair<Long, Integer> latestUpdatedAt = getLatestUpdatedAt();
        List<LatestUpdatesEntry> latestUpdatedMods = getLatestUpdatedMods();

        JSONObject result = new JSONObject();
        result.put("lastCheckTimestamp", latestUpdatedAt.getLeft());
        result.put("lastCheckDuration", latestUpdatedAt.getRight());
        result.put("latestUpdatesEntries", latestUpdatedMods.stream().map(LatestUpdatesEntry::toMap).collect(Collectors.toList()));

        try (InputStream is = new FileInputStream("uploads/everestupdate.yaml")) {
            Map<Object, Object> mods = new Yaml().load(is);
            result.put("modCount", mods.size());
        }

        log.info("Uploading new Update Checker status: {}", result);
        CloudStorageUtils.sendStringToCloudStorage(result.toString(), "update_checker_status.json", "application/json");
    }

    private static List<LatestUpdatesEntry> getLatestUpdatedMods() {
        final Page<LogEntry> logEntries = logging.listLogEntries(
                Logging.EntryListOption.sortOrder(Logging.SortingField.TIMESTAMP, Logging.SortingOrder.DESCENDING),
                Logging.EntryListOption.filter("(" +
                        "jsonPayload.message =~ \"^=> Saved new information to database:\" " +
                        "OR jsonPayload.message =~ \"^Mod .* was deleted from the database$\")" +
                        " AND timestamp >= \"" + ZonedDateTime.now().minusDays(7).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\""),
                Logging.EntryListOption.pageSize(5)
        );

        List<LatestUpdatesEntry> latestUpdatesEntries = new ArrayList<>();
        for (LogEntry logEntry : logEntries.getValues()) {
            String message = (String) logEntry.<Payload.JsonPayload>getPayload().getDataAsMap().get("message");

            final Matcher matcher = MOD_INFO_EXTRACTOR.matcher(message);
            if (matcher.matches()) {
                latestUpdatesEntries.add(new LatestUpdatesEntry(
                        message.startsWith("=> Saved new information to database:"),
                        matcher.group(1),
                        matcher.group(2),
                        "https://gamebanana.com/" + matcher.group(6).toLowerCase(Locale.ROOT) + "s/" + matcher.group(7),
                        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(logEntry.getInstantTimestamp().atZone(ZoneId.of("UTC"))),
                        logEntry.getInstantTimestamp().toEpochMilli() / 1000L
                ));
            }
        }

        return latestUpdatesEntries;
    }

    private static Pair<Long, Integer> getLatestUpdatedAt() {
        final Page<LogEntry> lastCheck = logging.listLogEntries(
                Logging.EntryListOption.sortOrder(Logging.SortingField.TIMESTAMP, Logging.SortingOrder.DESCENDING),
                Logging.EntryListOption.filter("jsonPayload.message =~ \"^=== Ended searching for updates.\""),
                Logging.EntryListOption.pageSize(1)
        );

        for (LogEntry entry : lastCheck.getValues()) {
            // extract the duration
            String logContent = entry.<Payload.JsonPayload>getPayload().getDataAsMap().get("message").toString();
            if (logContent.endsWith(" ms.")) {
                logContent = logContent.substring(0, logContent.length() - 4);
                logContent = logContent.substring(logContent.lastIndexOf(" ") + 1);
                int timeMs = Integer.parseInt(logContent);

                return Pair.of(entry.getInstantTimestamp().toEpochMilli(), timeMs);
            }
        }

        return Pair.of(0L, 0);
    }
}
