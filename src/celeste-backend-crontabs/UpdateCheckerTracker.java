package com.max480.discord.randombots;

import com.google.cloud.storage.*;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListener;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A service that follows the Update Checker logs and re-posts them to a Discord channel.
 * It also calls frontend APIs to make it aware of database changes, and reload it as necessary.
 */
public class UpdateCheckerTracker implements TailerListener {
    private static final Logger log = LoggerFactory.getLogger(UpdateCheckerTracker.class);

    private static boolean lastLineIsNetworkError = false;
    protected static ZonedDateTime lastLogLineDate = ZonedDateTime.now();

    private static boolean luaCutscenesUpdated = false;

    private static final Storage storage = StorageOptions.newBuilder().setProjectId("max480-random-stuff").build().getService();

    private static String everestUpdateSha256 = "[first check]";
    private static String modSearchDatabaseSha256 = "[first check]";
    private static String fileIdsSha256 = "[first check]";

    // "src" as in "speedrun.com"
    private static final Set<String> SRC_MOD_IDS = new HashSet<>(Arrays.asList("QuickieMountain2", "Glyph", "Monika's D-Sides", "SpringCollab2020",
            "Into The Jungle", "PathofHopeChapter", "Shade World", "Anubi", "Insanelynicemap", "Veryepicmap", "DashPrologue", "Mario-1-1", "playablecredits",
            "24x33", "GateToTheStars"));
    private static final Pattern SAVED_MOD_PATTERN = Pattern.compile(".*=> Saved new information to database: Mod\\{name='([A-Za-z0-9 '-]+)', version='([0-9.]+)', url='.*', lastUpdate=([0-9]+),.*");

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
                }

                // check whether we should send it to the speedrun.com webhook as well.
                Matcher savedNewModMatch = SAVED_MOD_PATTERN.matcher(line);
                if (savedNewModMatch.matches()) {
                    String modName = savedNewModMatch.group(1);
                    String modVersion = savedNewModMatch.group(2);
                    String modUpdatedTime = Instant.ofEpochSecond(Long.parseLong(savedNewModMatch.group(3))).atZone(ZoneId.of("UTC"))
                            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG).withLocale(Locale.ENGLISH));

                    if (SRC_MOD_IDS.contains(modName)) {
                        executeWebhook(SecretConstants.SRC_UPDATE_CHECKER_HOOK, "**" + modName + "** was updated to version **" + modVersion + "** on " + modUpdatedTime + ".");
                        executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":information_source: SRC staff was notified about this.");
                    }
                }
            }
        }

        if (line != null && line.contains("=== Ended searching for updates.")) {
            // refresh is done! we should tell the frontend about it.
            try {
                String newEverestUpdateHash = hash("uploads/everestupdate.yaml");
                String newModSearchDatabaseHash = hash("uploads/modsearchdatabase.yaml");
                String newFileIdsHash = hash("modfilesdatabase/file_ids.yaml");

                if (!newEverestUpdateHash.equals(everestUpdateSha256)) {
                    log.info("Reloading everest_update.yaml as hash changed: {} -> {}", everestUpdateSha256, newEverestUpdateHash);
                    sendToCloudStorage("uploads/everestupdate.yaml", "everest_update.yaml", "text/yaml", false);

                    HttpURLConnection conn = (HttpURLConnection) new URL(SecretConstants.EVEREST_UPDATE_RELOAD_API).openConnection();
                    if (conn.getResponseCode() != 200) {
                        throw new IOException("Everest Update Reload API sent non 200 code: " + conn.getResponseCode());
                    }

                    everestUpdateSha256 = newEverestUpdateHash;
                }

                if (!newModSearchDatabaseHash.equals(modSearchDatabaseSha256)) {
                    log.info("Reloading mod_search_database.yaml as hash changed: {} -> {}", modSearchDatabaseSha256, newModSearchDatabaseHash);

                    // purge old indices
                    List<BlobId> toDelete = new ArrayList<>();
                    for (Blob b : storage.list("max480-random-stuff.appspot.com").iterateAll()) {
                        if (b.getName().startsWith("mod_search_index/")) {
                            toDelete.add(b.getBlobId());
                        }
                    }
                    if (!toDelete.isEmpty()) {
                        storage.delete(toDelete);
                    }

                    // build the new ones
                    buildIndex();

                    // and send them to Cloud Storage
                    if (!Files.walk(Paths.get("/tmp/mod_index"))
                            .filter(Files::isRegularFile)
                            .allMatch(f -> {
                                try {
                                    sendToCloudStorage(f.toAbsolutePath().toString(),
                                            "mod_search_index/" + f.toAbsolutePath().toString().substring(15),
                                            "application/octet-stream", false);
                                    return true;
                                } catch (IOException e) {
                                    log.error("Could not send {} to Cloud Storage", f, e);
                                    return false;
                                }
                            })) {

                        throw new IOException("Some index files could not be sent to Cloud Storage!");
                    }

                    // remove the directory now.
                    FileUtils.deleteDirectory(new File("/tmp/mod_index"));

                    sendToCloudStorage("uploads/modsearchdatabase.yaml", "mod_search_database.yaml", "text/yaml", false);

                    HttpURLConnection conn = (HttpURLConnection) new URL(SecretConstants.MOD_SEARCH_RELOAD_API).openConnection();
                    if (conn.getResponseCode() != 200) {
                        throw new IOException("Mod Search Reload API sent non 200 code: " + conn.getResponseCode());
                    }

                    modSearchDatabaseSha256 = newModSearchDatabaseHash;
                }

                if (!newFileIdsHash.equals(fileIdsSha256)) {
                    log.info("Reloading file_ids.yaml as hash changed: {} -> {}", fileIdsSha256, newFileIdsHash);
                    sendToCloudStorage("modfilesdatabase/file_ids.yaml", "file_ids.yaml", "text/yaml", false);

                    // if file_ids changed, it means the mod files database changed as well!
                    pack("modfilesdatabase", "/tmp/mod_files_database.zip");
                    sendToCloudStorage("/tmp/mod_files_database.zip", "mod_files_database.zip", "application/zip", false);
                    FileUtils.forceDelete(new File("/tmp/mod_files_database.zip"));

                    fileIdsSha256 = newFileIdsHash;
                }

                if (luaCutscenesUpdated) {
                    // also tell the frontend that Lua Cutscenes got updated, so that it can mirror the docs again.
                    log.info("Re-uploading Lua Cutscenes docs!");
                    HttpURLConnection conn = (HttpURLConnection) new URL(SecretConstants.LUA_CUTSCENES_DOC_UPLOAD_API).openConnection();
                    if (conn.getResponseCode() != 200) {
                        throw new IOException("Lua Cutscenes Documentation Upload API sent non 200 code: " + conn.getResponseCode());
                    } else {
                        luaCutscenesUpdated = false;
                    }
                }
            } catch (IOException e) {
                log.error("Error during a call to frontend to refresh databases", e);
                executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, "`Frontend call failed: " + e.toString() + "`");
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
        try {
            WebhookExecutor.executeWebhook(url,
                    "https://cdn.discordapp.com/attachments/445236692136230943/878508600509726730/unknown.png",
                    "Everest Update Checker",
                    message,
                    ImmutableMap.of("X-Everest-Log", "true"));
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

    public static void sendToCloudStorage(String file, String name, String contentType, boolean isPublic) throws IOException {
        BlobId blobId = BlobId.of("max480-random-stuff.appspot.com", name);
        BlobInfo.Builder blobInfoBuilder = BlobInfo.newBuilder(blobId).setContentType(contentType);
        if (!isPublic) {
            // do not cache private stuff.
            blobInfoBuilder.setCacheControl("no-store");
        }
        storage.createFrom(blobInfoBuilder.build(), Paths.get(file), 4096);

        if (isPublic) {
            storage.createAcl(blobId, Acl.of(Acl.User.ofAllUsers(), Acl.Role.READER));
        }
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

            FSDirectory newDirectory = FSDirectory.open(Paths.get("/tmp/mod_index")); // I know it's deprecated but creating a directory on App Engine is weird

            // feed the mods to Lucene so that it indexes them
            try (IndexWriter index = new IndexWriter(newDirectory, new IndexWriterConfig(new StandardAnalyzer()))) {
                for (HashMap<String, Object> mod : mods) {
                    Document modDocument = new Document();
                    modDocument.add(new TextField("type", mod.get("GameBananaType").toString(), Field.Store.YES));
                    modDocument.add(new TextField("id", mod.get("GameBananaId").toString(), Field.Store.YES));
                    modDocument.add(new TextField("name", mod.get("Name").toString(), Field.Store.YES));
                    modDocument.add(new TextField("author", mod.get("Author").toString(), Field.Store.NO));
                    modDocument.add(new TextField("summary", mod.get("Description").toString(), Field.Store.NO));
                    modDocument.add(new TextField("description", Jsoup.parseBodyFragment(mod.get("Text").toString()).text(), Field.Store.NO));
                    if (mod.get("CategoryName") != null) {
                        modDocument.add(new TextField("category", mod.get("CategoryName").toString(), Field.Store.NO));
                    }
                    index.addDocument(modDocument);
                }
            }

            log.debug("Index directory contains " + newDirectory.listAll().length + " files.");

            newDirectory.close();
        }
    }
}
