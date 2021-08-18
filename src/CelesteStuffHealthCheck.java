package com.max480.discord.randombots;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A bunch of Celeste health check routines.
 * Those are run periodically, and if one throws an exception, an alert is sent to max480.
 */
public class CelesteStuffHealthCheck {
    private static final Logger log = LoggerFactory.getLogger(CelesteStuffHealthCheck.class);

    /**
     * Checks that every Everest branch has a version and that we can download it.
     * Ran daily at midnight.
     */
    public static void checkEverestExists() throws IOException {
        JSONObject object = new JSONObject(IOUtils.toString(new URL("https://dev.azure.com/EverestAPI/Everest/_apis/build/builds?api-version=5.0").openStream(), UTF_8));
        JSONArray versionList = object.getJSONArray("value");
        int latestStable = -1;
        int latestBeta = -1;
        int latestDev = -1;
        for (Object version : versionList) {
            JSONObject versionObj = (JSONObject) version;
            String reason = versionObj.getString("reason");
            if (Arrays.asList("manual", "individualCI").contains(reason)) {
                switch (versionObj.getString("sourceBranch")) {
                    case "refs/heads/dev":
                        latestDev = Math.max(latestDev, versionObj.getInt("id"));
                        break;
                    case "refs/heads/beta":
                        latestBeta = Math.max(latestBeta, versionObj.getInt("id"));
                        break;
                    case "refs/heads/stable":
                        latestStable = Math.max(latestStable, versionObj.getInt("id"));
                        break;
                }
            }
        }

        if (latestStable == -1) {
            throw new IOException("There is no Everest stable version :a:");
        }
        log.debug("Latest stable version: " + latestStable);
        if (latestBeta == -1) {
            throw new IOException("There is no beta Everest version :a:");
        }
        log.debug("Latest beta version: " + latestBeta);
        if (latestDev == -1) {
            throw new IOException("There is no Everest dev version :a:");
        }
        log.debug("Latest dev version: " + latestDev);

        checkExists(latestStable);
        checkExists(latestBeta);
        checkExists(latestDev);
    }

    /**
     * Tries downloading an Everest version.
     *
     * @param version The version to download
     */
    private static void checkExists(int version) throws IOException {
        log.debug("Downloading version " + version + "...");
        byte[] contents = IOUtils.toByteArray(new URL("https://dev.azure.com/EverestAPI/Everest/_apis/build/builds/" + version + "/artifacts?artifactName=main&api-version=5.0&%24format=zip"));
        log.debug("Size of version {}: {}", version, contents.length);
        if (contents.length < 1_000_000) {
            throw new IOException("Version " + version + " is too small (" + contents.length + "), that's suspicious");
        }
    }

    /**
     * Checks that the custom entity catalog was updated just now.
     * Ran daily at midnight, just after {@link CustomEntityCatalogGenerator}.
     */
    public static void checkCustomEntityCatalog() throws IOException {
        // the catalog should have just be refreshed, and the date is UTC on the frontend
        String expectedRefreshDate = ZonedDateTime.now().withZoneSameInstant(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH));

        log.debug("Loading custom entity catalog... (expecting date: {})", expectedRefreshDate);
        if (!IOUtils.toString(new URL("https://max480-random-stuff.appspot.com/celeste/custom-entity-catalog"), UTF_8)
                .contains(expectedRefreshDate)) {

            throw new IOException("The latest refresh date of the Custom Entity Catalog is not \"" + expectedRefreshDate + "\" :a:");
        }

        expectedRefreshDate = ZonedDateTime.now().withZoneSameInstant(ZoneId.of("Europe/Paris"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH));

