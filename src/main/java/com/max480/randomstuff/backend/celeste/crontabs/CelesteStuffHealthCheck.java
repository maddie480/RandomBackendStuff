package com.max480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import com.max480.everest.updatechecker.YamlUtil;
import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import com.max480.randomstuff.backend.utils.HttpPostMultipart;
import com.max480.randomstuff.backend.utils.WebhookExecutor;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A bunch of Celeste health check routines.
 * Those are run periodically, and if one throws an exception, an alert is sent to Maddie.
 */
public class CelesteStuffHealthCheck {
    private static final Logger log = LoggerFactory.getLogger(CelesteStuffHealthCheck.class);

    /**
     * Checks that every Everest branch has a version and that we can download it.
     * Also sends out a notification to SRC staff if a new stable Everest hits.
     * Ran hourly with daily = false, and daily with daily = true.
     */

    public static void checkEverestExists(boolean daily) throws IOException {
        int latestStable = -1;
        int latestBeta = -1;
        int latestDev = -1;

        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/everest-versions.json"))) {
            JSONArray versionList = new JSONArray(IOUtils.toString(is, UTF_8));

            for (Object version : versionList) {
                JSONObject versionObj = (JSONObject) version;

                switch (versionObj.getString("branch")) {
                    case "dev" -> latestDev = Math.max(latestDev, versionObj.getInt("version"));
                    case "beta" -> latestBeta = Math.max(latestBeta, versionObj.getInt("version"));
                    case "stable" -> latestStable = Math.max(latestStable, versionObj.getInt("version"));
                }
            }
        }

        if (latestStable == -1) {
            throw new IOException("There is no Everest stable version :a:");
        }
        log.debug("Latest Everest stable version: " + latestStable);
        if (latestBeta == -1) {
            throw new IOException("There is no beta Everest version :a:");
        }
        log.debug("Latest Everest beta version: " + latestBeta);
        if (latestDev == -1) {
            throw new IOException("There is no Everest dev version :a:");
        }
        log.debug("Latest Everest dev version: " + latestDev);

        // check the last version we sent an SRC notification for.
        int savedLatestEverest = Integer.parseInt(FileUtils.readFileToString(new File("latest_everest.txt"), UTF_8));

        if (savedLatestEverest < latestStable) {
            // a new stable version of Everest hit! send a notification to SRC staff.
            WebhookExecutor.executeWebhook(SecretConstants.SRC_UPDATE_CHECKER_HOOK,
                    "https://cdn.discordapp.com/attachments/445236692136230943/878508600509726730/unknown.png",
                    "Everest Update Checker",
                    "**A new Everest stable was just released!**\nThe latest stable version is now **" + latestStable + "**.");
            WebhookExecutor.executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK,
                    "https://cdn.discordapp.com/attachments/445236692136230943/878508600509726730/unknown.png",
                    "Everest Update Checker",
                    ":information_source: Message sent to SRC staff:\n> **A new Everest stable was just released!**\n> The latest stable version is now **" + latestStable + "**.");

