package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.EventListener;
import ovh.maddie480.everest.updatechecker.FileDownloader;
import ovh.maddie480.everest.updatechecker.Mod;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.discord.modstructureverifier.ModStructureVerifier;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static com.max480.randomstuff.backend.celeste.crontabs.UpdateCheckerTracker.ModInfo;
import static java.nio.charset.StandardCharsets.UTF_8;
import static ovh.maddie480.randomstuff.backend.celeste.crontabs.GameBananaAutomatedChecks.enhanceYourWebhook;
import static ovh.maddie480.randomstuff.backend.celeste.crontabs.GameBananaAutomatedChecks.getMaskedEnhancedEmbedLink;

/**
 * A service that follows the Update Checker logs and re-posts them to a Discord channel.
 * It also calls frontend APIs to make it aware of database changes, and reload it as necessary.
 */
public class UpdateCheckerTracker extends EventListener {
    private static final Logger log = LoggerFactory.getLogger(UpdateCheckerTracker.class);

    private String everestUpdateSha256 = "[first check]";
    private String modSearchDatabaseSha256 = "[first check]";
    private String fileIdsSha256 = "[first check]";

    private boolean currentUpdateIsFull = false;
    private long lastFullCheckTimestamp = 0L;
    private long lastIncrementalCheckTimestamp = 0L;
    private long lastFullCheckDuration = 0L;
    private long lastIncrementalCheckDuration = 0L;
    private final List<Map<String, Object>> latestUpdates = new ArrayList<>();

