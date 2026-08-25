package ovh.maddie480.randomstuff.backend.celeste.moddatabase;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.CategoryRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.FileRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.MapEditorRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ModRecord;
import ovh.maddie480.randomstuff.backend.discord.modstructureverifier.ModStructureVerifier;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;
import ovh.maddie480.randomstuff.backend.utils.YamlUtil;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static ovh.maddie480.randomstuff.backend.celeste.crontabs.GameBananaAutomatedChecks.enhanceYourWebhook;
import static ovh.maddie480.randomstuff.backend.celeste.crontabs.GameBananaAutomatedChecks.getMaskedEnhancedEmbedLink;

/**
 * A service that follows the Update Checker logs and re-posts them to a Discord channel.
 * It also calls frontend APIs to make it aware of database changes, and reload it as necessary.
 */
public class UpdateCheckerTracker {
    private static final Logger log = LoggerFactory.getLogger(UpdateCheckerTracker.class);

    private boolean currentUpdateIsFull = false;
    private long lastFullCheckTimestamp = 0L;
    private long lastIncrementalCheckTimestamp = 0L;
    private long lastFullCheckDuration = 0L;
    private long lastIncrementalCheckDuration = 0L;
    private final List<Map<String, Object>> latestUpdates = new ArrayList<>();
    private final ModDatabase database;