            // and save the fact that we notified about this version.
            FileUtils.writeStringToFile(new File("latest_everest.txt"), Integer.toString(latestStable), UTF_8);
        }

        // save the latest versions to Cloud Storage for the everest.yaml validator to use
        Files.writeString(Paths.get("/shared/celeste/latest-everest-versions.json"), new JSONObject(ImmutableMap.of(
                "stable", latestStable,
                "beta", latestBeta,
                "dev", latestDev
        )).toString());

        if (daily) {
            checkEverestVersionExists(latestStable);
            checkEverestVersionExists(latestBeta);
            checkEverestVersionExists(latestDev);
        }
    }

    /**
     * Checks that every Olympus branch has a version and that we can download it for all 3 supported operating systems.
     */
    public static void checkOlympusExists(boolean daily) throws IOException {
        JSONObject object = new JSONObject(ConnectionUtils.toStringWithTimeout("https://dev.azure.com/EverestAPI/Olympus/_apis/build/builds", UTF_8));
        JSONArray versionList = object.getJSONArray("value");
        int latestStable = -1;
        int latestMain = -1;
        int latestWindowsInit = -1;
        for (Object version : versionList) {
            JSONObject versionObj = (JSONObject) version;
            String reason = versionObj.getString("reason");
            if (Arrays.asList("manual", "individualCI").contains(reason)
                    && "completed".equals(versionObj.getString("status"))
                    && "succeeded".equals(versionObj.getString("result"))) {

                switch (versionObj.getString("sourceBranch")) {
                    case "refs/heads/main" -> latestMain = Math.max(latestMain, versionObj.getInt("id"));
                    case "refs/heads/stable" -> latestStable = Math.max(latestStable, versionObj.getInt("id"));
                    case "refs/heads/windows-init" ->
                            latestWindowsInit = Math.max(latestWindowsInit, versionObj.getInt("id"));
                }
            }
        }

        if (latestStable == -1) {
            throw new IOException("There is no Olympus stable version :a:");
        }
        log.debug("Latest Olympus stable version: " + latestStable);
        if (latestMain == -1) {
            throw new IOException("There is no Olympus dev version :a:");
        }
        log.debug("Latest Olympus dev version: " + latestMain);
        if (latestWindowsInit == -1) {
            throw new IOException("There is no Olympus windows-init version :a:");
        }
        log.debug("Latest Olympus windows-init version: " + latestWindowsInit);

        if (daily) {
            checkOlympusVersionExists(latestStable, "windows.main");
            checkOlympusVersionExists(latestStable, "macos.main");
            checkOlympusVersionExists(latestStable, "linux.main");
            checkOlympusVersionExists(latestMain, "windows.main");
            checkOlympusVersionExists(latestMain, "macos.main");
            checkOlympusVersionExists(latestMain, "linux.main");
            checkOlympusVersionExists(latestWindowsInit, "windows.main");
            checkOlympusVersionExists(latestWindowsInit, "macos.main");
            checkOlympusVersionExists(latestWindowsInit, "linux.main");
        }
    }

    /**
     * Checks that all artifacts of an Everest version are downloadable.
     */
    private static void checkEverestVersionExists(int versionNumber) throws IOException {
        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/everest-versions.json"))) {
            JSONArray versionList = new JSONArray(IOUtils.toString(is, UTF_8));

            for (Object version : versionList) {
                JSONObject versionObj = (JSONObject) version;

                if (versionObj.getInt("version") == versionNumber) {
                    checkExists(versionObj.getString("mainDownload"));
                    checkExists(versionObj.getString("olympusMetaDownload"));
                    checkExists(versionObj.getString("olympusBuildDownload"));
                }
            }
        }
    }

    /**
     * Checks that an Olympus artifact is downloadable.
     */
    private static void checkOlympusVersionExists(int version, String artifactName) throws IOException {
        log.debug("Downloading Olympus version {}, artifact {}...", version, artifactName);

        checkExists("https://dev.azure.com/EverestAPI/Olympus/_apis/build/builds/" + version + "/artifacts?artifactName=" + artifactName + "&api-version=5.0&%24format=zip");
    }

    /**
     * Checks that a link is downloadable.
     */
    private static void checkExists(String link) throws IOException {
        log.debug("Trying to download {}...", link);

        long size = 0;
        byte[] buffer = new byte[4096];
        try (InputStream is = ConnectionUtils.openStreamWithTimeout(link)) {
            while (true) {
                int read = is.read(buffer);
                if (read == -1) break;
                size += read;
            }
        }

        int minSize = 1_000_000;
        if (link.contains("meta")) {
            minSize = 100;
        }

        log.debug("Total size: {}, expecting at least {}", size, minSize);
        if (size < minSize) {
            throw new IOException(link + " is too small (" + size + "), that's suspicious");
        }
    }

    /**
     * Checks that the custom entity catalog was updated just now.
     * Ran daily, just after {@link CustomEntityCatalogGenerator}.
     */
    public static void checkCustomEntityCatalog() throws IOException {
        // the catalog should have just be refreshed, and the date is UTC on the frontend
        String expectedRefreshDate = ZonedDateTime.now().withZoneSameInstant(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH));

        log.debug("Loading custom entity catalog... (expecting date: {})", expectedRefreshDate);
        if (!ConnectionUtils.toStringWithTimeout("https://maddie480.ovh/celeste/custom-entity-catalog", UTF_8)
                .contains(expectedRefreshDate)) {

            throw new IOException("The latest refresh date of the Custom Entity Catalog is not \"" + expectedRefreshDate + "\" :a:");
        }

        expectedRefreshDate = ZonedDateTime.now().withZoneSameInstant(ZoneId.of("Europe/Paris"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH));

        log.debug("Loading custom entity catalog JSON... (expecting date: {})", expectedRefreshDate);
        if (!ConnectionUtils.toStringWithTimeout("https://maddie480.ovh/celeste/custom-entity-catalog.json", UTF_8)
                .contains(expectedRefreshDate)) {

            throw new IOException("The latest refresh date of the Custom Entity Catalog JSON is not \"" + expectedRefreshDate + "\" :a:");
        }
    }

    /**
     * Checks that the list of files on Banana Mirror is the exact same as the files listed in everest_update.yaml
     * and mod_search_database.yaml (so there is no "desync" between both, and all files referenced actually exist).
     * Ran daily.
     */
    public static void checkBananaMirrorDatabaseMatch() throws IOException {
        log.debug("Checking Banana Mirror contents...");

        // === zips referenced in everest_update.yaml should be present at https://celestemodupdater.0x0a.de/banana-mirror/
        List<String> bananaMirror = Jsoup.connect("https://celestemodupdater.0x0a.de/banana-mirror/").get()
                .select("td.indexcolname a")
                .stream()
                .map(a -> "https://celestemodupdater.0x0a.de/banana-mirror/" + a.attr("href"))
                .filter(item -> !item.equals("https://celestemodupdater.0x0a.de/banana-mirror//"))
                .sorted()
                .toList();

        List<String> everestUpdate;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/everest_update.yaml")) {
            Map<String, Map<String, Object>> mapped = YamlUtil.load(is);
            everestUpdate = mapped.values()
                    .stream()
                    .map(item -> item.get(com.max480.everest.updatechecker.Main.serverConfig.mainServerIsMirror ? "URL" : "MirrorURL").toString())
                    .sorted()
                    .collect(Collectors.toList());
        }

        if (!bananaMirror.equals(everestUpdate)) {
            throw new IOException("Banana Mirror contents don't match the mod updater database");
        }

        // === images referenced in mod_search_database.yaml should be present at https://celestemodupdater.0x0a.de/banana-mirror-images/
        List<String> bananaMirrorImages = Jsoup.connect("https://celestemodupdater.0x0a.de/banana-mirror-images/").get()
                .select("td.indexcolname a")
                .stream()
                .map(a -> "https://celestemodupdater.0x0a.de/banana-mirror-images/" + a.attr("href"))
                .filter(item -> !item.equals("https://celestemodupdater.0x0a.de/banana-mirror-images//"))
                .sorted()
                .toList();

        List<String> modSearchDatabase;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/mod_search_database.yaml")) {
            List<Map<String, Object>> mapped = YamlUtil.load(is);
            modSearchDatabase = mapped.stream()
                    .map(item -> (List<String>) item.get("MirroredScreenshots"))
                    .flatMap(List::stream)
                    .sorted()
                    .collect(Collectors.toList());
        }

        if (!bananaMirrorImages.equals(modSearchDatabase)) {
            throw new IOException("Banana Mirror Images contents don't match the mod updater database");
        }

        // === Rich Presence icons we saved locally should be present at https://celestemodupdater.0x0a.de/rich-presence-icons/
        List<String> richPresenceIcons = Jsoup.connect("https://celestemodupdater.0x0a.de/rich-presence-icons/").get()
                .select("td.indexcolname a")
                .stream()
                .map(a -> "https://celestemodupdater.0x0a.de/rich-presence-icons/" + a.attr("href"))
                .filter(item -> !item.equals("https://celestemodupdater.0x0a.de/rich-presence-icons//") && !item.equals("https://celestemodupdater.0x0a.de/rich-presence-icons/list.json"))
                .sorted()
                .toList();

        List<String> richPresenceIconsLocal;
        try (InputStream is = Files.newInputStream(Paths.get("banana_mirror_rich_presence_icons.yaml"))) {
            Map<String, Map<String, List<String>>> mapped = YamlUtil.load(is);

            for (Map.Entry<String, List<String>> hashToFiles : mapped.get("HashesToFiles").entrySet()) {
                for (String file : hashToFiles.getValue()) {
                    if (!mapped.get("FilesToHashes").get(file).contains(hashToFiles.getKey())) {
                        throw new IOException("Backwards link for " + hashToFiles.getKey() + " => " + file + " does not exist!");
                    }
                }
            }
            for (Map.Entry<String, List<String>> fileToHashes : mapped.get("FilesToHashes").entrySet()) {
                for (String hash : fileToHashes.getValue()) {
                    if (!mapped.get("HashesToFiles").get(hash).contains(fileToHashes.getKey())) {
                        throw new IOException("Backwards link for " + fileToHashes.getKey() + " => " + hash + " does not exist!");
                    }
                }
            }

            richPresenceIconsLocal = mapped.get("HashesToFiles").keySet().stream()
                    .map(a -> "https://celestemodupdater.0x0a.de/rich-presence-icons/" + a + ".png")
                    .sorted()
                    .collect(Collectors.toList());
        }

        // and they should also match the list present at list.json
        JSONArray richPresenceIconListJson;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://celestemodupdater.0x0a.de/rich-presence-icons/list.json")) {
            richPresenceIconListJson = new JSONArray(IOUtils.toString(is, UTF_8));
        }
        List<String> richPresenceIconsList = new ArrayList<>();
        for (Object o : richPresenceIconListJson) {
            richPresenceIconsList.add("https://celestemodupdater.0x0a.de/rich-presence-icons/" + o + ".png");
        }
        richPresenceIconsList.sort(Comparator.naturalOrder());

        if (!richPresenceIcons.equals(richPresenceIconsLocal) || !richPresenceIcons.equals(richPresenceIconsList)) {
            throw new IOException("Banana Mirror Rich Presence Icons contents don't match the ones we have saved locally");
        }
    }

    /**
     * Checks that GameBanana categories didn't change overnight (because that requires changes in the updater).
     * YES means the category accepts files, NO means it doesn't.
     * Run daily.
     */
    public static void checkGameBananaCategories() throws IOException {
        Process gamebananaChecker = new ProcessBuilder("/bin/bash", "-c", "/app/static/check-gb.sh").start();
        String result = IOUtils.toString(gamebananaChecker.getInputStream(), StandardCharsets.UTF_8);
        if (!result.equals("""
                App - NO
                Article - NO
                Blog - NO
                Club - NO
                Concept - NO
                Contest - NO
                Event - NO
                Idea - NO
                Jam - NO
                Mod - YES
                Model - YES
                News - NO
                Poll - NO
                PositionAvailable - NO
                Project - NO
                Question - NO
                Request - NO
                Review - NO
                Script - NO
                Sound - YES
                Spray - YES
                Studio - NO
                Thread - NO
                Tool - YES
                Tutorial - NO
                Ware - NO
                Wiki - NO
                Wip - YES
                """)) {

            log.warn("GB categories changed!");
            throw new IOException("GB categories changed! (or the script failed...)");
        } else {
            log.debug("GB categories didn't change");
        }
        log.debug("GB categories are:\n{}", result);
    }

    /**
     * Checks that APIs for Olympus (like search and sorted list) work.
     * Run hourly.
     */
    public static void checkOlympusAPIs() throws IOException {
        // search
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/gamebanana-search?q=EXTENDED+VARIANT+MODE"), UTF_8)
                .contains("\"Name\":\"Extended Variant Mode\"")) {

            throw new IOException("Extended Variant Mode search test failed");
        }

        // sorted list
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/gamebanana-list?sort=downloads&category=6800&page=1"), UTF_8)
                .contains("\"Name\":\"The 2020 Celeste Spring Community Collab\"")) {

            throw new IOException("Sorted list API test failed");
        }

        // categories list
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/gamebanana-categories"), UTF_8)
                .contains("""
                        - itemtype: Mod
                          categoryid: 6800
                          formatted: Maps
                          count:\s""")) {

            throw new IOException("Categories list API failed");
        }

        // featured mods list
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/gamebanana-featured"), UTF_8)
                .contains("\"Name\":\"The 2020 Celeste Spring Community Collab\"")) {

            throw new IOException("Featured mods list API failed");
        }

        // check that the mirror is alive by downloading a GhostNet screenshot
        if (!DigestUtils.sha256Hex(ConnectionUtils.toByteArrayWithTimeout("https://celestemodupdater.0x0a.de/banana-mirror-images/img_ss_mods_5b05ac2b4b6da.png"))
                .equals("32887093611c0338d020b23496d33bdc10838185ab2bd31fa0b903da5b9ab7e7")) {

            throw new IOException("Download from mirror test failed");
        }

        // Everest versions: check that latest dev is listed
        int latestDev;
        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/latest-everest-versions.json"))) {
            latestDev = new JSONObject(IOUtils.toString(is, UTF_8)).getInt("dev");
        }
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/everest-versions"), UTF_8)
                .contains("\"version\":" + latestDev)) {

            throw new IOException("Everest versions test failed");
        }
    }

    /**
     * Checks other GameBanana-related APIs that are less critical, like deprecated versions.
     * Run daily.
     */
    public static void checkSmallerGameBananaAPIs() throws IOException {
        // "random Celeste map" button
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/random-map");
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        if (!IOUtils.toString(connection.getInputStream(), UTF_8).contains("Celeste")) {
            throw new IOException("Didn't get redirected to a random Celeste map!");
        }
        connection.disconnect();

        // GameBanana info API (used by file searcher only)
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/gamebanana-info?itemtype=Mod&itemid=53650"), UTF_8)
                .contains("\"Name\":\"Extended Variant Mode\"")) {

            throw new IOException("Extended Variant Mode info check failed");
        }

        // deprecated GameBanana categories API
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/gamebanana-categories"), UTF_8)
                .contains("""
                        - itemtype: Tool
                          formatted: Tools
                          count:\s""")) {

            throw new IOException("Categories list API v1 failed");
        }

        // mod search database API
        final String modSearchDatabase = ConnectionUtils.toStringWithTimeout("https://maddie480.ovh/celeste/mod_search_database.yaml", UTF_8);
        if (!modSearchDatabase.contains("Name: The 2020 Celeste Spring Community Collab")) {
            throw new IOException("mod_search_database.yaml check failed");
        }

        // mod files database zip
        try (ZipInputStream zis = new ZipInputStream(ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/mod_files_database.zip"))) {
            Set<String> expectedFiles = new HashSet<>(Arrays.asList(
                    "ahorn_vanilla.yaml", "loenn_vanilla.yaml", "list.yaml", "Mod/150813/info.yaml", "Mod/150813/484937.yaml", "Mod/53641/ahorn_506448.yaml"
            ));

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                expectedFiles.remove(entry.getName());
            }

            if (!expectedFiles.isEmpty()) {
                throw new IOException("The following files are missing from mod files database: " + String.join(", ", expectedFiles));
            }
        }

        // mod_dependency_graph.yaml
        final String modDependencyGraph = ConnectionUtils.toStringWithTimeout("https://maddie480.ovh/celeste/mod_dependency_graph.yaml", UTF_8);
        if (!modDependencyGraph.contains("SpringCollab2020Audio:")
                || !modDependencyGraph.contains("URL: https://gamebanana.com/mmdl/484937")
                || !modDependencyGraph.contains("- Name: SpringCollab2020Audio")
                || !modDependencyGraph.contains("  Version: 1.0.0")) {

            throw new IOException("mod_dependency_graph.yaml check failed");
        }

        // Update Checker status, widget version
        final String updateCheckerStatus = ConnectionUtils.toStringWithTimeout("https://maddie480.ovh/celeste/update-checker-status?widget=true", UTF_8);
        if (!updateCheckerStatus.contains("<span class=\"GreenColor\">Up</span>")) {
            throw new IOException("Update checker is not OK according to status widget!");
        }

        // GameBanana "JSON to RSS feed" API
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/gamebanana/rss-feed?_aCategoryRowIds[]=5081&_sOrderBy=_tsDateAdded,ASC&_nPerpage=10"), UTF_8)
                .contains("<title>Outcast Outback Helper</title>")) {

            throw new IOException("RSS feed by category API failed");
        }
    }

    /**
     * Checks that the backend is still serving Update Checker files, and that the Update Checker is still alive.
     * If the Update Checker runs into an error, it will already be posted by {@link UpdateCheckerTracker}.
     * Run hourly.
     */
    public static void updateCheckerHealthCheck() throws IOException {
        log.debug("Checking Update Checker");

        // everest_update.yaml responds
        final String everestUpdate = ConnectionUtils.toStringWithTimeout("https://maddie480.ovh/celeste/everest_update.yaml", UTF_8);
        if (!everestUpdate.contains("SpringCollab2020Audio:") || !everestUpdate.contains("URL: https://gamebanana.com/mmdl/484937")) {
            throw new IOException("everest_update.yaml check failed");
        }

        // it matches what we have on disk
        final String fileOnDisk = FileUtils.readFileToString(new File("uploads/everestupdate.yaml"), UTF_8);
        if (!fileOnDisk.equals(everestUpdate)) {
            throw new IOException("everest_update.yaml on disk and on Cloud Storage don't match!");
        }

        // the status page says everything is fine
        final String updateCheckerStatus = ConnectionUtils.toStringWithTimeout("https://maddie480.ovh/celeste/update-checker-status", UTF_8);
        if (!updateCheckerStatus.contains("The update checker is up and running!")) {
            throw new IOException("Update checker is not OK according to status page!");
        }

        // and the update checker server is not frozen
        if (UpdateCheckerTracker.lastEndOfCheckForUpdates.isBefore(ZonedDateTime.now().minusMinutes(30))) {
            throw new IOException("Update Checker did not end an update successfully since " +
                    UpdateCheckerTracker.lastEndOfCheckForUpdates.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG)));
        }
    }

    /**
     * Checks that the everest.yaml validator is still up and considers the Winter Collab everest.yaml valid.
     * Run daily.
     */
    public static void everestYamlValidatorHealthCheck() throws IOException {
        // write the Winter Collab everest.yaml to a file.
        FileUtils.writeStringToFile(new File("/tmp/everest.yaml"),
                """
                        - Name: WinterCollab2021
                          Version: 1.3.4
                          DLL: "Code/bin/Debug/WinterCollabHelper.dll"
                          Dependencies:
                            - Name: Everest
                              Version: 1.2707.0
                            - Name: AdventureHelper
                              Version: 1.5.1
                            - Name: Anonhelper
                              Version: 1.0.4
                            - Name: BrokemiaHelper
                              Version: 1.2.3
                            - Name: CherryHelper
                              Version: 1.6.7
                            - Name: ColoredLights
                              Version: 1.1.1
                            - Name: CollabUtils2
                              Version: 1.3.11
                            - Name: CommunalHelper
                              Version: 1.7.0
                            - Name: ContortHelper
                              Version: 1.5.4
                            - Name: CrystallineHelper
                              Version: 1.10.0
                            - Name: DJMapHelper
                              Version: 1.8.27
                            - Name: ExtendedVariantMode
                              Version: 0.19.11
                            - Name: FactoryHelper
                              Version: 1.2.4
                            - Name: FancyTileEntities
                              Version: 1.4.0
                            - Name: FemtoHelper
                              Version: 1.1.1
                            - Name: FlaglinesAndSuch
                              Version: 1.4.6
                            - Name: FrostHelper
                              Version: 1.22.4
                            - Name: HonlyHelper
                              Version: 1.3.2
                            - Name: JackalHelper
                              Version: 1.3.5
                            - Name: LunaticHelper
                              Version: 1.1.1
                            - Name: MaxHelpingHand
                              Version: 1.13.3
                            - Name: memorialHelper
                              Version: 1.0.3
                            - Name: MoreDasheline
                              Version: 1.6.3
                            - Name: OutbackHelper
                              Version: 1.4.0
                            - Name: PandorasBox
                              Version: 1.0.29
                            - Name: Sardine7
                              Version: 1.0.0
                            - Name: ShroomHelper
                              Version: 1.0.1
                            - Name: TwigHelper
                              Version: 1.1.5
                            - Name: VivHelper
                              Version: 1.4.1
                            - Name: VortexHelper
                              Version: 1.1.0
                            - Name: WinterCollab2021Audio
                              Version: 1.3.0""", UTF_8);

        { // HTML version
            // build a request to everest.yaml validator
            HttpPostMultipart submit = new HttpPostMultipart("https://maddie480.ovh/celeste/everest-yaml-validator", "UTF-8", new HashMap<>());
            submit.addFilePart("file", new File("/tmp/everest.yaml"));
            submit.addFormField("outputFormat", "html");
            HttpURLConnection result = submit.finish();

            // read the response from everest.yaml validator and check the Winter Collab is deemed valid.
            String resultBody = IOUtils.toString(result.getInputStream(), StandardCharsets.UTF_8);
            if (!resultBody.contains("Your everest.yaml file seems valid!")
                    || !resultBody.contains("WinterCollab2021Audio") || !resultBody.contains("VivHelper") || !resultBody.contains("1.4.1")) {
                throw new IOException("everest.yaml validator gave unexpected output for Winter Collab yaml file");
            }
        }

        { // JSON version
            // build a request to everest.yaml validator
            HttpPostMultipart submit = new HttpPostMultipart("https://maddie480.ovh/celeste/everest-yaml-validator", "UTF-8", new HashMap<>());
            submit.addFilePart("file", new File("/tmp/everest.yaml"));
            submit.addFormField("outputFormat", "json");
            HttpURLConnection result = submit.finish();

            // read the response from everest.yaml validator and check the Winter Collab is deemed valid.
            JSONObject resultBody = new JSONObject(IOUtils.toString(result.getInputStream(), StandardCharsets.UTF_8));
            boolean found = false;
            for (Object dependency : resultBody.getJSONArray("modInfo").getJSONObject(0).getJSONArray("Dependencies")) {
                JSONObject dep = (JSONObject) dependency;
                if (dep.getString("Name").equals("VivHelper") && dep.getString("Version").equals("1.4.1")) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IOException("everest.yaml validator gave unexpected output for Winter Collab yaml file");
            }
        }

        // delete the temp file
        new File("/tmp/everest.yaml").delete();
    }

    /**
     * Checks that the font generator works by sending it the Collab Utils 2 Japanese translation, using libgdx.
     * Run daily.
     */
    public static void checkFontGeneratorLibGdx() throws IOException {
        log.debug("Downloading sample dialog file...");

        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://raw.githubusercontent.com/EverestAPI/CelesteCollabUtils2/master/Dialog/Japanese.txt");
             OutputStream os = new FileOutputStream("/tmp/Japanese.txt")) {

            IOUtils.copy(is, os);
        }

        log.debug("Sending libgdx request to font generator...");

        // build a request to font generator
        HttpPostMultipart submit = new HttpPostMultipart("https://maddie480.ovh/celeste/font-generator", "UTF-8", new HashMap<>());
        submit.addFormField("fontFileName", "collabutils2_japanese_healthcheck");
        submit.addFormField("font", "japanese");
        submit.addFormField("method", "libgdx");
        submit.addFilePart("dialogFile", new File("/tmp/Japanese.txt"));
        HttpURLConnection result = submit.finish();

        // delete the temp file
        new File("/tmp/Japanese.txt").delete();

        // read the response as a zip file
        try (ZipInputStream zip = new ZipInputStream(result.getInputStream())) {
            if (!zip.getNextEntry().getName().equals("japanese.fnt")
                    || !zip.getNextEntry().getName().equals("collabutils2_japanese_healthcheck.png")
                    || zip.getNextEntry() != null) {

                throw new IOException("Font generator ZIP had unexpected contents!");
            }
        }
    }

    /**
     * Checks that the font generator works by sending it the Collab Utils 2 Japanese translation, using BMFont.
     * Run daily.
     */
    public static void checkFontGeneratorBMFont() throws IOException {
        log.debug("Downloading sample dialog file...");

        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://raw.githubusercontent.com/EverestAPI/CelesteCollabUtils2/master/Dialog/Japanese.txt");
             OutputStream os = new FileOutputStream("/tmp/Japanese.txt")) {

            IOUtils.copy(is, os);
        }

        log.debug("Sending request to BMFont font generator...");

        // build a request to font generator
        HttpPostMultipart submit = new HttpPostMultipart("https://maddie480.ovh/celeste/font-generator", "UTF-8", new HashMap<>());
        submit.addFormField("font", "japanese");
        submit.addFormField("method", "bmfont");
        submit.addFilePart("dialogFile", new File("/tmp/Japanese.txt"));
        HttpURLConnection result = submit.finish();

        // delete the temp file
        new File("/tmp/Japanese.txt").delete();

        if (result.getResponseCode() != 200) {
            throw new IOException("Font generator responded with HTTP code " + result.getResponseCode());
        }

        String url = result.getURL().toString();
        log.debug("Font generator task tracker URL: {}, checking result in 30 seconds", url);

        try {
            Thread.sleep(30000);
        } catch (InterruptedException e) {
            throw new IOException(e);
        }

        log.debug("Loading result page: {}", url);
        try (InputStream is = ConnectionUtils.openStreamWithTimeout(url)) {
            String response = IOUtils.toString(is, UTF_8);
            if (!response.contains("Here is the font you need to place")) {
                throw new IOException("Font generator result page does not indicate success!");
            }
        }

        // read the response as a zip file
        log.debug("Loading generated font file: {}/download/0", url);
        try (ZipInputStream zip = new ZipInputStream(ConnectionUtils.openStreamWithTimeout(url + "/download/0"))) {
            for (int i = 0; i < 2; i++) {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null || (!entry.getName().equals("japanese.fnt") && !entry.getName().startsWith("japanese_generated_"))) {
                    throw new IOException("Font generator ZIP had unexpected contents!");
                }
            }

            if (zip.getNextEntry() != null) {
                throw new IOException("Font generator ZIP had unexpected contents!");
            }
        }
    }

    /**
     * Checks that the mod structure verifier can scan Tornado Valley successfully.
     * Run daily.
     */
    public static void checkModStructureVerifier() throws IOException {
        log.debug("Downloading Tornado Valley...");

        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://celestemodupdater.0x0a.de/banana-mirror/399127.zip");
             OutputStream os = new FileOutputStream("/tmp/tornado.zip")) {

            IOUtils.copy(is, os);
        }

        log.debug("Sending Tornado Valley to mod structure verifier...");

        // build a request to mod structure verifier
        HttpPostMultipart submit = new HttpPostMultipart("https://maddie480.ovh/celeste/mod-structure-verifier", "UTF-8", new HashMap<>());
        submit.addFilePart("zipFile", new File("/tmp/tornado.zip"));
        HttpURLConnection result = submit.finish();

        // delete the temp file
        new File("/tmp/tornado.zip").delete();

        String url;
        try (InputStream is = result.getInputStream()) {
            // the response is a URL relative to maddie480.ovh.
            url = "https://maddie480.ovh" + IOUtils.toString(is, UTF_8);
        }
        log.debug("Mod structure verifier task tracker URL: {}, checking result in 15 seconds", url);

        try {
            Thread.sleep(15000);
        } catch (InterruptedException e) {
            throw new IOException(e);
        }

        log.debug("Loading result page: {}", url);
        try (InputStream is = ConnectionUtils.openStreamWithTimeout(url)) {
            String response = IOUtils.toString(is, UTF_8);
            if (!response.contains("No issue was found with your zip!")) {
                throw new IOException("Mod structure verifier result page does not indicate success!");
            }
        }
    }

    /**
     * Checks that the map tree viewer can decode the Tornado Valley bin.
     * Run daily.
     */
    public static void checkMapTreeViewer() throws IOException {
        log.debug("Checking bin-to-json API...");


        try (ZipInputStream zis = new ZipInputStream(ConnectionUtils.openStreamWithTimeout("https://celestemodupdater.0x0a.de/banana-mirror/399127.zip"))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("Maps/Meowsmith/1/TornadoValleyConcept.bin".equals(entry.getName())) {
                    log.debug("Found map bin, sending...");

                    HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/bin-to-json");
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);

                    try (OutputStream os = connection.getOutputStream()) {
                        log.debug("Transferred {} bytes", IOUtils.copy(zis, os));
                    }

                    try (InputStream response = connection.getInputStream()) {
                        if (!IOUtils.toString(response, UTF_8).contains("\"texture\":\"9-core/fossil_b.png\"")) {
                            throw new IOException("bin-to-json response didn't have the expected content!");
                        }
                        return;
                    }
                }
            }

            throw new IOException("The map bin to use as a test was not found!");
        }
    }

    /**
     * Checks that the file searcher can find Tornado Valley.
     * Run daily.
     */
    public static void checkFileSearcher() throws IOException {
        log.debug("Checking File Searcher...");

        // run the search
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/file-search?" +
                "query=Graphics/Atlases/Checkpoints/Meowsmith/1/TornadoValleyConcept/A/2b_hub.png&exact=true")) {

            log.debug("Response to first request: {}", IOUtils.toString(is, UTF_8));
        }

        // leave time for the search to be done
        try {
            Thread.sleep(15000);
        } catch (InterruptedException e) {
            throw new IOException(e);
        }

        // check the result of the search
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/file-search?" +
                "query=Graphics/Atlases/Checkpoints/Meowsmith/1/TornadoValleyConcept/A/2b_hub.png&exact=true")) {

            String result = IOUtils.toString(is, UTF_8);
            log.debug("Response to second request: {}", result);

            if (!result.contains("{\"itemid\":150597,\"itemtype\":\"Mod\",\"fileid\":399127}")) {
                throw new IOException("File Searcher did not return expected result!");
            }
        }
    }

    /**
     * Checks that the direct link service still works as it should, by using it with Helping Hand.
     * Run daily.
     */
    public static void checkDirectLinkService() throws IOException {
        int fileId;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/everest_update.yaml")) {
            Map<String, Map<String, Object>> mapped = YamlUtil.load(is);
            fileId = (int) mapped.get("MaxHelpingHand").get("GameBananaFileId");
        }

        // search for MaxHelpingHand
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/direct-link-service");
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        try (OutputStream os = connection.getOutputStream()) {
            IOUtils.write("modId=MaxHelpingHand&twoclick=&mirror=", os, UTF_8);
        }

        String resultHtml;
        try (InputStream is = connection.getInputStream()) {
            resultHtml = IOUtils.toString(is, UTF_8);
        }
        if (!resultHtml.contains("https://maddie480.ovh/celeste/dl?id=MaxHelpingHand&amp;twoclick=1&amp;mirror=1")) {
            throw new IOException("Direct Link Service did not send the direct link!");
        }

        connection = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/dl?id=MaxHelpingHand&twoclick=1");
        connection.setInstanceFollowRedirects(false);
        if (!("https://0x0a.de/twoclick?gamebanana.com/mmdl/" + fileId).equals(connection.getHeaderField("location"))) {
            throw new IOException("Direct Link Service did not redirect to GameBanana correctly!");
        }

        connection = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/dl?id=MaxHelpingHand&twoclick=1&mirror=1");
        connection.setInstanceFollowRedirects(false);
        if (!("https://0x0a.de/twoclick?celestemodupdater.0x0a.de/banana-mirror/" + fileId + ".zip").equals(connection.getHeaderField("location"))) {
            throw new IOException("Direct Link Service did not redirect to mirror correctly!");
        }
    }

    /**
     * Checks that the page for speedrun.com update notification setup still displays properly.
     * Run daily.
     */
    public static void checkSrcModUpdateNotificationsPage() throws IOException {
        String contents = IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/src-mod-update-notifications?key="
                + SecretConstants.SRC_MOD_UPDATE_NOTIFICATIONS_KEY), UTF_8);
        if (!contents.contains("SpringCollab2020")
                || !contents.contains("The 2020 Celeste Spring Community Collab")
                || !contents.contains("https://gamebanana.com/mods/150813")) {

            throw new IOException("speedrun.com mod update notifications page does not render properly or does not have Spring Collab on it");
        }
    }

    /**
     * Checks that the Discord Bots page shows the Mod Structure Verifier server count.
     * Run daily.
     */
    public static void checkDiscordBotsPage() throws IOException {
        Document soup = Jsoup.connect("https://maddie480.ovh/discord-bots").get();

        String expected;
        try (InputStream is = Files.newInputStream(Paths.get("/shared/discord-bots/bot-server-counts.yaml"))) {
            int count = YamlUtil.<Map<String, Integer>>load(is).get("ModStructureVerifier");
            expected = count + " " + (count == 1 ? "server" : "servers");
        }

        if (!soup.select("#mod-structure-verifier .badge").text().equals(expected)) {
            throw new IOException("Discord Bots page does not display Mod Structure Verifier server count properly");
        }
    }

    /**
     * Checks that the #celeste_news_network subscription service page shows the webhook / subscriber count.
     * Run daily.
     */
    public static void checkCelesteNewsNetworkSubscriptionService() throws IOException {
        String contents = IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/news-network-subscription"), UTF_8);

        String expected;
        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/celeste-news-network-subscribers.json"))) {
            int count = new JSONArray(IOUtils.toString(is, UTF_8)).length();
            expected = "<b>" + count + " " + (count == 1 ? "webhook" : "webhooks") + "</b>";
        }

        if (!contents.contains(expected)) {
            throw new IOException("#celeste_news_network subscription service page does not show subscriber count anywhere!");
        }
    }

    /**
     * Checks that the static pages still responds (since they're not *that* static).
     * Run daily.
     */
    public static void checkStaticPages() throws IOException {
        for (String url : Arrays.asList(
                "https://maddie480.ovh/",
                "https://maddie480.ovh/discord-bots/timezone-bot/detect-timezone",
                "https://maddie480.ovh/discord-bots/timezone-bot/timezone-dropdown-help",
                "https://maddie480.ovh/discord-bots/terms-and-privacy"
        )) {
            log.debug("Checking response code of {}", url);
            HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(url);
            if (connection.getResponseCode() != 200) {
                throw new IOException(url + " responded with " + connection.getResponseCode());
            }
        }
    }
}
