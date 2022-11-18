package com.max480.randomstuff.backend.celeste.crontabs;

import com.google.cloud.storage.StorageException;
import com.google.common.collect.ImmutableMap;
import com.max480.everest.updatechecker.EventListener;
import com.max480.everest.updatechecker.Mod;
import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.discord.modstructureverifier.ModStructureVerifier;
import com.max480.randomstuff.backend.utils.CloudStorageUtils;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import com.max480.randomstuff.backend.utils.WebhookExecutor;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A service that follows the Update Checker logs and re-posts them to a Discord channel.
 * It also calls frontend APIs to make it aware of database changes, and reload it as necessary.
 */
public class UpdateCheckerTracker extends EventListener {
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

    protected static ZonedDateTime lastEndOfCheckForUpdates = ZonedDateTime.now();

    private String everestUpdateSha256 = "[first check]";
    private String modSearchDatabaseSha256 = "[first check]";
    private String fileIdsSha256 = "[first check]";
    private boolean luaCutscenesUpdated = false;

    private boolean currentUpdateIsFull = false;
    private long lastFullCheckTimestamp = 0L;
    private long lastIncrementalCheckTimestamp = 0L;
    private long lastFullCheckDuration = 0L;
    private long lastIncrementalCheckDuration = 0L;
    private final List<Map<String, Object>> latestUpdates = new ArrayList<>();

    public UpdateCheckerTracker() {
        // read back the latest updates that happened before the tracker was started up.
        try (InputStream is = CloudStorageUtils.getCloudStorageInputStream("update_checker_status.json")) {
            JSONObject updateCheckerStatusData = new JSONObject(IOUtils.toString(is, UTF_8));

            lastFullCheckTimestamp = updateCheckerStatusData.getLong("lastFullCheckTimestamp");
            lastIncrementalCheckTimestamp = updateCheckerStatusData.getLong("lastIncrementalCheckTimestamp");
            lastFullCheckDuration = updateCheckerStatusData.getLong("lastFullCheckDuration");
            lastIncrementalCheckDuration = updateCheckerStatusData.getLong("lastIncrementalCheckDuration");

            for (Object o : updateCheckerStatusData.getJSONArray("latestUpdatesEntries")) {
                JSONObject latestUpdatesEntry = (JSONObject) o;
                latestUpdates.add(latestUpdatesEntry.toMap());
            }

            log.debug("Read latest updates entries: {}", latestUpdates);
        } catch (IOException e) {
            log.error("Could not initialize Update Checker Tracker!", e);
        }
    }

    @Override
    public void startedSearchingForUpdates(boolean full) {
        currentUpdateIsFull = full;
    }