        log.debug("Loading custom entity catalog JSON... (expecting date: {})", expectedRefreshDate);
        if (!IOUtils.toString(new URL("https://max480-random-stuff.appspot.com/celeste/custom-entity-catalog.json"), UTF_8)
                .contains(expectedRefreshDate)) {

            throw new IOException("The latest refresh date of the Custom Entity Catalog JSON is not \"" + expectedRefreshDate + "\" :a:");
        }
    }

    /**
     * Checks that the list of files on Banana Mirror is the exact same as the files listed in everest_update.yaml
     * (so there is no "desync" between both).
     * Ran daily at midnight.
     */
    public static void checkBananaMirrorDatabaseMatch() throws IOException {
        log.debug("Checking Banana Mirror contents...");

        List<String> bananaMirror = Jsoup.connect("https://celestemodupdater.0x0a.de/banana-mirror/").get()
                .select("td.indexcolname a")
                .stream()
                .map(a -> "https://celestemodupdater.0x0a.de/banana-mirror/" + a.attr("href"))
                .filter(item -> !item.equals("https://celestemodupdater.0x0a.de/banana-mirror//"))
                .sorted()
                .collect(Collectors.toList());

        List<String> everestUpdate;
        try (InputStream is = new URL("https://max480-random-stuff.appspot.com/celeste/everest_update.yaml").openStream()) {
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
    }

    /**
     * Checks that max480-random-stuff.herokuapp.com APIs work as expected.
     * Ran daily at midnight.
     */
    public static void checkHerokuAppWorks() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("https://max480-random-stuff.herokuapp.com/api/mods?q=spring").openConnection();
        connection.setRequestProperty("Accept", "application/json");
        connection.connect();
        if (!IOUtils.toString(connection.getInputStream(), UTF_8).contains("\"SpringCollab2020Audio\":\"https://celestemodupdater.0x0a.de/banana-mirror/484937.zip\"")) {
            throw new IOException("Banana Mirror Browser API test failed");
        }
        connection.disconnect();

        connection = (HttpURLConnection) new URL("https://max480-random-stuff.herokuapp.com/api/assets/tilesets").openConnection();
        connection.setRequestProperty("Accept", "application/json");
        connection.connect();
        if (!IOUtils.toString(connection.getInputStream(), UTF_8).contains("\"Tilesets/Background Tilesets")) {
            throw new IOException("Google Drive category list API test failed");
        }
        connection.disconnect();
    }

    /**
     * Checks that GameBanana categories didn't change overnight (because that requires changes in the updater).
     * YES means the category accepts files, NO means it doesn't.
     * Run daily at midnight.
     */
    public static void checkGameBananaCategories() throws IOException {
        Process gamebananaChecker = new ProcessBuilder("/bin/bash", "-c", "./check_gb.sh").start();
        String result = IOUtils.toString(gamebananaChecker.getInputStream(), StandardCharsets.UTF_8);
        if (!result.equals("App - NO\n" +
                "Article - NO\n" +
                "Blog - NO\n" +
                "Castaway - YES\n" +
                "Club - NO\n" +
                "Concept - NO\n" +
                "Contest - NO\n" +
                "Crafting - YES\n" +
                "Effect - YES\n" +
                "Event - NO\n" +
                "Gamefile - YES\n" +
                "Gui - YES\n" +
                "Idea - NO\n" +
                "Jam - NO\n" +
                "Map - YES\n" +
                "Mod - YES\n" +
                "Model - YES\n" +
                "News - NO\n" +
                "Poll - NO\n" +
                "PositionAvailable - NO\n" +
                "Prefab - YES\n" +
                "Project - NO\n" +
                "Question - NO\n" +
                "Request - NO\n" +
                "Review - NO\n" +
                "Script - NO\n" +
                "Skin - YES\n" +
                "Sound - YES\n" +
                "Spray - YES\n" +
                "Studio - NO\n" +
                "Texture - YES\n" +
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
        if (!IOUtils.toString(new URL("https://max480-random-stuff.appspot.com/celeste/gamebanana-search?q=EXTENDED+VARIANT+MODE").openStream(), UTF_8)
                .contains("{itemtype: Mod, itemid: 53650}")) {

            throw new IOException("Extended Variant Mode search test failed");
        }
        if (!IOUtils.toString(new URL("https://max480-random-stuff.appspot.com/celeste/gamebanana-search?q=EXTENDED+VARIANT+MODE&version=2").openStream(), UTF_8)
                .contains("\"https://gamebanana.com/mods/53650\"")) {

            throw new IOException("Extended Variant Mode search test through API v2 failed");
        }

        if (!IOUtils.toString(new URL("https://max480-random-stuff.appspot.com/celeste/gamebanana-list?sort=downloads&type=Tool&page=1").openStream(), UTF_8)
                .contains("{itemtype: Tool, itemid: 6449}")) { // Everest

            throw new IOException("Sorted list API test failed");
        }
        if (!IOUtils.toString(new URL("https://max480-random-stuff.appspot.com/celeste/gamebanana-categories?version=2").openStream(), UTF_8)
                .contains("- itemtype: Tool\n" +
                        "  formatted: Tools\n" +
                        "  count: ")) {

            throw new IOException("Categories list API failed");
        }
        if (!IOUtils.toString(new URL("https://max480-random-stuff.appspot.com/gamebanana/rss-feed?_aCategoryRowIds[]=5081&_sOrderBy=_tsDateAdded,ASC&_nPerpage=10").openStream(), UTF_8)
                .contains("<title>Outcast Outback Helper</title>")) {

            throw new IOException("RSS feed by category API failed");
        }

        HttpURLConnection connection = (HttpURLConnection)
                new URL("https://max480-random-stuff.appspot.com/celeste/webp-to-png?src=https://images.gamebanana.com/img/ss/mods/5b05ac2b4b6da.webp").openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        if (IOUtils.toByteArray(connection.getInputStream()).length < 10_000) {
            throw new IOException("WebP to PNG API test failed");
        }
        connection.disconnect();

        connection = (HttpURLConnection)
                new URL("https://max480-random-stuff.appspot.com/celeste/banana-mirror-image?src=https://images.gamebanana.com/img/ss/mods/5b05ac2b4b6da.webp").openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        if (IOUtils.toByteArray(connection.getInputStream()).length < 10_000) {
            throw new IOException("Banana Mirror Image API test failed");
        }
        connection.disconnect();
    }

    /**
     * Checks that the backend is still serving Update Checker files, and that the Update Checker is still alive.
     * If the Update Checker runs into an error, it will already be posted by {@link UpdateCheckerTracker}.
     * Run hourly.
     */
    public static void updateCheckerHealthCheck() throws IOException {
        log.debug("Checking Update Checker");

        String[] files = new String[]{"everestupdate.yaml", "everestupdateexcluded.yaml", "everestupdatenoyaml.yaml"};
        for (String file : files) {
            log.debug("GET " + SecretConstants.BACKEND_BASE_URL + file);
            String file1 = IOUtils.toString(new URL(SecretConstants.BACKEND_BASE_URL + file), UTF_8);

            if (file.equals("everestupdate.yaml")) {
                log.debug("GET https://max480-random-stuff.appspot.com/celeste/everest_update.yaml");
                String file2 = IOUtils.toString(new URL("https://max480-random-stuff.appspot.com/celeste/everest_update.yaml"), UTF_8);
                if (!file1.equals(file2)) {
                    throw new IOException("Both " + file + " files are not identical");
                }
            }
        }

        String fileIds = IOUtils.toString(new URL("https://max480-random-stuff.appspot.com/celeste/file_ids.yaml"), UTF_8);
        if (!fileIds.contains("'484937'")) {
            throw new IOException("file_ids.yaml check failed");
        }

        String updateCheckerStatus = IOUtils.toString(new URL("https://max480-random-stuff.appspot.com/celeste/update-checker-status"), UTF_8);
        if (!updateCheckerStatus.contains("The update checker is up and running!")) {
            throw new IOException("Update checker is not OK according to status page!");
        }

        if (UpdateCheckerTracker.lastLogLineDate.isBefore(ZonedDateTime.now().minusMinutes(20))) {
            throw new IOException("Update Checker did not emit any log line since " +
                    UpdateCheckerTracker.lastLogLineDate.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG)));
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

        // build a request to everest.yaml validator
        HttpPostMultipart submit = new HttpPostMultipart("https://max480-random-stuff.appspot.com/celeste/everest-yaml-validator", "UTF-8", new HashMap<>());
        submit.addFilePart("file", new File("/tmp/everest.yaml"));
        HttpURLConnection result = submit.finish();

        // delete the temp file
        new File("/tmp/everest.yaml").delete();

        // read the response from everest.yaml validator and check the Winter Collab is deemed valid.
        String resultBody = IOUtils.toString(result.getInputStream(), StandardCharsets.UTF_8);
        if (!resultBody.contains("Your everest.yaml file seems valid!")
                || !resultBody.contains("WinterCollab2021Audio") || !resultBody.contains("VivHelper") || !resultBody.contains("1.4.1")) {
            throw new IOException("everest.yaml validator gave unexpected output for Winter Collab yaml file");
        }
    }
}