    public UpdateCheckerTracker() {
        try {
            // read back the latest updates that happened before the tracker was started up.
            try (BufferedReader br = Files.newBufferedReader(Paths.get("/shared/celeste/updater/status.json"))) {
                JSONObject updateCheckerStatusData = new JSONObject(new JSONTokener(br));

                lastFullCheckTimestamp = updateCheckerStatusData.getLong("lastFullCheckTimestamp");
                lastIncrementalCheckTimestamp = updateCheckerStatusData.getLong("lastIncrementalCheckTimestamp");
                lastFullCheckDuration = updateCheckerStatusData.getLong("lastFullCheckDuration");
                lastIncrementalCheckDuration = updateCheckerStatusData.getLong("lastIncrementalCheckDuration");

                for (Object o : updateCheckerStatusData.getJSONArray("latestUpdatesEntries")) {
                    JSONObject latestUpdatesEntry = (JSONObject) o;
                    latestUpdates.add(latestUpdatesEntry.toMap());
                }

                log.debug("Read latest updates entries: {}", latestUpdates);
            }

            Path updateCheckerTrackerState = Paths.get("update_checker_tracker_state.ser");

            // load state
            if (Files.exists(updateCheckerTrackerState)) {
                try (ObjectInputStream is = new ObjectInputStream(Files.newInputStream(updateCheckerTrackerState))) {
                    everestUpdateSha256 = is.readUTF();
                    modSearchDatabaseSha256 = is.readUTF();
                    fileIdsSha256 = is.readUTF();
                }
            }
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

        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/src-mod-update-notification-ids.json"))) {
            List<String> srcModIds = new JSONArray(new JSONTokener(is)).toList()
                    .stream()
                    .map(Object::toString)
                    .collect(Collectors.toCollection(ArrayList::new));

            if (srcModIds.contains(mod.getName())) {
                String message = "**" + mod.getName() + "** was updated to version **" + mod.getVersion() + "** on <t:" + mod.getLastUpdate() + ":f>.\n" +
                        ":arrow_right: <https://gamebanana.com/" + mod.getGameBananaType().toLowerCase(Locale.ROOT) + "s/" + mod.getGameBananaId() + ">";
                executeWebhookAsUpdateChecker(SecretConstants.SRC_UPDATE_CHECKER_HOOK, message);
                executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":information_source: Message sent to SRC staff:\n> " + message);
            }

        } catch (IOException e) {
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
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":mag_right: **" + modId + "** has "
                + pluralize(dependencyCount, "dependency", "dependencies") + " and "
                + pluralize(optionalDependencyCount, "optional dependency", "optional dependencies") + ".");
    }

    @Override
    public void modUpdatedIncrementally(String gameBananaType, int gameBananaId, String modName) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":repeat: **" + modName + "** " +
                "was updated incrementally.\n:arrow_right: <https://gamebanana.com/" + gameBananaType.toLowerCase(Locale.ROOT) + "s/" + gameBananaId + ">\n");
    }

    @Override
    public void modHasNoYamlFile(String gameBananaType, int gameBananaId, String fileUrl) {
        AtomicReference<String> message = new AtomicReference<>("contains a file that has no `everest.yaml`: " + fileUrl);
        AtomicBoolean sendFollowup = new AtomicBoolean(false);

        // is the everest.yaml actually in a subfolder? the update checker's FileDownloader should still have the file for us to check
        try (ZipFile zip = new ZipFile(FileDownloader.downloadFile(fileUrl).toFile())) {
            zip.stream()
                    .filter(entry -> entry.getName().endsWith("/everest.yaml"))
                    .findFirst()
                    .ifPresent(entry -> {
                        message.set("has a file that contains an `everest.yaml`, but it is located at `"
                                + entry.getName() + "` instead of the root of the zip: " + fileUrl);
                        sendFollowup.set(true);
                    });
        } catch (Exception e) {
            log.warn("Error while checking if everest.yaml is in subfolder", e);
        }

        for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
            executeWebhookAsBananaWatch(webhook, ":warning: Mod " + getMaskedEnhancedEmbedLink(gameBananaType, gameBananaId) + " " + message.get());
            if (sendFollowup.get()) {
                executeWebhookAsBananaWatch(webhook, "Make sure to zip the folder properly to avoid this: https://maddie480.ovh/img/zip.png");
            }
        }
    }

    @Override
    public void zipFileIsNotUTF8(String downloadUrl, String detectedEncoding) {
        executeWebhookAsBananaWatch(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: The zip at <" + downloadUrl + "> could not be read as a ZIP archive with UTF-8 file names. "
                + " It was read with the **" + detectedEncoding + "** encoding instead.");
    }

    @Override
    public void zipFileIsUnreadable(String gameBananaType, int gameBananaId, String fileUrl, IOException e) {
        for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
            executeWebhookAsBananaWatch(webhook, ":warning: Mod " + getMaskedEnhancedEmbedLink(gameBananaType, gameBananaId)
                    + " contains a file that could not be read as a ZIP file: " + fileUrl);
        }
        postExceptionToWebhook(e);
    }

    @Override
    public void zipFileIsUnreadableForFileListing(String gameBananaType, int gameBananaId, String fileUrl, Exception e) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: Mod " + getMaskedEnhancedEmbedLink(gameBananaType, gameBananaId)
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
            executeWebhookAsBananaWatch(webhook, ":warning: Mod " + getMaskedEnhancedEmbedLink(gameBananaType, gameBananaId)
                    + " contains a file that has the same ID **" + otherMod.getName() + "** as mod " + getMaskedEnhancedEmbedLink(otherMod.getGameBananaType(), otherMod.getGameBananaId()) + " : "
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
            executeWebhookAsBananaWatch(webhook, ":warning: Mod " + getMaskedEnhancedEmbedLink(gameBananaType, gameBananaId)
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

                try (InputStream is = Files.newInputStream(Paths.get("uploads/everestupdate.yaml"));
                     OutputStream os = Files.newOutputStream(Paths.get("/shared/celeste/updater/everest-update.yaml"))) {

                    Map<String, Map<String, Object>> yaml = YamlUtil.load(is);
                    for (Map<String, Object> entry : yaml.values()) entry.remove("MirrorURL");
                    YamlUtil.dump(yaml, os);
                }

                Files.writeString(Paths.get("/shared/celeste/updater/mod-dependency-graph.yaml"), convertModDependencyGraphToEverestYamlFormat(), UTF_8);

                HttpURLConnection conn = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/everest-update-reload?key="
                        + SecretConstants.RELOAD_SHARED_SECRET);
                if (conn.getResponseCode() != 200) {
                    throw new IOException("Everest Update Reload API sent non 200 code: " + conn.getResponseCode());
                }

                // update Mod Structure Verifier maps
                updateModStructureVerifierMaps();

                everestUpdateSha256 = newEverestUpdateHash;
                UpdateOutgoingWebhooks.changesHappened();
            }

            if (!newModSearchDatabaseHash.equals(modSearchDatabaseSha256)) {
                log.info("Reloading mod_search_database.yaml as hash changed: {} -> {}", modSearchDatabaseSha256, newModSearchDatabaseHash);

                Path modSearchDatabase = Paths.get("uploads/modsearchdatabase.yaml");

                { // sort the mod search database in a predictable order
                    List<Map<String, Object>> database;
                    try (InputStream is = Files.newInputStream(modSearchDatabase)) {
                        database = YamlUtil.load(is);
                    }
                    database.sort(Comparator
                            .<Map<String, Object>, String>comparing(o -> (String) o.get("GameBananaType"))
                            .thenComparing(o -> (int) o.get("GameBananaId")));
                    try (OutputStream os = Files.newOutputStream(modSearchDatabase)) {
                        YamlUtil.dump(database, os);
                    }
                }

                Files.copy(modSearchDatabase, Paths.get("/shared/celeste/updater/mod-search-database.yaml"), StandardCopyOption.REPLACE_EXISTING);

                serializeModSearchDatabase();

                HttpURLConnection conn = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/gamebanana-search-reload?key="
                        + SecretConstants.RELOAD_SHARED_SECRET);
                if (conn.getResponseCode() != 200) {
                    throw new IOException("Mod Search Reload API sent non 200 code: " + conn.getResponseCode());
                }

                modSearchDatabaseSha256 = hash("uploads/modsearchdatabase.yaml");
                UpdateOutgoingWebhooks.changesHappened();
            }

            if (!newFileIdsHash.equals(fileIdsSha256)) {
                log.info("Reloading mod files database as file_ids.yaml hash changed: {} -> {}", fileIdsSha256, newFileIdsHash);

                pack("modfilesdatabase", "/tmp/mod_files_database.zip");
                Files.move(Paths.get("/tmp/mod_files_database.zip"), Paths.get("/shared/celeste/updater/mod-files-database.zip"), StandardCopyOption.REPLACE_EXISTING);

                fileIdsSha256 = newFileIdsHash;
            }

            updateUpdateCheckerStatusInformation(System.currentTimeMillis() - postProcessingStart + timeTakenMilliseconds);

            // save state
            try (ObjectOutputStream os = new ObjectOutputStream(Files.newOutputStream(Paths.get("update_checker_tracker_state.ser")))) {
                os.writeUTF(everestUpdateSha256);
                os.writeUTF(modSearchDatabaseSha256);
                os.writeUTF(fileIdsSha256);
            }
        } catch (IOException e) {
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
                "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/update-checker.png",
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
                "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/gamebanana.png",
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
            if (url.startsWith("https://discord.com/")) {
                Pair<String, List<Map<String, Object>>> enhanced = enhanceYourWebhook(message);
                if (!enhanced.getRight().isEmpty()) {
                    WebhookExecutor.executeWebhook(url, avatar, nickname, enhanced.getLeft(), enhanced.getRight());
                    return;
                }
            }
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

            try (Stream<Path> walker = Files.walk(pp)) {
                if (!walker
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
    }

    private static void serializeModSearchDatabase() throws IOException {
        try (InputStream connectionToDatabase = new FileInputStream("uploads/modsearchdatabase.yaml")) {
            // download the mods
            List<HashMap<String, Object>> mods = YamlUtil.load(connectionToDatabase);
            log.debug("There are " + mods.size() + " mods in the search database.");

            // serialize the mod list for the frontend to be able to load it
            List<ModInfo> modDatabaseForSorting = new LinkedList<>();
            Map<Integer, String> modCategories = new HashMap<>();

            for (HashMap<String, Object> mod : mods) {
                modCategories.put((int) mod.get("CategoryId"), mod.get("CategoryName").toString());
                if (mod.containsKey("SubcategoryId")) {
                    modCategories.put((int) mod.get("SubcategoryId"), mod.get("SubcategoryName").toString());
                }

                HashMap<String, Object> modWithTokenizedName = new HashMap<>(mod);
                modWithTokenizedName.put("TokenizedName", tokenize((String) mod.get("Name")));

                modDatabaseForSorting.add(new ModInfo(mod.get("GameBananaType").toString(), (int) mod.get("GameBananaId"),
                        (int) mod.get("Likes"), (int) mod.get("Views"), (int) mod.get("Downloads"), (int) mod.get("CategoryId"),
                        mod.containsKey("SubcategoryId") ? (int) mod.get("SubcategoryId") : null,
                        (int) mod.get("CreatedDate"), modWithTokenizedName));
            }

            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("/tmp/mod_search_database.ser"))) {
                oos.writeObject(modDatabaseForSorting);
                oos.writeObject(modCategories);
            }
            Files.move(Paths.get("/tmp/mod_search_database.ser"), Paths.get("/shared/celeste/mod-search-database.ser"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String[] tokenize(String string) {
        string = StringUtils.stripAccents(string.toLowerCase(Locale.ROOT)) // "Pokémon" => "pokemon"
                .replace("'", "") // "Maddie's Helping Hand" => "maddies helping hand"
                .replaceAll("[^a-z0-9* ]", " "); // "The D-Sides Pack" => "the d sides pack"
        while (string.contains("  ")) string = string.replace("  ", " ");
        return string.split(" ");
    }

    /**
     * Converts mod_dependency_graph.yaml to an everest.yaml-like format (see {@link #keyValueToEverestYamlFormat(Map)}).
     */
    private static String convertModDependencyGraphToEverestYamlFormat() throws IOException {
        // read
        Map<String, Map<String, Object>> dependencyGraph;
        try (InputStream is = new FileInputStream("uploads/moddependencygraph.yaml")) {
            dependencyGraph = YamlUtil.load(is);
        }

        // convert
        for (Map<String, Object> entry : dependencyGraph.values()) {
            entry.put("Dependencies", keyValueToEverestYamlFormat((Map<String, Object>) entry.get("Dependencies")));
            entry.put("OptionalDependencies", keyValueToEverestYamlFormat((Map<String, Object>) entry.get("OptionalDependencies")));
        }

        // write
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            YamlUtil.dump(dependencyGraph, os);
            return os.toString(UTF_8);
        }
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
    public static void updateModStructureVerifierMaps() throws IOException {
        log.info("Updating Mod Structure Verifier entity maps...");

        Map<String, String> assets = getElementMap("", file -> {
            try (InputStream is = new FileInputStream(file)) {
                return YamlUtil.<List<String>>load(is)
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

    /**
     * Updates version numbers of helpers that are hosted on GitHub, in order to make them available for the
     * Mod Structure Verifier.
     * Run hourly.
     */
    public static void updatePrivateHelpersFromGitHub() throws IOException {
        // load version numbers from private helpers hosted on GitHub, and store them in a file for the everest.yaml validator.
        Map<String, String> extraYamls = new HashMap<>();

        for (String extraYaml : SecretConstants.EVEREST_YAMLS_FROM_GITHUB) {
            HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(extraYaml);
            connection.setRequestProperty("Accept", "application/vnd.github.v3.raw");
            connection.setRequestProperty("Authorization", "Basic " + SecretConstants.GITHUB_MAIN_ACCOUNT_BASIC_AUTH);

            try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
                List<Map<String, Object>> contents = YamlUtil.load(is);
                extraYamls.put((String) contents.get(0).get("Name"), (String) contents.get(0).get("Version"));
            }
        }

        try (BufferedWriter bw = Files.newBufferedWriter(Paths.get("/shared/celeste/everest-yamls-from-github.json"))) {
            new JSONObject(extraYamls).write(bw);
        }
        log.info("Updated everest.yamls from GitHub with: {}", extraYamls);
    }

    private static Map<String, String> getEntityMap(String type) throws IOException {
        Map<String, String> ahornEntities = getElementMap("ahorn_", file -> {
            try (InputStream is = new FileInputStream(file)) {
                Map<String, List<String>> info = YamlUtil.load(is);
                return info.get(type);
            }
        });

        Map<String, String> loennEntities = getElementMap("loenn_", file -> {
            try (InputStream is = new FileInputStream(file)) {
                Map<String, List<String>> info = YamlUtil.load(is);
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
            updaterDatabase = YamlUtil.load(is);
        }

        Map<String, String> elementMap = new HashMap<>();
        Set<String> duplicateElements = new HashSet<>();

        // go through the contents of each mod in the database, to list out its assets.
        for (Map.Entry<String, Map<String, Object>> entry : updaterDatabase.entrySet()) {
            String depUrl = (String) entry.getValue().get("URL");

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
            Map<Object, Object> mods = YamlUtil.load(is);
            result.put("modCount", mods.size());
        }

        log.info("Uploading new Update Checker status: {}", result);
        Files.writeString(Paths.get("/shared/celeste/updater/status.json"), result.toString(), UTF_8);
    }
}