    @Override
    public void uploadedModToBananaMirror(String fileName) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":outbox_tray: Uploaded mod zip " + fileName + " to Banana Mirror");
        }
    }

    @Override
    public void deletedModFromBananaMirror(String fileName) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":wastebasket: Deleted mod zip " + fileName + " from Banana Mirror");
        }
    }

    @Override
    public void uploadedImageToBananaMirror(String fileName) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":outbox_tray: Uploaded mod image " + fileName + " to Banana Mirror");
        }
    }

    @Override
    public void deletedImageFromBananaMirror(String fileName) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":wastebasket: Deleted mod image " + fileName + " from Banana Mirror");
        }
    }

    @Override
    public void uploadedRichPresenceIconToBananaMirror(String fileName, String originatingFileId) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":outbox_tray: Uploaded Rich Presence icon " + fileName +
                    " (coming from <https://gamebanana.com/mmdl/" + originatingFileId + ">) to Banana Mirror");
        }
    }

    @Override
    public void deletedRichPresenceIconFromBananaMirror(String fileName, String originatingFileId) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":wastebasket: Deleted Rich Presence icon " + fileName +
                    " (coming from <https://gamebanana.com/mmdl/" + originatingFileId + ">) from Banana Mirror");
        }
    }

    @Override
    public void savedNewInformationToDatabase(Mod mod) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":white_check_mark: **" + mod.getName() + "** was updated to version **" + mod.getVersion() + "** on <t:" + mod.getLastUpdate() + ">.\n" +
                    ":arrow_right: <https://gamebanana.com/" + mod.getGameBananaType().toLowerCase(Locale.ROOT) + "s/" + mod.getGameBananaId() + ">\n" +
                    ":inbox_tray: <" + mod.getUrl() + ">");
        }

        if (mod.getName().equals("LuaCutscenes")) {
            luaCutscenesUpdated = true;
        }

        try (InputStream is = CloudStorageUtils.getCloudStorageInputStream("src_mod_update_notification_ids.json")) {
            List<String> srcModIds = new JSONArray(IOUtils.toString(is, UTF_8)).toList()
                    .stream()
                    .map(Object::toString)
                    .collect(Collectors.toCollection(ArrayList::new));

            if (srcModIds.contains(mod.getName())) {
                String message = "**" + mod.getName() + "** was updated to version **" + mod.getVersion() + "** on <t:" + mod.getLastUpdate() + ":f>.\n" +
                        ":arrow_right: <https://gamebanana.com/" + mod.getGameBananaType().toLowerCase(Locale.ROOT) + "s/" + mod.getGameBananaId() + ">";
                executeWebhookAsUpdateChecker(SecretConstants.SRC_UPDATE_CHECKER_HOOK, message);
                executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":information_source: Message sent to SRC staff:\n> " + message);
            }

        } catch (IOException | StorageException e) {
            log.error("Error while fetching SRC mod update notification ID list", e);
            executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":x: Error while fetching SRC mod update notification ID list: " + e);
        }

        addModUpdateToLatestUpdatesList(mod, true);
    }

    @Override
    public void scannedZipContents(String fileUrl, int fileCount) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":mag_right: Found " + pluralize(fileCount, "file", "files") + " in " + fileUrl + ".");
    }

    @Override
    public void scannedAhornEntities(String fileUrl, int entityCount, int triggerCount, int effectCount) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":mag_right: Ahorn plugins: " + fileUrl + " has " + pluralize(entityCount, "entity", "entities") + ", " +
                pluralize(triggerCount, "trigger", "triggers") + " and " + pluralize(effectCount, "effect", "effects") + ".");
    }

    @Override
    public void scannedLoennEntities(String fileUrl, int entityCount, int triggerCount, int effectCount) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":mag_right: Lönn plugins: " + fileUrl + " has " + pluralize(entityCount, "entity", "entities") + ", " +
                pluralize(triggerCount, "trigger", "triggers") + " and " + pluralize(effectCount, "effect", "effects") + ".");
    }

    @Override
    public void scannedModDependencies(String modId, int dependencyCount, int optionalDependencyCount) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":mag_right: **" + modId + "** has " +
                pluralize(dependencyCount, "dependency", "dependencies") + " and " + pluralize(optionalDependencyCount, "optional dependency", "optional dependencies") + ".");
    }

    @Override
    public void modUpdatedIncrementally(String gameBananaType, int gameBananaId, String modName) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":repeat: **" + modName + "** " +
                "was updated incrementally.\n:arrow_right: <https://gamebanana.com/" + gameBananaType.toLowerCase(Locale.ROOT) + "s/" + gameBananaId + ">\n");
    }

    @Override
    public void modHasNoYamlFile(String gameBananaType, int gameBananaId, String fileUrl) {
        for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
            executeWebhookAsBananaWatch(webhook, ":warning: Mod https://gamebanana.com/" + gameBananaType.toLowerCase(Locale.ROOT) + "s/" + gameBananaId
                    + " contains a file that has no `everest.yaml`: " + fileUrl);
        }
    }

    @Override
    public void zipFileIsNotUTF8(String downloadUrl, String detectedEncoding) {
        for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
            executeWebhookAsBananaWatch(webhook, ":warning: The zip at <" + downloadUrl + "> could not be read as a ZIP archive with UTF-8 file names. "
                    + " It was read with the **" + detectedEncoding + "** encoding instead.");
        }
    }

    @Override
    public void zipFileIsUnreadable(String gameBananaType, int gameBananaId, String fileUrl, IOException e) {
        for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
            executeWebhookAsBananaWatch(webhook, ":warning: Mod https://gamebanana.com/" + gameBananaType.toLowerCase(Locale.ROOT) + "s/" + gameBananaId
                    + " contains a file that could not be read as a ZIP file: " + fileUrl);
        }
        postExceptionToWebhook(e);
    }

    @Override
    public void zipFileIsUnreadableForFileListing(String gameBananaType, int gameBananaId, String fileUrl, Exception e) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: Mod https://gamebanana.com/" + gameBananaType.toLowerCase(Locale.ROOT) + "s/" + gameBananaId
                + " contains a file that could not be read as a ZIP file for file listing: " + fileUrl);
        postExceptionToWebhook(e);
    }

    @Override
    public void moreRecentFileAlreadyExists(String gameBananaType, int gameBananaId, String fileUrl, Mod otherMod) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: File " + fileUrl + " was skipped because "
                + otherMod.getUrl() + " is more recent. Both are part of <https://gamebanana.com/" + gameBananaType.toLowerCase(Locale.ROOT) + "s/" + gameBananaId + ">.");
    }

    @Override
    public void currentVersionBelongsToAnotherMod(String gameBananaType, int gameBananaId, String fileUrl, Mod otherMod) {
        for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
            executeWebhookAsBananaWatch(webhook, ":warning: Mod https://gamebanana.com/" + gameBananaType.toLowerCase(Locale.ROOT) + "s/" + gameBananaId
                    + " contains a file that has the same ID **" + otherMod.getName() + "** as mod https://gamebanana.com/" + otherMod.getGameBananaType().toLowerCase(Locale.ROOT) + "s/" + otherMod.getGameBananaId() + " : "
                    + fileUrl);
        }
    }

    @Override
    public void modIsExcludedByName(Mod mod) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: A file with mod ID **" + mod.getName() + "** was skipped because this mod ID is blacklisted.");
    }

    @Override
    public void yamlFileIsUnreadable(String gameBananaType, int gameBananaId, String fileUrl, Exception e) {
        for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
            executeWebhookAsBananaWatch(webhook, ":warning: Mod https://gamebanana.com/" + gameBananaType.toLowerCase(Locale.ROOT) + "s/" + gameBananaId
                    + " contains an `everest.yaml` file that could not be parsed: " + fileUrl);
        }
        postExceptionToWebhook(e);
    }

    @Override
    public void modWasDeletedFromDatabase(Mod mod) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":x: **" + mod.getName() + "** was deleted from the database.");
        }

        addModUpdateToLatestUpdatesList(mod, false);
    }

    @Override
    public void modWasDeletedFromExcludedFileList(String fileUrl) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":x: " + fileUrl + " was deleted from the blacklist.");
    }

    @Override
    public void modWasDeletedFromNoYamlFileList(String fileUrl) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":x: " + fileUrl + " was deleted from the blacklist.");
    }

    @Override
    public void retriedIOException(IOException e) {
        // nothing!
    }

    @Override
    public void dependencyTreeScanException(String modId, Exception e) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: The dependency tree scan of mod **" + modId + "** failed.");
        postExceptionToWebhook(e);
    }

    @Override
    public void zipFileWalkthroughError(String gameBananaType, int gameBananaId, String fileUrl, Exception e) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: An error occurred when going through file " + fileUrl + ", that is part of " +
                "https://gamebanana.com/" + gameBananaType.toLowerCase(Locale.ROOT) + "s/" + gameBananaId + ".");
        postExceptionToWebhook(e);
    }

    @Override
    public void ahornPluginScanError(String fileUrl, Exception e) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: An error occurred when scanning Ahorn plugins for file " + fileUrl + ".");
        postExceptionToWebhook(e);
    }

    @Override
    public void loennPluginScanError(String fileUrl, Exception e) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: An error occurred when scanning Lönn plugins for file " + fileUrl + ".");
        postExceptionToWebhook(e);
    }

    @Override
    public void uncaughtError(Exception e) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":boom: Uncaught error while updating the database. Changes so far might be rolled back.");
        }
        postExceptionToWebhook(e);
    }

    private void postExceptionToWebhook(Exception e) {
        String stackTrace = ExceptionUtils.getStackTrace(e);
        if (stackTrace.length() > 1992) {
            stackTrace = stackTrace.substring(0, 1992);
        }
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, "```\n" + stackTrace + "\n```");
    }

    private void addModUpdateToLatestUpdatesList(Mod mod, boolean isAddition) {
        latestUpdates.add(0, ImmutableMap.of(
                "isAddition", isAddition,
                "name", mod.getName(),
                "version", mod.getVersion(),
                "url", "https://gamebanana.com/" + mod.getGameBananaType().toLowerCase(Locale.ROOT) + "s/" + mod.getGameBananaId(),
                "date", DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(ZonedDateTime.now(ZoneId.of("UTC"))),
                "timestamp", Instant.now().toEpochMilli() / 1000L));

        while (latestUpdates.size() > 5) {
            latestUpdates.remove(5);
        }
    }

    private String pluralize(int number, String singular, String plural) {
        return "**" + number + "** " + (number == 1 ? singular : plural);
    }

    @Override
    public void endedSearchingForUpdates(int modDownloadedCount, long timeTakenMilliseconds) {
        try {
            long postProcessingStart = System.currentTimeMillis();

            String newEverestUpdateHash = hash("uploads/everestupdate.yaml");
            String newModSearchDatabaseHash = hash("uploads/modsearchdatabase.yaml");
            String newFileIdsHash = hash("modfilesdatabase/file_ids.yaml");

            if (!newEverestUpdateHash.equals(everestUpdateSha256)) {
                log.info("Reloading everest_update.yaml as hash changed: {} -> {}", everestUpdateSha256, newEverestUpdateHash);
                CloudStorageUtils.sendToCloudStorage("uploads/everestupdate.yaml", "everest_update.yaml", "text/yaml");
                CloudStorageUtils.sendToCloudStorage("uploads/moddependencygraph.yaml", "mod_dependency_graph.yaml", "text/yaml");

                CloudStorageUtils.sendStringToCloudStorage(convertModDependencyGraphToEverestYamlFormat(),
                        "mod_dependency_graph_everest.yaml", "text/yaml");

                HttpURLConnection conn = ConnectionUtils.openConnectionWithTimeout("https://max480-random-stuff.appspot.com/celeste/everest-update-reload?key="
                        + SecretConstants.RELOAD_SHARED_SECRET);
                if (conn.getResponseCode() != 200) {
                    throw new IOException("Everest Update Reload API sent non 200 code: " + conn.getResponseCode());
                }

                updateModStructureVerifierMaps();

                everestUpdateSha256 = newEverestUpdateHash;
                UpdateOutgoingWebhooks.changesHappened();
            }

            if (!newModSearchDatabaseHash.equals(modSearchDatabaseSha256)) {
                log.info("Reloading mod_search_database.yaml as hash changed: {} -> {}", modSearchDatabaseSha256, newModSearchDatabaseHash);

                CloudStorageUtils.sendToCloudStorage("uploads/modsearchdatabase.yaml", "mod_search_database.yaml", "text/yaml");

                // build the new indices and send them to Cloud Storage
                buildIndex();
                pack("/tmp/mod_index", "/tmp/mod_index.zip");
                CloudStorageUtils.sendToCloudStorage("/tmp/mod_index.zip", "mod_index.zip", "application/zip");
                Files.delete(Paths.get("/tmp/mod_index.zip"));
                FileUtils.deleteDirectory(new File("/tmp/mod_index"));

                HttpURLConnection conn = ConnectionUtils.openConnectionWithTimeout("https://max480-random-stuff.appspot.com/celeste/gamebanana-search-reload?key="
                        + SecretConstants.RELOAD_SHARED_SECRET);
                if (conn.getResponseCode() != 200) {
                    throw new IOException("Mod Search Reload API sent non 200 code: " + conn.getResponseCode());
                }

                modSearchDatabaseSha256 = newModSearchDatabaseHash;
                UpdateOutgoingWebhooks.changesHappened();
            }

            if (!newFileIdsHash.equals(fileIdsSha256)) {
                log.info("Reloading file_ids.yaml as hash changed: {} -> {}", fileIdsSha256, newFileIdsHash);
                CloudStorageUtils.sendToCloudStorage("modfilesdatabase/file_ids.yaml", "file_ids.yaml", "text/yaml");

                // if file_ids changed, it means the mod files database changed as well!
                pack("modfilesdatabase", "/tmp/mod_files_database.zip");
                CloudStorageUtils.sendToCloudStorage("/tmp/mod_files_database.zip", "mod_files_database.zip", "application/zip");
                FileUtils.forceDelete(new File("/tmp/mod_files_database.zip"));

                fileIdsSha256 = newFileIdsHash;
            }

            if (luaCutscenesUpdated) {
                // also update the Lua Cutscenes documentation mirror.
                log.info("Re-uploading Lua Cutscenes docs!");
                LuaCutscenesDocumentationUploader.updateLuaCutscenesDocumentation();
                luaCutscenesUpdated = false;

                executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":repeat: Lua Cutscenes documentation was updated.");
            }

            updateUpdateCheckerStatusInformation(System.currentTimeMillis() - postProcessingStart + timeTakenMilliseconds);

            lastEndOfCheckForUpdates = ZonedDateTime.now();
        } catch (IOException | StorageException e) {
            log.error("Error during a call to frontend to refresh databases", e);
            executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":x: Frontend call failed: " + e);
        }
    }

    /**
     * Executes a webhook with the "Everest Update Checker" header, profile picture and name.
     *
     * @param url     The URL of the webhook
     * @param message The message to send
     */
    private static void executeWebhookAsUpdateChecker(String url, String message) {
        executeWebhook(url,
                message,
                "https://cdn.discordapp.com/attachments/445236692136230943/878508600509726730/unknown.png",
                "Everest Update Checker");
    }

    /**
     * Executes a webhook with the "Banana Watch" header, profile picture and name.
     *
     * @param url     The URL of the webhook
     * @param message The message to send
     */
    private static void executeWebhookAsBananaWatch(String url, String message) {
        executeWebhook(url,
                message,
                "https://cdn.discordapp.com/avatars/793432836912578570/0a3f716e15c8c3adca6c461c2d64553e.png?size=128",
                "Banana Watch");
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
                List<ModInfo> modDatabaseForSorting = new LinkedList<>();
                Map<Integer, String> modCategories = new HashMap<>();

                for (HashMap<String, Object> mod : mods) {
                    Document modDocument = new Document();
                    modDocument.add(new TextField("type", mod.get("GameBananaType").toString(), Field.Store.YES));
                    modDocument.add(new TextField("id", mod.get("GameBananaId").toString(), Field.Store.YES));
                    modDocument.add(new TextField("name", mod.get("Name").toString(), Field.Store.YES));
                    modDocument.add(new TextField("author", mod.get("Author").toString(), Field.Store.NO));
                    modDocument.add(new TextField("summary", mod.get("Description").toString(), Field.Store.NO));
                    modDocument.add(new TextField("description", Jsoup.parseBodyFragment(mod.get("Text").toString()).text(), Field.Store.NO));
                    modDocument.add(new TextField("category", mod.get("CategoryName").toString(), Field.Store.NO));
                    index.addDocument(modDocument);

                    if ("Mod".equals(mod.get("GameBananaType"))) {
                        modCategories.put((int) mod.get("CategoryId"), mod.get("CategoryName").toString());
                    }

                    modDatabaseForSorting.add(new ModInfo(mod.get("GameBananaType").toString(), (int) mod.get("GameBananaId"),
                            (int) mod.get("Likes"), (int) mod.get("Views"), (int) mod.get("Downloads"), (int) mod.get("CategoryId"),
                            (int) mod.get("CreatedDate"), mod));
                }

                try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("/tmp/mod_search_database.ser"))) {
                    oos.writeObject(modDatabaseForSorting);
                    oos.writeObject(modCategories);
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

    private void updateUpdateCheckerStatusInformation(long lastCheckDuration) throws IOException {
        if (currentUpdateIsFull) {
            lastFullCheckTimestamp = System.currentTimeMillis();
            lastFullCheckDuration = lastCheckDuration;
        } else {
            lastIncrementalCheckTimestamp = System.currentTimeMillis();
            lastIncrementalCheckDuration = lastCheckDuration;
        }

        JSONObject result = new JSONObject();
        result.put("lastFullCheckTimestamp", lastFullCheckTimestamp);
        result.put("lastIncrementalCheckTimestamp", lastIncrementalCheckTimestamp);
        result.put("lastFullCheckDuration", lastFullCheckDuration);
        result.put("lastIncrementalCheckDuration", lastIncrementalCheckDuration);
        result.put("latestUpdatesEntries", latestUpdates);

        try (InputStream is = new FileInputStream("uploads/everestupdate.yaml")) {
            Map<Object, Object> mods = new Yaml().load(is);
            result.put("modCount", mods.size());
        }

        log.info("Uploading new Update Checker status: {}", result);
        CloudStorageUtils.sendStringToCloudStorage(result.toString(), "update_checker_status.json", "application/json");
    }
}