    public UpdateCheckerTracker(ModDatabase database) {
        this.database = database;

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
        } catch (IOException e) {
            log.error("Could not initialize Update Checker Tracker!", e);
        }
    }

    public void startedSearchingForUpdates(boolean full) {
        currentUpdateIsFull = full;
    }

    public void uploadedModToBananaMirror(String fileName) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":outbox_tray: Uploaded mod zip " + fileName + " to Banana Mirror");
        }
    }

    public void deletedModFromBananaMirror(String fileName) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":wastebasket: Deleted mod zip " + fileName + " from Banana Mirror");
        }
    }

    public void uploadedImageToBananaMirror(String fileName) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":outbox_tray: Uploaded mod image " + fileName + " to Banana Mirror");
        }
    }

    public void deletedImageFromBananaMirror(String fileName) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":wastebasket: Deleted mod image " + fileName + " from Banana Mirror");
        }
    }

    public void uploadedRichPresenceIconToBananaMirror(String fileName) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":outbox_tray: Uploaded Rich Presence icon " + fileName + " to Banana Mirror");
        }
    }

    public void deletedRichPresenceIconFromBananaMirror(String fileName) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":wastebasket: Deleted Rich Presence icon " + fileName + " from Banana Mirror");
        }
    }

    public void savedNewInformationToDatabase(ModRecord mod, FileRecord file) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":white_check_mark: **" + file.modId + "** was updated to version **" + file.modVersion + "** on <t:" + file.createdDate + ">.\n" +
                    ":arrow_right: <" + mod.pageUrl + ">\n" +
                    ":inbox_tray: <" + file.mainUrl + ">");
        }

        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/src-mod-update-notification-ids.json"))) {
            List<String> srcModIds = new JSONArray(new JSONTokener(is)).toList()
                    .stream()
                    .map(Object::toString)
                    .collect(Collectors.toCollection(ArrayList::new));

            if (srcModIds.contains(file.modId)) {
                String message = "**" + file.modId + "** was updated to version **" + file.modVersion + "** on <t:" + file.createdDate + ":f>.\n" +
                        ":arrow_right: <" + mod.pageUrl + ">";
                executeWebhookAsUpdateChecker(SecretConstants.SRC_UPDATE_CHECKER_HOOK, message);
                executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":information_source: Message sent to SRC staff:\n> " + message);
            }

        } catch (IOException e) {
            log.error("Error while fetching SRC mod update notification ID list", e);
            executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":x: Error while fetching SRC mod update notification ID list: " + e);
        }

        addModUpdateToLatestUpdatesList(mod, file, true);
    }

    public void scannedZipContents(String fileUrl, int fileCount) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":mag_right: Found " + pluralize(fileCount, "file", "files") + " in " + fileUrl + ".");
    }

    public void scannedAhornEntities(String fileUrl, int entityCount, int triggerCount, int effectCount) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":mag_right: Ahorn plugins: " + fileUrl + " has " + pluralize(entityCount, "entity", "entities") + ", " +
                pluralize(triggerCount, "trigger", "triggers") + " and " + pluralize(effectCount, "effect", "effects") + ".");
    }

    public void scannedLoennEntities(String fileUrl, int entityCount, int triggerCount, int effectCount) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":mag_right: Lönn plugins: " + fileUrl + " has " + pluralize(entityCount, "entity", "entities") + ", " +
                pluralize(triggerCount, "trigger", "triggers") + " and " + pluralize(effectCount, "effect", "effects") + ".");
    }

    public void scannedModDependencies(String modId, int dependencyCount, int optionalDependencyCount) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":mag_right: **" + modId + "** has "
                + pluralize(dependencyCount, "dependency", "dependencies") + " and "
                + pluralize(optionalDependencyCount, "optional dependency", "optional dependencies") + ".");
    }

    public void modUpdatedIncrementally(String modName, String url) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":repeat: **" + modName + "** " +
                "was updated incrementally.\n:arrow_right: <" + url + ">\n");
    }

    public void modHasNoYamlFile(ModRecord mod, FileRecord file) {
        AtomicReference<String> message = new AtomicReference<>("contains a file that has no `everest.yaml`: " + file.mainUrl);
        AtomicBoolean sendFollowup = new AtomicBoolean(false);

        // is the everest.yaml actually in a subfolder? the update checker's FileDownloader should still have the file for us to check
        Arrays.stream(file.fileListing)
                .filter(entry -> entry.endsWith("/everest.yaml"))
                .findFirst()
                .ifPresent(entry -> {
                    message.set("has a file that contains an `everest.yaml`, but it is located at `"
                            + entry + "` instead of the root of the zip: " + file.mainUrl);
                    sendFollowup.set(true);
                });

        for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
            executeWebhookAsBananaWatch(webhook, ":warning: Mod " + getMaskedEnhancedEmbedLink(mod, file) + " " + message.get());
            if (sendFollowup.get()) {
                executeWebhookAsBananaWatch(webhook, "Make sure to zip the folder properly to avoid this: https://maddie480.ovh/img/zip.png");
            }
        }
    }

    public void zipFileIsNotUTF8(String downloadUrl, String detectedEncoding) {
        executeWebhookAsBananaWatch(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: The zip at <" + downloadUrl + "> could not be read as a ZIP archive with UTF-8 file names. "
                + " It was read with the **" + detectedEncoding + "** encoding instead.");
    }

    public void zipFileIsUnreadable(ModRecord mod, FileRecord file, IOException e) {
        for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
            executeWebhookAsBananaWatch(webhook, ":warning: Mod " + getMaskedEnhancedEmbedLink(mod, file)
                    + " contains a file that could not be read as a ZIP file: " + file.mainUrl);
        }
        postExceptionToWebhook(e);
    }

    public void zipFileIsUnreadableForFileListing(ModRecord mod, FileRecord file, Exception e) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: Mod " + getMaskedEnhancedEmbedLink(mod, file)
                + " contains a file that could not be read as a ZIP file for file listing: " + file.mainUrl);
        postExceptionToWebhook(e);
    }

    public void fileDownloadError(ModRecord mod, FileRecord file, Exception e) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: Mod " + getMaskedEnhancedEmbedLink(mod, file)
                + " could not be downloaded, skipping it: " + file.mainUrl);
        postExceptionToWebhook(e);
    }

    public void moreRecentFileAlreadyExists(ModRecord mod, FileRecord file, FileRecord otherFile) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: File " + file.mainUrl + " was skipped because "
                + otherFile.mainUrl + " is more recent. Both are part of <" + mod.name + ">.");
    }

    public void currentVersionBelongsToAnotherMod(ModRecord mod, FileRecord file, ModRecord otherMod, FileRecord otherFile) {
        for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
            executeWebhookAsBananaWatch(webhook, ":warning: Mod " + getMaskedEnhancedEmbedLink(mod, file)
                    + " contains a file that has the same ID **" + file.modId + "** as mod " + getMaskedEnhancedEmbedLink(otherMod, otherFile) + " .");
        }
    }

    public void modIsExcludedByName(String name) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: A file with mod ID **" + name + "** was skipped because this mod ID is blacklisted.");
    }

    public void yamlFileIsUnreadable(ModRecord mod, FileRecord file, Exception e) {
        for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
            executeWebhookAsBananaWatch(webhook, ":warning: Mod " + getMaskedEnhancedEmbedLink(mod, file)
                    + " contains an `everest.yaml` file that could not be parsed: " + file.mainUrl);
        }
        postExceptionToWebhook(e);
    }

    public void modWasDeletedFromDatabase(ModRecord mod, FileRecord file) {
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            executeWebhookAsUpdateChecker(webhook, ":x: **" + file.modId + "** was deleted from the database.");
        }

        addModUpdateToLatestUpdatesList(mod, file, false);
    }

    public void zipFileWalkthroughError(String modUrl, String fileUrl, Exception e) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: An error occurred when going through file " + fileUrl + ", that is part of " + modUrl + ".");
        postExceptionToWebhook(e);
    }

    public void ahornPluginScanError(String fileUrl, Exception e) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: An error occurred when scanning Ahorn plugins for file " + fileUrl + ".");
        postExceptionToWebhook(e);
    }

    public void loennPluginScanError(String fileUrl, Exception e) {
        executeWebhookAsUpdateChecker(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":warning: An error occurred when scanning Lönn plugins for file " + fileUrl + ".");
        postExceptionToWebhook(e);
    }

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

    private void addModUpdateToLatestUpdatesList(ModRecord mod, FileRecord file, boolean isAddition) {
        latestUpdates.addFirst(ImmutableMap.of(
                "isAddition", isAddition,
                "name", file.modId,
                "version", file.modVersion,
                "url", mod.pageUrl,
                "date", DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(ZonedDateTime.now(ZoneId.of("UTC"))),
                "timestamp", Instant.now().toEpochMilli() / 1000L));

        while (latestUpdates.size() > 5) {
            latestUpdates.remove(5);
        }
    }

    private String pluralize(int number, String singular, String plural) {
        return "**" + number + "** " + (number == 1 ? singular : plural);
    }

    public void endedSearchingForUpdates(long timeTakenMilliseconds) {
        try {
            long postProcessingStart = System.currentTimeMillis();

            mapToTheGoodOldFiles();

            HttpURLConnection conn = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/everest-update-reload?key="
                    + SecretConstants.RELOAD_SHARED_SECRET);
            if (conn.getResponseCode() != 200) {
                throw new IOException("Everest Update Reload API sent non 200 code: " + conn.getResponseCode());
            }

            updateModStructureVerifierMaps();

            conn = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/gamebanana-search-reload?key="
                    + SecretConstants.RELOAD_SHARED_SECRET);
            if (conn.getResponseCode() != 200) {
                throw new IOException("Mod Search Reload API sent non 200 code: " + conn.getResponseCode());
            }

            updateUpdateCheckerStatusInformation(System.currentTimeMillis() - postProcessingStart + timeTakenMilliseconds);

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
    private void executeWebhookAsUpdateChecker(String url, String message) {
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
    private void executeWebhookAsBananaWatch(String url, String message) {
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
    private void executeWebhook(String url, String message, String avatar, String nickname) {
        try {
            if (url.startsWith("https://discord.com/")) {
                Pair<String, List<Map<String, Object>>> enhanced = enhanceYourWebhook(database, message);
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

    private void mapToTheGoodOldFiles() throws IOException {
        { // everest_update.yaml
            TreeMap<String, Object> everestUpdate = new TreeMap<>();
            database.listLatestVersions().stream()
                    .map(e -> Pair.of(
                            e.file().modId, ImmutableMap.of(
                                    "Version", e.file().modVersion,
                                    "LastUpdate", e.file().createdDate,
                                    "GameBananaFileId", e.file().mirrorName, // old
                                    "MirrorName", e.file().mirrorName, // new
                                    "URL", e.file().mainUrl,
                                    "xxHash", Collections.singletonList(e.file().xxHash),
                                    "Size", e.file().size
                            )
                    ))
                    .forEach(e -> everestUpdate.put(e.getKey(), e.getValue()));

            try (OutputStream os = Files.newOutputStream(Paths.get("/shared/celeste/updater/everest-update.yaml"))) {
                YamlUtil.dump(everestUpdate, os);
            }
        }

        { // mod_search_database.yaml
            List<Map<String, Object>> db = database.allMods.stream()
                    .map(m -> {
                        Map<String, Object> contents = new TreeMap<>(ImmutableMap.of(
                                "PageURL", m.pageUrl,
                                "Name", m.name,
                                "Author", m.author.name,
                                "Description", m.summary,
                                "Likes", m.likes,
                                "Views", m.views,
                                "Downloads", m.downloads,
                                "Text", m.description
                        ));
                        contents.put("CreatedDate", m.createdDate);
                        contents.put("ModifiedDate", m.modifiedDate);
                        contents.put("UpdatedDate", m.updatedDate);
                        contents.put("Screenshots", Arrays.stream(m.screenshots)
                                .map(s -> s.mainUrl)
                                .toList());
                        contents.put("MirroredScreenshots", Arrays.stream(m.screenshots)
                                .map(s -> s.mirrorName)
                                .filter(Objects::nonNull)
                                .map(s -> "https://celestemodupdater.0x0a.de/banana-mirror-images/" + s + ".png")
                                .toList());
                        contents.put("Files", Arrays.stream(m.files)
                                .map(f -> ImmutableMap.of(
                                        "Description", f.description,
                                        "HasEverestYaml", f.hasEverestYaml,
                                        "Size", f.size,
                                        "CreatedDate", f.createdDate,
                                        "Downloads", f.downloads,
                                        "URL", f.mainUrl,
                                        "Name", f.name,
                                        "MirrorName", f.mirrorName
                                ))
                                .toList());

                        Map<String, Object> recurseItem = new TreeMap<>();
                        contents.put("Category", recurseItem);
                        CategoryRecord recurse = new CategoryRecord();
                        while (true) {
                            recurseItem.put("ID", recurse.id);
                            recurseItem.put("Name", recurse.name);
                            if (recurse.parent == null) break;

                            Map<String, Object> newRecurseItem = new TreeMap<>();
                            recurseItem.put("Parent", newRecurseItem);

                            recurse = recurse.parent;
                            recurseItem = newRecurseItem;
                        }

                        // jank GameBanana-dependent mapping I need to get rid of
                        CategoryRecord category = m.category, subcategory = m.category;
                        while (category.parent != null && !category.parent.id.endsWith("/Root")) {
                            category = category.parent;
                        }
                        String itemtype = "Mod";
                        if (category.parent != null && category.parent.id.equals("GameBanana_Wip_Root")) {
                            itemtype = "Wip";
                        }
                        if (category.parent != null && category.parent.id.equals("GameBanana_Tool_Root")) {
                            itemtype = "Tool";
                        }

                        contents.putAll(ImmutableMap.of(
                                "GameBananaType", itemtype,
                                "CategoryId", category.id.substring(category.id.lastIndexOf("/") + 1),
                                "CategoryName", category.name
                        ));
                        if (!category.equals(subcategory)) {
                            contents.putAll(ImmutableMap.of(
                                    "SubcategoryId", subcategory.id.substring(category.id.lastIndexOf("/") + 1),
                                    "SubcategoryName", subcategory.name
                            ));
                        }
                        return contents;
                    })
                    .toList();

            try (OutputStream os = Files.newOutputStream(Paths.get("/shared/celeste/updater/mod-search-database.yaml"))) {
                YamlUtil.dump(db, os);
            }
        }

        { // mod_dependency_graph.yaml
            Map<String, Object> graph = new TreeMap<>();
            database.listLatestVersions().stream()
                    .map(m -> Pair.of(m.file().modId, ImmutableMap.of(
                            "URL", m.file().mainUrl,
                            "MirrorName", m.file().mirrorName,
                            "Dependencies", Arrays.stream(m.file().dependencies)
                                    .map(d -> ImmutableMap.of(
                                            "Name", d.name,
                                            "Version", d.version
                                    ))
                                    .toList(),
                            "OptionalDependencies", Arrays.stream(m.file().optionalDependencies)
                                    .map(d -> ImmutableMap.of(
                                            "Name", d.name,
                                            "Version", d.version
                                    ))
                                    .toList()
                    )))
                    .forEach(p -> graph.put(p.getKey(), p.getValue()));

            try (OutputStream os = Files.newOutputStream(Paths.get("/shared/celeste/updater/mod-dependency-graph.yaml"))) {
                YamlUtil.dump(graph, os);
            }
        }

        { // mod_files_database.zip (sort of)
            Path p = Paths.get("/shared/celeste/updater/mod-files-database.zip");
            try (ZipOutputStream zs = new ZipOutputStream(Files.newOutputStream(p))) {
                zs.setLevel(Deflater.BEST_COMPRESSION);

                for (ModRecord mod : database.allMods) {
                    for (FileRecord file : mod.files) {
                        ZipEntry zipEntry = new ZipEntry(mod.id + "/" + file.id + ".yaml");
                        zs.putNextEntry(zipEntry);
                        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                            YamlUtil.dump(file.fileListing, os);
                            zs.write(os.toByteArray());
                        }
                        zs.closeEntry();
                    }
                }
            }
        }
    }

    /**
     * Updates the maps used by the Mod Structure Verifier to see in which mod each asset is.
     * Called on startup and each time everest_update.yaml is modified.
     */
    public void updateModStructureVerifierMaps() throws IOException {
        log.info("Updating Mod Structure Verifier entity maps...");

        Map<String, String> assets = getElementMap(file -> Arrays.stream(file.fileListing)
                .filter(e -> e.toLowerCase(Locale.ROOT).startsWith("graphics/atlases/gameplay/bgs/")
                        || e.toLowerCase(Locale.ROOT).startsWith("graphics/atlases/gameplay/decals/"))
                .collect(Collectors.toList()));

        Map<String, String> entities = getEntityMap(e -> e.entities);
        Map<String, String> triggers = getEntityMap(e -> e.triggers);
        Map<String, String> effects = getEntityMap(e -> e.effects);

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
                extraYamls.put((String) contents.getFirst().get("Name"), (String) contents.getFirst().get("Version"));
            }
        }

        try (BufferedWriter bw = Files.newBufferedWriter(Paths.get("/shared/celeste/everest-yamls-from-github.json"))) {
            new JSONObject(extraYamls).write(bw);
        }
        log.info("Updated everest.yamls from GitHub with: {}", extraYamls);
    }

    private Map<String, String> getEntityMap(Function<MapEditorRecord, String[]> getter) {
        Map<String, String> ahornEntities = getElementMap(
                file -> Arrays.asList(getter.apply(file.ahornEntities)));
        Map<String, String> loennEntities = getElementMap(
                file -> Arrays.asList(getter.apply(file.loennEntities)));

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

    private Map<String, String> getElementMap(Function<FileRecord, List<String>> reader) {
        Map<String, String> elementMap = new HashMap<>();
        Set<String> duplicateElements = new HashSet<>();

        // go through the contents of each mod in the database, to list out its assets.
        for (ModDatabase.ModLatestVersion record : database.listLatestVersions()) {
            for (String element : reader.apply(record.file())) {
                element = element.toLowerCase(Locale.ROOT);
                if (!duplicateElements.contains(element)) {
                    if (elementMap.containsKey(element)) {
                        // we found an element in multiple mods! do not include it, since it is ambiguous.
                        duplicateElements.add(element);
                        elementMap.remove(element);
                    } else {
                        elementMap.put(element, record.file().modId);
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
