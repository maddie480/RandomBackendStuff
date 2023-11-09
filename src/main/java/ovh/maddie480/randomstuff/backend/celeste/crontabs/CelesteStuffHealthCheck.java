package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import ovh.maddie480.everest.updatechecker.DatabaseUpdater;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.HttpPostMultipart;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.Main;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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
        int latestCore = -1;

        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/everest-versions-with-native.json"))) {
            JSONArray versionList = new JSONArray(IOUtils.toString(is, UTF_8));

            for (Object version : versionList) {
                JSONObject versionObj = (JSONObject) version;

                switch (versionObj.getString("branch")) {
                    case "core" -> latestCore = Math.max(latestCore, versionObj.getInt("version"));
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
        if (latestCore == -1) {
            throw new IOException("There is no Everest core version :a:");
        }
        log.debug("Latest Everest core version: " + latestCore);

        // check the last version we sent an SRC notification for.
        int savedLatestEverest = Integer.parseInt(FileUtils.readFileToString(new File("latest_everest.txt"), UTF_8));

        if (savedLatestEverest < latestStable) {
            // a new stable version of Everest hit! send a notification to SRC staff.
            WebhookExecutor.executeWebhook(SecretConstants.SRC_UPDATE_CHECKER_HOOK,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/update-checker.png",
                    "Everest Update Checker",
                    "**A new Everest stable was just released!**\nThe latest stable version is now **" + latestStable + "**.");
            WebhookExecutor.executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/update-checker.png",
                    "Everest Update Checker",
                    ":information_source: Message sent to SRC staff:\n> **A new Everest stable was just released!**\n> The latest stable version is now **" + latestStable + "**.");

            // and save the fact that we notified about this version.
            FileUtils.writeStringToFile(new File("latest_everest.txt"), Integer.toString(latestStable), UTF_8);
        }

        // save the latest versions to Cloud Storage for the everest.yaml validator to use
        Files.writeString(Paths.get("/shared/celeste/latest-everest-versions.json"), new JSONObject(ImmutableMap.of(
                "stable", latestStable,
                "beta", latestBeta,
                "dev", latestDev,
                "core", latestCore
        )).toString());

        if (daily) {
            checkEverestVersionExists(latestStable);
            checkEverestVersionExists(latestBeta);
            checkEverestVersionExists(latestDev);
            checkEverestVersionExists(latestCore);
        }
    }

    /**
     * Checks that every Olympus branch has a version and that we can download it for all 3 supported operating systems.
     */
    public static void checkOlympusExists(boolean daily) throws IOException {
        String latestStable = "";
        String latestMain = "";
        String latestWindowsInit = "";

        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/olympus-versions.json"))) {
            JSONArray versionList = new JSONArray(IOUtils.toString(is, UTF_8));

            for (Object version : versionList) {
                JSONObject versionObj = (JSONObject) version;

                switch (versionObj.getString("branch")) {
                    case "windows-init" ->
                            latestWindowsInit = maxString(latestWindowsInit, versionObj.getString("version"));
                    case "main" -> latestMain = maxString(latestMain, versionObj.getString("version"));
                    case "stable" -> latestStable = maxString(latestStable, versionObj.getString("version"));
                }
            }
        }

        if (latestStable.isEmpty()) {
            throw new IOException("There is no Olympus stable version :a:");
        }
        log.debug("Latest Olympus stable version: " + latestStable);
        if (latestMain.isEmpty()) {
            throw new IOException("There is no Olympus dev version :a:");
        }
        log.debug("Latest Olympus dev version: " + latestMain);
        if (latestWindowsInit.isEmpty()) {
            throw new IOException("There is no Olympus windows-init version :a:");
        }
        log.debug("Latest Olympus windows-init version: " + latestWindowsInit);

        if (daily) {
            checkOlympusVersionExists("main");
            checkOlympusVersionExists("stable");
            checkOlympusVersionExists("windows-init");
        }
    }

    private static String maxString(String a, String b) {
        return a.compareTo(b) > 0 ? a : b;
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
    private static void checkOlympusVersionExists(String branch) throws IOException {
        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/olympus-versions.json"))) {
            JSONArray versionList = new JSONArray(IOUtils.toString(is, UTF_8));

            for (Object version : versionList) {
                JSONObject versionObj = (JSONObject) version;

                if (branch.equals(versionObj.getString("branch"))) {
                    checkExists(versionObj.getString("windowsDownload"));
                    checkExists(versionObj.getString("macDownload"));
                    checkExists(versionObj.getString("linuxDownload"));
                    break;
                }
            }
        }
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
        for (String mirror : Arrays.asList("https://celestemodupdater.0x0a.de", "https://celestemodupdater-storage.0x0a.de",
                "https://celestemodupdater-conductor.0x0a.de", "https://celestemodupdater-mirror.papyrus.0x0a.de")) {

            log.debug("Checking Banana Mirror contents ({})...", mirror);

            // === zips referenced in everest_update.yaml should be present at https://celestemodupdater.0x0a.de/banana-mirror/
            List<String> bananaMirror = ConnectionUtils.jsoupGetWithRetry(mirror + "/banana-mirror/")
                    .select("td.indexcolname a")
                    .stream()
                    .map(a -> mirror + "/banana-mirror/" + a.attr("href"))
                    .filter(item -> !item.equals(mirror + "/banana-mirror//"))
                    .sorted()
                    .toList();

            List<String> everestUpdate;
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/everest_update.yaml")) {
                Map<String, Map<String, Object>> mapped = YamlUtil.load(is);
                everestUpdate = mapped.values()
                        .stream()
                        .map(item -> item.get(Main.serverConfig.mainServerIsMirror ? "URL" : "MirrorURL")
                                .toString().replace("https://celestemodupdater.0x0a.de", mirror))
                        .sorted()
                        .collect(Collectors.toList());
            }

            if (!bananaMirror.equals(everestUpdate)) {
                throw new IOException("Banana Mirror contents at " + mirror + " don't match the mod updater database");
            }

            // === images referenced in mod_search_database.yaml should be present at https://celestemodupdater.0x0a.de/banana-mirror-images/
            List<String> bananaMirrorImages = ConnectionUtils.jsoupGetWithRetry(mirror + "/banana-mirror-images/")
                    .select("td.indexcolname a")
                    .stream()
                    .map(a -> mirror + "/banana-mirror-images/" + a.attr("href"))
                    .filter(item -> !item.equals(mirror + "/banana-mirror-images//"))
                    .sorted()
                    .toList();

            List<String> modSearchDatabase;
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/mod_search_database.yaml")) {
                List<Map<String, Object>> mapped = YamlUtil.load(is);
                modSearchDatabase = mapped.stream()
                        .map(item -> (List<String>) item.get("MirroredScreenshots"))
                        .flatMap(List::stream)
                        .distinct()
                        .sorted()
                        .map(i -> i.replace("https://celestemodupdater.0x0a.de", mirror))
                        .collect(Collectors.toList());
            }

            if (!bananaMirrorImages.equals(modSearchDatabase)) {
                throw new IOException("Banana Mirror Images contents at " + mirror + " don't match the mod updater database");
            }

            // these mirrors don't have Rich Presence icons
            if (Arrays.asList("https://celestemodupdater-conductor.0x0a.de",
                    "https://celestemodupdater-mirror.papyrus.0x0a.de").contains(mirror)) {
                continue;
            }

            // === Rich Presence icons we saved locally should be present at https://celestemodupdater.0x0a.de/rich-presence-icons/
            List<String> richPresenceIcons = ConnectionUtils.jsoupGetWithRetry(mirror + "/rich-presence-icons/")
                    .select("td.indexcolname a")
                    .stream()
                    .map(a -> mirror + "/rich-presence-icons/" + a.attr("href"))
                    .filter(item -> !item.equals(mirror + "/rich-presence-icons//") && !item.equals(mirror + "/rich-presence-icons/list.json"))
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
                        .map(a -> mirror + "/rich-presence-icons/" + a + ".png")
                        .sorted()
                        .collect(Collectors.toList());
            }

            // and they should also match the list present at list.json
            JSONArray richPresenceIconListJson;
            try (InputStream is = ConnectionUtils.openStreamWithTimeout(mirror + "/rich-presence-icons/list.json")) {
                richPresenceIconListJson = new JSONArray(IOUtils.toString(is, UTF_8));
            }
            List<String> richPresenceIconsList = new ArrayList<>();
            for (Object o : richPresenceIconListJson) {
                richPresenceIconsList.add(mirror + "/rich-presence-icons/" + o + ".png");
            }
            richPresenceIconsList.sort(Comparator.naturalOrder());

            if (!richPresenceIcons.equals(richPresenceIconsLocal) || !richPresenceIcons.equals(richPresenceIconsList)) {
                throw new IOException("Banana Mirror Rich Presence Icons contents at " + mirror + " don't match the ones we have saved locally");
            }
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

        // Everest versions: check that we are getting redirected to latest dev
        HttpURLConnection redirectConnection = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/download-everest?branch=dev");
        redirectConnection.setInstanceFollowRedirects(false);
        if (redirectConnection.getResponseCode() != 302 || !redirectConnection.getHeaderField("Location").contains((latestDev - 700) + "")) {
            throw new IOException("Everest redirect test failed");
        }

        // Olympus versions: check that the expected file is sent out
        try (InputStream i1 = Files.newInputStream(Paths.get("/shared/celeste/olympus-versions.json"));
             InputStream i2 = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/olympus-versions")) {

            if (!IOUtils.toString(i1, UTF_8).equals(IOUtils.toString(i2, UTF_8))) {
                throw new IOException("Olympus versions test failed");
            }
        }

        // Olympus redirect: check that we are redirected to latest stable
        String latestStableLink = null;
        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/olympus-versions.json"))) {
            JSONArray versionList = new JSONArray(IOUtils.toString(is, UTF_8));

            for (Object version : versionList) {
                JSONObject versionObj = (JSONObject) version;
                if ("stable".equals(versionObj.getString("branch"))) {
                    latestStableLink = versionObj.getString("windowsDownload");
                    break;
                }
            }
        }

        redirectConnection = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/download-olympus?branch=stable&platform=windows");
        redirectConnection.setInstanceFollowRedirects(false);
        if (redirectConnection.getResponseCode() != 302 || !redirectConnection.getHeaderField("Location").equals(latestStableLink)) {
            throw new IOException("Olympus redirect test failed");
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
        if (!IOUtils.toString(ConnectionUtils.connectionToInputStream(connection), UTF_8).contains("Celeste")) {
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

        // and the status page says everything is fine
        final String updateCheckerStatus = ConnectionUtils.toStringWithTimeout("https://maddie480.ovh/celeste/update-checker-status", UTF_8);
        if (!updateCheckerStatus.contains("The update checker is up and running!")) {
            throw new IOException("Update checker is not OK according to status page!");
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
            String resultBody = IOUtils.toString(ConnectionUtils.connectionToInputStream(result), StandardCharsets.UTF_8);
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
            JSONObject resultBody = new JSONObject(IOUtils.toString(ConnectionUtils.connectionToInputStream(result), StandardCharsets.UTF_8));
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
     * Checks that the font generator works by sending it the Collab Utils 2 Japanese translation, using BMFont.
     * Run daily.
     */
    public static void checkFontGeneratorBMFont() throws IOException {
        log.debug("Downloading sample dialog file...");

        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://raw.githubusercontent.com/EverestAPI/CelesteCollabUtils2/master/Dialog/Japanese.txt");
             OutputStream os = new FileOutputStream("/tmp/Japanese.txt")) {

            IOUtils.copy(is, os);
        }

        log.debug("Sending request to font generator...");

        // build a request to font generator
        HttpPostMultipart submit = new HttpPostMultipart("https://maddie480.ovh/celeste/font-generator", "UTF-8", new HashMap<>());
        submit.addFormField("font", "japanese");
        submit.addFilePart("dialogFile", new File("/tmp/Japanese.txt"));
        HttpURLConnection result = submit.finish();

        // delete the temp file
        new File("/tmp/Japanese.txt").delete();

        if (result.getResponseCode() != 200) {
            throw new IOException("Font generator responded with HTTP code " + result.getResponseCode());
        }

        String url = result.getURL().toString();
        log.debug("Font generator task tracker URL: {}, checking result in 60 seconds", url);

        try {
            Thread.sleep(60000);
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
     * Checks that the font generator works by sending it the Collab Utils 2 English text file, using BMFont.
     * Run daily.
     */
    public static void checkFontGeneratorBMFontCustom() throws IOException {
        log.debug("Downloading sample dialog file...");

        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://raw.githubusercontent.com/EverestAPI/CelesteCollabUtils2/master/Dialog/English.txt");
             OutputStream os = new FileOutputStream("/tmp/English.txt")) {

            IOUtils.copy(is, os);
        }

        log.debug("Downloading sample font...");

        log.debug("Sending request to custom font generator...");

        // build a request to font generator
        HttpPostMultipart submit = new HttpPostMultipart("https://maddie480.ovh/celeste/font-generator", "UTF-8", new HashMap<>());
        submit.addFormField("font", "custom");
        submit.addFilePart("dialogFile", new File("/tmp/English.txt"));
        submit.addFilePart("fontFile", new File("/app/static/font-healthcheck.ttf"));
        submit.addFormField("fontFileName", "celeste_stuff_health_check");
        HttpURLConnection result = submit.finish();

        // delete the temp files
        new File("/tmp/English.txt").delete();

        if (result.getResponseCode() != 200) {
            throw new IOException("Font generator responded with HTTP code " + result.getResponseCode());
        }

        String url = result.getURL().toString();
        log.debug("Font generator task tracker URL: {}, checking result in 60 seconds", url);

        try {
            Thread.sleep(60000);
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
            for (int i = 0; i < 4; i++) {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null || (!entry.getName().equals("celeste_stuff_health_check.fnt") && !entry.getName().startsWith("celeste_stuff_health_check_generated_"))) {
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
        try (InputStream is = ConnectionUtils.connectionToInputStream(result)) {
            // the response is a URL relative to maddie480.ovh.
            url = "https://maddie480.ovh" + IOUtils.toString(is, UTF_8);
        }
        log.debug("Mod structure verifier task tracker URL: {}, checking result in 60 seconds", url);

        try {
            Thread.sleep(60000);
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

                    try (InputStream response = ConnectionUtils.connectionToInputStream(connection)) {
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
            Thread.sleep(60000);
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
     * Also checks that the bundle download service works by downloading The Secret of Celeste Mountain (by Xaphan).
     * Run daily.
     */
    public static void checkDirectLinkService() throws IOException {
        String socmHash, xaphanHelperHash;
        int fileId;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/everest_update.yaml")) {
            Map<String, Map<String, Object>> mapped = YamlUtil.load(is);
            fileId = (int) mapped.get("MaxHelpingHand").get("GameBananaFileId");
            socmHash = ((List<String>) mapped.get("TheSecretOfCelesteMountain").get("xxHash")).get(0);
            xaphanHelperHash = ((List<String>) mapped.get("XaphanHelper").get("xxHash")).get(0);
        }

        // search for MaxHelpingHand
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/direct-link-service");
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        try (OutputStream os = connection.getOutputStream()) {
            IOUtils.write("modId=MaxHelpingHand&twoclick=&mirror=", os, UTF_8);
        }

        String resultHtml;
        try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
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

        connection = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/bundle-download?id=MaxHelpingHand");
        connection.setInstanceFollowRedirects(false);
        if (!("https://maddie480.ovh/celeste/dl?id=MaxHelpingHand").equals(connection.getHeaderField("location"))) {
            throw new IOException("Bundle Download service didn't redirect to Direct Link Service correctly!");
        }

        // search for bundle download of SoCM
        connection = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/direct-link-service");
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        try (OutputStream os = connection.getOutputStream()) {
            IOUtils.write("modId=TheSecretOfCelesteMountain&bundle=", os, UTF_8);
        }
        try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
            resultHtml = IOUtils.toString(is, UTF_8);
        }
        if (!resultHtml.contains("https://maddie480.ovh/celeste/bundle-download?id=TheSecretOfCelesteMountain")) {
            throw new IOException("Direct Link Service did not send the bundle link!");
        }

        // bundle download of Secret of Celeste Mountain
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/bundle-download?id=TheSecretOfCelesteMountain");
             OutputStream os = Files.newOutputStream(Paths.get("/tmp/socm.zip"))) {

            IOUtils.copy(is, os);
        }

        try (ZipFile f = new ZipFile("/tmp/socm.zip")) {
            List<String> files = f.stream().map(ZipEntry::getName).sorted().toList();
            if (!Arrays.asList("TheSecretOfCelesteMountain.zip", "XaphanHelper.zip").equals(files)) {
                throw new IOException("SoCM Bundle didn't contain the expected files!");
            }

            String actualSocmHash, actualXaphanHelperHash;
            try (InputStream is = f.getInputStream(f.getEntry("TheSecretOfCelesteMountain.zip"))) {
                actualSocmHash = DatabaseUpdater.computeXXHash(is);
            }
            try (InputStream is = f.getInputStream(f.getEntry("XaphanHelper.zip"))) {
                actualXaphanHelperHash = DatabaseUpdater.computeXXHash(is);
            }

            if (!actualSocmHash.equals(socmHash) || !actualXaphanHelperHash.equals(xaphanHelperHash)) {
                throw new IOException("SoCM Bundle files are corrupted!");
            }
        }

        FileUtils.forceDelete(new File("/tmp/socm.zip"));
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
        Document soup = ConnectionUtils.jsoupGetWithRetry("https://maddie480.ovh/discord-bots");

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

    /**
     * Checks that the collab list still renders correctly, and shows at least the Anarchy Mapping Event.
     * Run daily.
     */
    public static void checkCollabList() throws IOException {
        log.debug("Checking collab list...");
        String contents = IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/collab-contest-list"), UTF_8);
        if (!contents.contains("Anarchy Mapping Event")) {
            throw new IOException("Collab list does not show Anarchy Mapping Event!");
        }

        String key = "";
        for (String s : new File("/shared/celeste/collab-list").list()) {
            try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/collab-list/" + s))) {
                JSONObject o = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                if ("Anarchy Mapping Event".equals(o.getString("name"))) {
                    key = s.substring(0, s.length() - 5);
                }
            }
        }

        log.debug("Checking collab editor with key {}...", key);
        contents = IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/collab-contest-editor?key=" + key), UTF_8);
        if (!contents.contains("Anarchy Mapping Event")) {
            throw new IOException("Collab editor does not show Anarchy Mapping Event!");
        }
    }

    /**
     * Checks that the latest Olympus news on Olympus also shows up on the website, by grabbing its title.
     * Run daily.
     */
    public static void checkOlympusNews() throws IOException {
        log.debug("Searching for an Olympus news entry to work with...");

        // list the Olympus news posts
        List<String> entries = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(ConnectionUtils.openStreamWithTimeout("https://everestapi.github.io/olympusnews/index.txt")))) {
            String s;
            while ((s = br.readLine()) != null) {
                if (s.endsWith(".md")) {
                    entries.add(s);
                }
            }
        }

        // sort them by descending order
        entries.sort(Comparator.<String>naturalOrder().reversed());

        // find one that has a title!
        String title = null;
        String slug = null;

        for (String entryName : entries) {
            String data = ConnectionUtils.toStringWithTimeout("https://everestapi.github.io/olympusnews/" + entryName, StandardCharsets.UTF_8);

            // split between data, preview and full text
            String yaml = data.split("\n---\n", 2)[0];
            Map<String, String> yamlParsed;
            try (ByteArrayInputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
                yamlParsed = YamlUtil.load(is);
            }

            if (yamlParsed.containsKey("title")) {
                title = yamlParsed.get("title");
                slug = entryName.substring(0, entryName.length() - 3);
                break;
            }
        }

        title = StringEscapeUtils.escapeHtml4(title);

        log.debug("Checking that {} with title {} is present on the Olympus News page...", slug, title);

        String siteContent;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/olympus-news")) {
            siteContent = IOUtils.toString(is, UTF_8);
        }
        if (!siteContent.contains(title)) {
            throw new IOException("Olympus News was not found on the page!");
        }

        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/olympus-news.json")) {
            siteContent = IOUtils.toString(is, UTF_8);
        }
        if (!siteContent.contains(slug)) {
            throw new IOException("Olympus News was not found on the page!");
        }

        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/olympus-news.xml")) {
            siteContent = IOUtils.toString(is, UTF_8);
        }
        if (!siteContent.contains(slug)) {
            throw new IOException("Olympus News was not found on the page!");
        }
    }

    /**
     * Checks that there are assets in the asset drive browser, and that we can download one from each category.
     * Run daily.
     */
    public static void checkAssetDriveBrowser() throws IOException {
        for (String category : Arrays.asList("decals", "stylegrounds", "bgtilesets", "fgtilesets", "hires", "misc")) {
            JSONArray list;
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/asset-drive/list/" + category)) {
                list = new JSONArray(IOUtils.toString(is, UTF_8));
            }

            if (list.isEmpty()) {
                throw new IOException("The list of " + category + " is empty!");
            }

            String idToDownload = list.getJSONObject(0).getString("id");
            log.debug("There are {} {} on the website, trying to download {}", list.length(), category, idToDownload);

            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/asset-drive/files/" + idToDownload)) {
                checkPngInputStreamForAssetBrowser(is);
            }

            // download the first 100 files
            StringBuilder idsToDownload = new StringBuilder("https://maddie480.ovh/celeste/asset-drive/multi-download?files=");
            Set<String> namesAlreadyUsed = new HashSet<>();
            for (int i = 0; i < list.length() && namesAlreadyUsed.size() < 100; i++) {
                String name = list.getJSONObject(i).getString("name");
                if (namesAlreadyUsed.contains(name)) continue;
                namesAlreadyUsed.add(name);

                if (i != 0) idsToDownload.append(',');
                idsToDownload.append(list.getJSONObject(i).getString("id"));
            }
            log.debug("Bulk download test: {}", idsToDownload);

            try (ZipInputStream is = new ZipInputStream(ConnectionUtils.openStreamWithTimeout(idsToDownload.toString()))) {
                for (int i = 0; i < namesAlreadyUsed.size(); i++) {
                    ZipEntry entry = is.getNextEntry();
                    if (entry == null) throw new IOException("Zip has less than " + namesAlreadyUsed.size() + " files!");
                    log.debug("Reading file: {}", entry.getName());
                    checkPngInputStreamForAssetBrowser(is);
                }
                if (is.getNextEntry() != null) throw new IOException("Zip has more than 100 files!");
            }
        }
    }

    private static void checkPngInputStreamForAssetBrowser(InputStream is) throws IOException {
        byte[] signature = new byte[8];
        int readBytes = is.read(signature);

        // assets have to be PNG files, no exceptions (they're filtered on MIME type)
        boolean isPng = readBytes == 8
                && signature[0] == -119 // 0x89
                && signature[1] == 0x50
                && signature[2] == 0x4E
                && signature[3] == 0x47
                && signature[4] == 0x0D
                && signature[5] == 0x0A
                && signature[6] == 0x1A
                && signature[7] == 0x0A;

        if (!isPng) {
            throw new IOException("Downloaded file is not a PNG file!");
        }

        log.debug("Downloaded {} bytes", IOUtils.consume(is) + 8);
    }
}
