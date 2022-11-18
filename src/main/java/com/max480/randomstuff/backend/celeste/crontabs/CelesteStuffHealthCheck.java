package com.max480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.CloudStorageUtils;
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
import org.yaml.snakeyaml.Yaml;

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
 * Those are run periodically, and if one throws an exception, an alert is sent to max480.
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

        try (InputStream is = CloudStorageUtils.getCloudStorageInputStream("everest_version_list.json")) {
            JSONArray versionList = new JSONArray(IOUtils.toString(is, UTF_8));

            for (Object version : versionList) {
                JSONObject versionObj = (JSONObject) version;

                switch (versionObj.getString("branch")) {
                    case "dev":
                        latestDev = Math.max(latestDev, versionObj.getInt("version"));
                        break;
                    case "beta":
                        latestBeta = Math.max(latestBeta, versionObj.getInt("version"));
                        break;
                    case "stable":
                        latestStable = Math.max(latestStable, versionObj.getInt("version"));
                        break;
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
        CloudStorageUtils.sendStringToCloudStorage(new JSONObject(ImmutableMap.of(
                "stable", latestStable,
                "beta", latestBeta,
                "dev", latestDev
        )).toString(), "everest_versions.json", "application/json");

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
                    case "refs/heads/main":
                        latestMain = Math.max(latestMain, versionObj.getInt("id"));
                        break;
                    case "refs/heads/stable":
                        latestStable = Math.max(latestStable, versionObj.getInt("id"));
                        break;
                    case "refs/heads/windows-init":
                        latestWindowsInit = Math.max(latestWindowsInit, versionObj.getInt("id"));
                        break;
                }
            }
        }

        if (latestStable == -1) {
            throw new IOException("There is no Olympus stable version :a:");
        }
        log.debug("Latest Olympus stable version: " + latestStable);
        if (latestMain == -1) {
            // dev doesn't exist currently, and Olympus falls back to stable when it doesn't
            // throw new IOException("There is no Olympus dev version :a:");
            latestMain = latestStable;
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
        try (InputStream is = CloudStorageUtils.getCloudStorageInputStream("everest_version_list.json")) {
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
        if (!ConnectionUtils.toStringWithTimeout("https://max480-random-stuff.appspot.com/celeste/custom-entity-catalog", UTF_8)
                .contains(expectedRefreshDate)) {

            throw new IOException("The latest refresh date of the Custom Entity Catalog is not \"" + expectedRefreshDate + "\" :a:");
        }

        expectedRefreshDate = ZonedDateTime.now().withZoneSameInstant(ZoneId.of("Europe/Paris"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH));

        log.debug("Loading custom entity catalog JSON... (expecting date: {})", expectedRefreshDate);
        if (!ConnectionUtils.toStringWithTimeout("https://max480-random-stuff.appspot.com/celeste/custom-entity-catalog.json", UTF_8)
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
                .collect(Collectors.toList());

        List<String> everestUpdate;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/everest_update.yaml")) {
            Map<String, Map<String, Object>> mapped = new Yaml().load(is);
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
                .collect(Collectors.toList());

        List<String> modSearchDatabase;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/mod_search_database.yaml")) {
            List<Map<String, Object>> mapped = new Yaml().load(is);
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
                .collect(Collectors.toList());

        List<String> richPresenceIconsLocal;
        try (InputStream is = Files.newInputStream(Paths.get("banana_mirror_rich_presence_icons.yaml"))) {
            Map<String, Map<String, List<String>>> mapped = new Yaml().load(is);

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
        Process gamebananaChecker = new ProcessBuilder("/bin/bash", "-c", "./check_gb.sh").start();
        String result = IOUtils.toString(gamebananaChecker.getInputStream(), StandardCharsets.UTF_8);
        if (!result.equals("App - NO\n" +
                "Article - NO\n" +
                "Blog - NO\n" +
                "Club - NO\n" +
                "Concept - NO\n" +
                "Contest - NO\n" +
                "Event - NO\n" +
                "Idea - NO\n" +
                "Jam - NO\n" +
                "Mod - YES\n" +
                "Model - YES\n" +
                "News - NO\n" +
                "Poll - NO\n" +
                "PositionAvailable - NO\n" +
                "Project - NO\n" +
                "Question - NO\n" +
                "Request - NO\n" +
                "Review - NO\n" +
                "Script - NO\n" +
                "Sound - YES\n" +
                "Spray - YES\n" +
                "Studio - NO\n" +
                "Thread - NO\n" +
                "Tool - YES\n" +
                "Tutorial - NO\n" +
                "Ware - NO\n" +
                "Wiki - NO\n" +
                "Wip - YES\n")) {

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
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/gamebanana-search?q=EXTENDED+VARIANT+MODE&full=true"), UTF_8)
                .contains("\"Name\":\"Extended Variant Mode\"")) {

            throw new IOException("Extended Variant Mode search test failed");
        }

        // sorted list
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/gamebanana-list?sort=downloads&category=6800&page=1&full=true"), UTF_8)
                .contains("\"Name\":\"The 2020 Celeste Spring Community Collab\"")) {

            throw new IOException("Sorted list API test failed");
        }

        // categories list
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/gamebanana-categories?version=2"), UTF_8)
                .contains("- categoryid: 6800\n" +
                        "  formatted: Maps\n" +
                        "  count: ")) {

            throw new IOException("Categories list API failed");
        }

        // featured mods list
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/gamebanana-featured"), UTF_8)
                .contains("\"Name\":\"The 2020 Celeste Spring Community Collab\"")) {

            throw new IOException("Featured mods list API failed");
        }

        // check that the mirror is alive by downloading a GhostNet screenshot
        if (!DigestUtils.sha256Hex(ConnectionUtils.toByteArrayWithTimeout("https://celestemodupdater.0x0a.de/banana-mirror-images/img_ss_mods_5b05ac2b4b6da.png"))
                .equals("32887093611c0338d020b23496d33bdc10838185ab2bd31fa0b903da5b9ab7e7")) {

            throw new IOException("Download from mirror test failed");
        }

        // Olympus News
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/olympus-news"), UTF_8)
                .contains("\"preview\":\"You can find every news post on Twitter!\"")) {

            throw new IOException("Olympus News test failed");
        }

        // Everest versions: check that latest dev is listed
        int latestDev;
        try (InputStream is = CloudStorageUtils.getCloudStorageInputStream("everest_versions.json")) {
            latestDev = new JSONObject(IOUtils.toString(is, UTF_8)).getInt("dev");
        }
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/everest-versions"), UTF_8)
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
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://max480-random-stuff.appspot.com/celeste/random-map");
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        if (!IOUtils.toString(connection.getInputStream(), UTF_8).contains("Celeste")) {
            throw new IOException("Didn't get redirected to a random Celeste map!");
        }
        connection.disconnect();

        // deprecated GameBanana categories API
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/gamebanana-categories"), UTF_8)
                .contains("- itemtype: Tool\n" +
                        "  formatted: Tools\n" +
                        "  count: ")) {

            throw new IOException("Categories list API v1 failed");
        }

        // deprecated GameBanana search API
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/gamebanana-search?q=EXTENDED+VARIANT+MODE"), UTF_8)
                .contains("{itemtype: Mod, itemid: 53650}")) {

            throw new IOException("Extended Variant Mode search test failed");
        }

        // deprecated GameBanana sorted list API
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/gamebanana-list?sort=downloads&type=Tool&page=1"), UTF_8)
                .contains("{itemtype: Tool, itemid: 6449}")) { // Everest

            throw new IOException("Sorted list API test failed");
        }

        // deprecated webp to png API
        connection = ConnectionUtils.openConnectionWithTimeout("https://max480-random-stuff.appspot.com/celeste/webp-to-png?src=https://images.gamebanana.com/img/ss/mods/5b05ac2b4b6da.webp");
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        if (!DigestUtils.sha256Hex(IOUtils.toByteArray(connection.getInputStream())).equals("32887093611c0338d020b23496d33bdc10838185ab2bd31fa0b903da5b9ab7e7")) {
            throw new IOException("WebP to PNG API test failed");
        }
        connection.disconnect();

        // Banana Mirror image redirect
        connection = ConnectionUtils.openConnectionWithTimeout("https://max480-random-stuff.appspot.com/celeste/banana-mirror-image?src=https://images.gamebanana.com/img/ss/mods/5b05ac2b4b6da.webp");
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        if (!DigestUtils.sha256Hex(IOUtils.toByteArray(connection.getInputStream())).equals("32887093611c0338d020b23496d33bdc10838185ab2bd31fa0b903da5b9ab7e7")) {
            throw new IOException("Banana Mirror Image API test failed");
        }
        connection.disconnect();

        // mod search database API
        final String modSearchDatabase = ConnectionUtils.toStringWithTimeout("https://max480-random-stuff.appspot.com/celeste/mod_search_database.yaml", UTF_8);
        if (!modSearchDatabase.contains("Name: The 2020 Celeste Spring Community Collab")) {
            throw new IOException("mod_search_database.yaml check failed");
        }

        // file IDs list API
        final String fileIds = ConnectionUtils.toStringWithTimeout("https://max480-random-stuff.appspot.com/celeste/file_ids.yaml", UTF_8);
        if (!fileIds.contains("'484937'")) {
            throw new IOException("file_ids.yaml check failed");
        }

        // mod files database zip
        try (ZipInputStream zis = new ZipInputStream(ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/mod_files_database.zip"))) {
            Set<String> expectedFiles = new HashSet<>(Arrays.asList(
                    "ahorn_vanilla.yaml", "list.yaml", "Mod/150813/info.yaml", "Mod/150813/484937.yaml", "Mod/53641/ahorn_506448.yaml"
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
        final String modDependencyGraph = ConnectionUtils.toStringWithTimeout("https://max480-random-stuff.appspot.com/celeste/mod_dependency_graph.yaml", UTF_8);
        if (!modDependencyGraph.contains("SpringCollab2020Audio:")
                || !modDependencyGraph.contains("URL: https://gamebanana.com/mmdl/484937")
                || !modDependencyGraph.contains("SpringCollab2020Audio: 1.0.0")) {

            throw new IOException("mod_dependency_graph.yaml check failed");
        }

        final String modDependencyGraphEverest = ConnectionUtils.toStringWithTimeout("https://max480-random-stuff.appspot.com/celeste/mod_dependency_graph.yaml?format=everestyaml", UTF_8);
        if (!modDependencyGraphEverest.contains("SpringCollab2020Audio:")
                || !modDependencyGraphEverest.contains("URL: https://gamebanana.com/mmdl/484937")
                || !modDependencyGraphEverest.contains("- Name: SpringCollab2020Audio")
                || !modDependencyGraphEverest.contains("  Version: 1.0.0")) {

            throw new IOException("mod_dependency_graph.yaml in everest.yaml format check failed");
        }

        // Update Checker status, widget version
        final String updateCheckerStatus = ConnectionUtils.toStringWithTimeout("https://max480-random-stuff.appspot.com/celeste/update-checker-status?widget=true", UTF_8);
        if (!updateCheckerStatus.contains("<span class=\"GreenColor\">Up</span>")) {
            throw new IOException("Update checker is not OK according to status widget!");
        }

        // GameBanana "JSON to RSS feed" API
        if (!IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/gamebanana/rss-feed?_aCategoryRowIds[]=5081&_sOrderBy=_tsDateAdded,ASC&_nPerpage=10"), UTF_8)
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
        final String everestUpdate = ConnectionUtils.toStringWithTimeout("https://max480-random-stuff.appspot.com/celeste/everest_update.yaml", UTF_8);
        if (!everestUpdate.contains("SpringCollab2020Audio:") || !everestUpdate.contains("URL: https://gamebanana.com/mmdl/484937")) {
            throw new IOException("everest_update.yaml check failed");
        }

        // it matches what we have on disk
        final String fileOnDisk = FileUtils.readFileToString(new File("uploads/everestupdate.yaml"), UTF_8);
        if (!fileOnDisk.equals(everestUpdate)) {
            throw new IOException("everest_update.yaml on disk and on Cloud Storage don't match!");
        }

        // the status page says everything is fine
        final String updateCheckerStatus = ConnectionUtils.toStringWithTimeout("https://max480-random-stuff.appspot.com/celeste/update-checker-status", UTF_8);
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
                "- Name: WinterCollab2021\n" +
                        "  Version: 1.3.4\n" +
                        "  DLL: \"Code/bin/Debug/WinterCollabHelper.dll\"\n" +
                        "  Dependencies:\n" +
                        "    - Name: Everest\n" +
                        "      Version: 1.2707.0\n" +
                        "    - Name: AdventureHelper\n" +
                        "      Version: 1.5.1\n" +
                        "    - Name: Anonhelper\n" +
                        "      Version: 1.0.4\n" +
                        "    - Name: BrokemiaHelper\n" +
                        "      Version: 1.2.3\n" +
                        "    - Name: CherryHelper\n" +
                        "      Version: 1.6.7\n" +
                        "    - Name: ColoredLights\n" +
                        "      Version: 1.1.1\n" +
                        "    - Name: CollabUtils2\n" +
                        "      Version: 1.3.11\n" +
                        "    - Name: CommunalHelper\n" +
                        "      Version: 1.7.0\n" +
                        "    - Name: ContortHelper\n" +
                        "      Version: 1.5.4\n" +
                        "    - Name: CrystallineHelper\n" +
                        "      Version: 1.10.0\n" +
                        "    - Name: DJMapHelper\n" +
                        "      Version: 1.8.27\n" +
                        "    - Name: ExtendedVariantMode\n" +
                        "      Version: 0.19.11\n" +
                        "    - Name: FactoryHelper\n" +
                        "      Version: 1.2.4\n" +
                        "    - Name: FancyTileEntities\n" +
                        "      Version: 1.4.0\n" +
                        "    - Name: FemtoHelper\n" +
                        "      Version: 1.1.1\n" +
                        "    - Name: FlaglinesAndSuch\n" +
                        "      Version: 1.4.6\n" +
                        "    - Name: FrostHelper\n" +
                        "      Version: 1.22.4\n" +
                        "    - Name: HonlyHelper\n" +
                        "      Version: 1.3.2\n" +
                        "    - Name: JackalHelper\n" +
                        "      Version: 1.3.5\n" +
                        "    - Name: LunaticHelper\n" +
                        "      Version: 1.1.1\n" +
                        "    - Name: MaxHelpingHand\n" +
                        "      Version: 1.13.3\n" +
                        "    - Name: memorialHelper\n" +
                        "      Version: 1.0.3\n" +
                        "    - Name: MoreDasheline\n" +
                        "      Version: 1.6.3\n" +
                        "    - Name: OutbackHelper\n" +
                        "      Version: 1.4.0\n" +
                        "    - Name: PandorasBox\n" +
                        "      Version: 1.0.29\n" +
                        "    - Name: Sardine7\n" +
                        "      Version: 1.0.0\n" +
                        "    - Name: ShroomHelper\n" +
                        "      Version: 1.0.1\n" +
                        "    - Name: TwigHelper\n" +
                        "      Version: 1.1.5\n" +
                        "    - Name: VivHelper\n" +
                        "      Version: 1.4.1\n" +
                        "    - Name: VortexHelper\n" +
                        "      Version: 1.1.0\n" +
                        "    - Name: WinterCollab2021Audio\n" +
                        "      Version: 1.3.0", UTF_8);

        { // HTML version
            // build a request to everest.yaml validator
            HttpPostMultipart submit = new HttpPostMultipart("https://max480-random-stuff.appspot.com/celeste/everest-yaml-validator", "UTF-8", new HashMap<>());
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
            HttpPostMultipart submit = new HttpPostMultipart("https://max480-random-stuff.appspot.com/celeste/everest-yaml-validator", "UTF-8", new HashMap<>());
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
        HttpPostMultipart submit = new HttpPostMultipart("https://max480-random-stuff.appspot.com/celeste/font-generator", "UTF-8", new HashMap<>());
        submit.addFormField("fontFileName", "collabutils2_japanese_healthcheck");
        submit.addFormField("font", "japanese");
        submit.addFormField("method", "libgdx");
        submit.addFilePart("dialogFile", new File("/tmp/Japanese.txt"));
        HttpURLConnection result = submit.finish();

        // delete the temp file
        new File("/tmp/Japanese.txt").delete();

        // read the response as a zip file
        try (ZipInputStream zip = new ZipInputStream(result.getInputStream())) {
            if (!zip.getNextEntry().getName().equals("collabutils2_japanese_healthcheck.png")
                    || !zip.getNextEntry().getName().equals("japanese.fnt")
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
        HttpPostMultipart submit = new HttpPostMultipart("https://max480-random-stuff.appspot.com/celeste/font-generator", "UTF-8", new HashMap<>());
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
        HttpPostMultipart submit = new HttpPostMultipart("https://max480-random-stuff.appspot.com/celeste/mod-structure-verifier", "UTF-8", new HashMap<>());
        submit.addFilePart("zipFile", new File("/tmp/tornado.zip"));
        HttpURLConnection result = submit.finish();

        // delete the temp file
        new File("/tmp/tornado.zip").delete();

        String url;
        try (InputStream is = result.getInputStream()) {
            // the response is a URL relative to max480-random-stuff.appspot.com.
            url = "https://max480-random-stuff.appspot.com" + IOUtils.toString(is, UTF_8);
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

        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://max480-random-stuff.appspot.com/celeste/bin-to-json");
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.connect();

        try (ZipInputStream zis = new ZipInputStream(ConnectionUtils.openStreamWithTimeout("https://celestemodupdater.0x0a.de/banana-mirror/399127.zip"));
             OutputStream os = connection.getOutputStream()) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("Maps/Meowsmith/1/TornadoValleyConcept.bin".equals(entry.getName())) {
                    log.debug("Found map bin, sending...");
                    IOUtils.copy(zis, os);

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
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/file-search?" +
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
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/file-search?" +
                "query=Graphics/Atlases/Checkpoints/Meowsmith/1/TornadoValleyConcept/A/2b_hub.png&exact=true")) {

            String result = IOUtils.toString(is, UTF_8);
            log.debug("Response to second request: {}", result);

            if (!result.contains("{\"itemid\":150597,\"itemtype\":\"Mod\",\"fileid\":399127}")) {
                throw new IOException("File Searcher did not return expected result!");
            }
        }
    }

    /**
     * Checks that the page for speedrun.com update notification setup still displays properly.
     * Run daily.
     */
    public static void checkSrcModUpdateNotificationsPage() throws IOException {
        String contents = IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/src-mod-update-notifications?key="
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
        Document soup = Jsoup.connect("https://max480-random-stuff.appspot.com/discord-bots").get();

        String expected;
        try (InputStream is = CloudStorageUtils.getCloudStorageInputStream("bot_server_counts.yaml")) {
            int count = new Yaml().<Map<String, Integer>>load(is).get("ModStructureVerifier");
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
        String contents = IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/news-network-subscription"), UTF_8);

        String expected;
        try (InputStream is = CloudStorageUtils.getCloudStorageInputStream("celeste_news_network_subscribers.json")) {
            int count = new JSONArray(IOUtils.toString(is, UTF_8)).length();
            expected = "<b>" + count + " " + (count == 1 ? "webhook" : "webhooks") + "</b>";
        }

        if (!contents.contains(expected)) {
            throw new IOException("#celeste_news_network subscription service page does not show subscriber count anywhere!");
        }
    }
}
