package com.max480.discord.randombots;

import com.google.common.collect.ImmutableMap;
import io.github.furstenheim.CopyDown;
import io.github.furstenheim.Options;
import io.github.furstenheim.OptionsBuilder;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * This class is intended to be run periodically to check mods on GameBanana for issues.
 * In max480's bot setup, all public methods are run everyday at midnight French time (10pm or 11pm UTC depending on daylight saving).
 */
public class GameBananaAutomatedChecks {
    private static final Logger logger = LoggerFactory.getLogger(GameBananaAutomatedChecks.class);

    private static final Options markdownOptions = OptionsBuilder.anOptions()
            .withBulletListMaker("-")
            .build();

    // files that should trigger a warning when present in a mod (files that ship with Celeste or Everest)
    private static final List<String> BAD_FILE_LIST = Arrays.asList("Celeste.exe",
            "CSteamworks.dll", "Celeste.Mod.mm.dll", "DotNetZip.dll", "FNA.dll", "I18N.CJK.dll", "I18N.MidEast.dll",
            "I18N.Other.dll", "I18N.Rare.dll", "I18N.West.dll", "I18N.dll", "Jdenticon.dll", "KeraLua.dll", "MMHOOK_Celeste.dll", "MojoShader.dll",
            "Mono.Cecil.Mdb.dll", "Mono.Cecil.Pdb.dll", "Mono.Cecil.Rocks.dll", "Mono.Cecil.dll", "MonoMod.RuntimeDetour.dll", "MonoMod.Utils.dll", "NLua.dll",
            "Newtonsoft.Json.dll", "SDL2.dll", "SDL2_image.dll", "Steamworks.NET.dll", "YamlDotNet.dll", "discord-rpc.dll", "fmod.dll", "fmodstudio.dll",
            "libEGL.dll", "libGLESv2.dll", "libjpeg-9.dll", "libpng16-16.dll", "lua53.dll", "steam_api.dll", "zlib1.dll", "Microsoft.Xna.Framework.dll",
            "Microsoft.Xna.Framework.Game.dll", "Microsoft.Xna.Framework.Graphics.dll");

    private static final Pattern gamebananaLinkRegex = Pattern.compile(".*(https://gamebanana.com/mmdl/[0-9]+).*");

    private static class GameBananaCheckResults {
        // files checked that have no issues
        public List<String> goodFiles;

        // files with issues (file ID => pre-built alert messages that can be sent again)
        public Map<String, List<String>> badFiles;

        public GameBananaCheckResults() {
            goodFiles = new ArrayList<>();
            badFiles = new HashMap<>();
        }

        public GameBananaCheckResults(Map<String, Object> source) {
            goodFiles = (List<String>) source.get("GoodFiles");
            badFiles = (Map<String, List<String>>) source.get("BadFiles");
        }

        public Map<String, Object> toMap() {
            return ImmutableMap.of(
                    "GoodFiles", goodFiles,
                    "BadFiles", badFiles
            );
        }
    }

    /**
     * Downloads every mod with a DLL and decompiles it looking for a "yield return orig.Invoke",
     * because mods shouldn't use those.
     * <p>
     * Also checks if a mod uses "the IntPtr trick" to call (for example) base.base.Awake() instead of base.Awake()
     * in a method override, because this causes Mac-only crashes with no error log.
     * <p>
     * If a mod is okay, its file ID will be saved to a yaml file and it won't be downloaded again.
     * Otherwise, webhooks will be called to warn some people about the mod.
     */
    public static void checkYieldReturnOrigAndIntPtrTrick() throws IOException {
        // the new file list is built from scratch (only files that still exist are copied over from the previous list).
        GameBananaCheckResults newResults = new GameBananaCheckResults();

        // and we want to load the previous state to be sure we don't handle already handled mods.
        GameBananaCheckResults oldResults;
        try (InputStream is = new FileInputStream("gamebanana_check_results_list.yaml")) {
            oldResults = new GameBananaCheckResults(new Yaml().load(is));
        }

        // download the updater database to figure out which mods we should scan...
        Map<String, Map<String, Object>> updaterDatabase;
        try (InputStream is = new FileInputStream("uploads/everestupdate.yaml")) {
            updaterDatabase = new Yaml().load(is);
        }

        for (Map.Entry<String, Map<String, Object>> modEntry : updaterDatabase.entrySet()) {
            String modName = modEntry.getKey();
            Map<String, Object> mod = modEntry.getValue();

            // does the file have a dll?
            String fileName = mod.get(com.max480.everest.updatechecker.Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL")
                    .toString().substring("https://gamebanana.com/mmdl/".length());

            if (oldResults.goodFiles.contains(fileName)) {
                // skip scanning known good files.
                newResults.goodFiles.add(fileName);
            } else if (oldResults.badFiles.containsKey(fileName)) {
                // skip scanning the file again, we know it is bad... alert about it again though, as a reminder.
                for (String message : oldResults.badFiles.get(fileName)) {
                    sendAlertToWebhook(message);
                }
                newResults.badFiles.put(fileName, oldResults.badFiles.get(fileName));
            } else {
                // check file listing
                List<String> fileList;
                try (InputStream is = new FileInputStream(
                        "modfilesdatabase/" + mod.get("GameBananaType") + "/" + mod.get("GameBananaId") + "/" + fileName + ".yaml")) {

                    fileList = new Yaml().load(is);
                }

                if (fileList.stream().anyMatch(file -> file.toLowerCase(Locale.ROOT).endsWith(".dll"))) {
                    // file listing contains dll, so download!
                    logger.debug("Downloading mod {} (file id {})", modName, fileName);

                    try (InputStream is = ConnectionUtils.openStreamWithTimeout(new URL(mod.get(com.max480.everest.updatechecker.Main.serverConfig.mainServerIsMirror ? "URL" : "MirrorURL").toString()))) {
                        FileUtils.copyToFile(is, new File("/tmp/mod_yield_police.zip"));
                    }

                    logger.debug("Searching for DLL");

                    try (ZipFile zip = new ZipFile(new File("/tmp/mod_yield_police.zip"))) {
                        // find the everest.yaml name used in this mod.
                        ZipEntry yaml = zip.getEntry("everest.yaml");
                        if (yaml == null) {
                            yaml = zip.getEntry("everest.yml");
                        }
                        if (yaml == null) {
                            yaml = zip.getEntry("multimetadata.yaml");
                        }

                        // read everest.yaml without extracting
                        List<Map<String, Object>> yamlContent;
                        try (InputStream is = zip.getInputStream(yaml)) {
                            yamlContent = new Yaml().load(is);
                        }

                        boolean yieldReturnIssue = false;
                        boolean intPtrIssue = false;
                        boolean readonlyStructIssue = false;

                        // read "DLL" fields for each everest.yaml entry
                        for (Map<String, Object> yamlEntry : yamlContent) {
                            Object dllPath = yamlEntry.get("DLL");
                            if (dllPath == null) {
                                logger.info("Mod actually has no DLL, skipping");
                            } else {
                                logger.debug("Extracting DLL from {}", dllPath);

                                try (InputStream is = zip.getInputStream(zip.getEntry(dllPath.toString()))) {
                                    FileUtils.copyToFile(is, new File("/tmp/mod_yield_police.dll"));
                                }

                                // invoke ilspycmd to decompile the mod.
                                logger.debug("Decompiling DLL...");
                                Process p = new ProcessBuilder("/home/rsa-key-20181108/.dotnet/tools/ilspycmd", "/tmp/mod_yield_police.dll").start();
                                String fullDecompile;
                                try (InputStream is = p.getInputStream()) {
                                    fullDecompile = IOUtils.toString(is, StandardCharsets.UTF_8);
                                }

                                // search for anything looking like yield return orig(self)
                                if (fullDecompile.contains("yield return orig.Invoke")) {
                                    logger.warn("Mod {} uses yield return orig(self)!", modName);
                                    yieldReturnIssue = true;
                                } else if (fullDecompile.contains(".MethodHandle.GetFunctionPointer()")) {
                                    logger.warn("Mod {} might be using the IntPtr trick", modName);
                                    intPtrIssue = true;
                                } else if (fullDecompile.contains("readonly struct")) {
                                    logger.warn("Mod {} might have a readonly struct", modName);
                                    readonlyStructIssue = true;
                                } else {
                                    logger.info("No yield return orig(self) detected in mod {}", modName);
                                }

                                logger.debug("Deleting temporary DLL");
                                FileUtils.forceDelete(new File("/tmp/mod_yield_police.dll"));
                            }
                        }

                        if (!yieldReturnIssue && !intPtrIssue && !readonlyStructIssue) {
                            newResults.goodFiles.add(fileName);
                        } else {
                            List<String> messages = new ArrayList<>();

                            // yell because mod bad :MADeline:
                            if (yieldReturnIssue) {
                                String message = ":warning: The mod called **" + modName + "** uses `yield return orig(self)`!" +
                                        " This is illegal <:landeline:458158726558384149>\n:arrow_right: https://gamebanana.com/"
                                        + mod.get("GameBananaType").toString().toLowerCase() + "s/" + mod.get("GameBananaId");
                                sendAlertToWebhook(message);
                                messages.add(message);
                            }
                            if (intPtrIssue) {
                                String message = ":warning: The mod called **" + modName + "** might be using the `IntPtr` trick to call base methods!" +
                                        " This is illegal <:landeline:458158726558384149>\n:arrow_right: https://gamebanana.com/"
                                        + mod.get("GameBananaType").toString().toLowerCase() + "s/" + mod.get("GameBananaId");
                                sendAlertToWebhook(message);
                                messages.add(message);
                            }
                            if (readonlyStructIssue) {
                                String message = ":warning: The mod called **" + modName + "** is using a `readonly struct`!" +
                                        " This is illegal <:landeline:458158726558384149>\n:arrow_right: https://gamebanana.com/"
                                        + mod.get("GameBananaType").toString().toLowerCase() + "s/" + mod.get("GameBananaId");
                                sendAlertToWebhook(message);
                                messages.add(message);
                            }

                            newResults.badFiles.put(fileName, messages);
                        }
                    } catch (ZipException e) {
                        logger.warn("Error while reading zip. Adding to the whitelist so that it isn't retried.", e);
                        newResults.goodFiles.add(fileName);

                        // send an angry ping to the owner to have the mod manually checked
                        WebhookExecutor.executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK,
                                "https://cdn.discordapp.com/avatars/793432836912578570/0a3f716e15c8c3adca6c461c2d64553e.png?size=128",
                                "Banana Watch",
                                "<@" + SecretConstants.OWNER_ID + "> The mod called **" + modName + "** could not be checked. Please check it manually.\n" +
                                        ":arrow_right: https://gamebanana.com/" + mod.get("GameBananaType").toString().toLowerCase() + "s/" + mod.get("GameBananaId"),
                                SecretConstants.OWNER_ID);
                    }

                    logger.debug("Deleting temporary ZIP");
                    FileUtils.forceDelete(new File("/tmp/mod_yield_police.zip"));
                }
            }
        }

        try (FileWriter writer = new FileWriter("gamebanana_check_results_list.yaml")) {
            new Yaml().dump(newResults.toMap(), writer);
        }
    }

    /**
     * Goes across all the zips that are more recent than Crowd Control (502895)
     * and reports all mods that ship with a file that also ships with Celeste or Everest.
     * (That arbitrary limit is here because that rule is not retroactive.)
     */
    public static void checkForForbiddenFiles() throws IOException {
        // load mod list
        List<String> mods;
        try (InputStream is = new FileInputStream("modfilesdatabase/list.yaml")) {
            mods = new Yaml().load(is);
        }

        for (String mod : mods) {
            scanModFileListings(mod);
        }
    }

    private static void scanModFileListings(String mod) throws IOException {
        // load file list for the mod
        String modName;
        List<String> files;
        try (InputStream is = new FileInputStream("modfilesdatabase/" + mod + "/info.yaml")) {
            Map<String, Object> info = new Yaml().load(is);
            modName = info.get("Name").toString();
            files = (List<String>) info.get("Files");
        }

        for (String file : files) {
            // check for forbidden files in any file more recent than Crowd Control (653181)
            if (Integer.parseInt(file) > 653181) {
                List<String> contents;
                try (InputStream is = new FileInputStream("modfilesdatabase/" + mod + "/" + file + ".yaml")) {
                    contents = new Yaml().load(is);
                }

                for (String entry : contents) {
                    for (String illegalFile : BAD_FILE_LIST) {
                        if (entry.equalsIgnoreCase(illegalFile) || entry.toLowerCase(Locale.ROOT).endsWith("/" + illegalFile.toLowerCase(Locale.ROOT))) {
                            // this file is illegal!
                            String nameForUrl = mod.split("/")[0].toLowerCase(Locale.ROOT) + "s/" + mod.split("/")[1];
                            sendAlertToWebhook(":warning: The mod called **" + modName + "** contains a file called `" + illegalFile + "`! " +
                                    "This is illegal <:landeline:458158726558384149>\n:arrow_right: https://gamebanana.com/" + nameForUrl);
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks for any mod that is blacklisted due to having the same ID as another file... that does not belong to the same mod.
     * This allows to catch if two different mods use the same ID.
     * <p>
     * Mods that are marked as Obsolete are excluded from the alerts.
     */
    public static void checkForDuplicateModIds() throws IOException {
        Map<String, String> excludedFilesList;
        try (InputStream is = new FileInputStream("uploads/everestupdateexcluded.yaml")) {
            excludedFilesList = new Yaml().load(is);
        }

        Set<Pair<String, String>> alreadyFoundConflicts = new HashSet<>();

        for (Map.Entry<String, String> excludedFilesListEntry : excludedFilesList.entrySet()) {
            Matcher descriptionMatcher = gamebananaLinkRegex.matcher(excludedFilesListEntry.getValue());
            if (descriptionMatcher.matches()) {
                String gbLink = descriptionMatcher.group(1);

                String fileId1 = excludedFilesListEntry.getKey().substring("https://gamebanana.com/mmdl/".length());
                String fileId2 = gbLink.substring("https://gamebanana.com/mmdl/".length());

                // this method is quite inefficient (we are reading files from disk in a loop),
                // but RAM is more of a constraint than time here...
                Pair<String, String> mod1 = whichModDoesFileBelongTo(fileId1);
                Pair<String, String> mod2 = whichModDoesFileBelongTo(fileId2);

                if (!mod1.getValue().equals(mod2.getValue()) && !alreadyFoundConflicts.contains(Pair.of(mod1.getValue(), mod2.getValue()))) {
                    // both files belong to different mods! this is fishy.
                    String nameForUrl1 = mod1.getValue().split("/")[0].toLowerCase(Locale.ROOT) + "s/" + mod1.getValue().split("/")[1];
                    String nameForUrl2 = mod2.getValue().split("/")[0].toLowerCase(Locale.ROOT) + "s/" + mod2.getValue().split("/")[1];

                    if (!modIsObsolete(mod1.getValue()) && !modIsObsolete(mod2.getValue())) {
                        sendAlertToWebhook(":warning: Mods **" + mod1.getKey() + "** and **" + mod2.getKey() + "** seem to be using the same mod ID! " +
                                "This is illegal <:landeline:458158726558384149>\n:arrow_right: " +
                                "https://gamebanana.com/" + nameForUrl1 + " and https://gamebanana.com/" + nameForUrl2);
                    } else {
                        logger.info(mod1.getValue() + " and " + mod2.getValue() + " use the same ID, but alert will not be sent" +
                                " because at least one of them is obsolete.");
                    }

                    // avoid warning about this conflict again.
                    alreadyFoundConflicts.add(Pair.of(mod1.getValue(), mod2.getValue()));
                    alreadyFoundConflicts.add(Pair.of(mod2.getValue(), mod1.getValue()));
                }
            }
        }
    }

    /**
     * Calls GameBanana to check if the given mod is obsolete.
     * If GameBanana is down, returns false (so that an alert gets sent no matter what).
     *
     * @param mod The mod to check, in the format used in modfilesdatabase/list.yaml (for example "Mod/53678")
     * @return Whether the mod is obsolete or not (false if GameBanana is unreachable)
     */
    private static boolean modIsObsolete(String mod) {
        try {
            return ConnectionUtils.runWithRetry(() -> {
                try (InputStream is = ConnectionUtils.openStreamWithTimeout(new URL("https://gamebanana.com/apiv7/" + mod + "?_csvProperties=_bIsObsolete"))) {
                    JSONObject modInfo = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                    return modInfo.getBoolean("_bIsObsolete");
                }
            });
        } catch (IOException e) {
            logger.error("Cannot get whether {} is obsolete or not, so we will assume it is not.", mod, e);
            return false;
        }
    }

    static Pair<String, String> whichModDoesFileBelongTo(String fileId) throws IOException {
        // load mod list
        List<String> mods = ConnectionUtils.runWithRetry(() -> {
            try (InputStream is = new FileInputStream("modfilesdatabase/list.yaml")) {
                return new Yaml().load(is);
            }
        });

        for (String mod : mods) {
            // load file list for the mod
            String modName;
            List<String> files;
            Map<String, Object> info = ConnectionUtils.runWithRetry(() -> {
                try (InputStream is = new FileInputStream("modfilesdatabase/" + mod + "/info.yaml")) {
                    return new Yaml().load(is);
                }
            });

            modName = info.get("Name").toString();
            files = (List<String>) info.get("Files");

            if (files.contains(fileId)) {
                return Pair.of(modName, mod);
            }
        }

        return null;
    }

    public static void checkAllModsWithEverestYamlValidator() throws IOException {
        List<String> oldAlreadyChecked;
        List<String> newAlreadyChecked = new ArrayList<>();
        try (InputStream is = new FileInputStream("already_validated_yaml_files.yaml")) {
            oldAlreadyChecked = new Yaml().load(is);
        }

        Map<String, Map<String, Object>> updaterDatabase;
        try (InputStream is = new FileInputStream("uploads/everestupdate.yaml")) {
            updaterDatabase = new Yaml().load(is);
        }

        for (Map.Entry<String, Map<String, Object>> modMap : updaterDatabase.entrySet()) {
            String modName = modMap.getKey();
            String url = modMap.getValue().get(com.max480.everest.updatechecker.Main.serverConfig.mainServerIsMirror ? "URL" : "MirrorURL").toString();
            if (!oldAlreadyChecked.contains(url)) {
                logger.debug("Downloading {} ({}) for everest.yaml checking", url, modName);
                try (InputStream is = ConnectionUtils.openStreamWithTimeout(new URL(url))) {
                    FileUtils.copyToFile(is, new File("/tmp/everest_yaml_police.zip"));
                }

                try (ZipFile zip = new ZipFile(new File("/tmp/everest_yaml_police.zip"))) {
                    // find the everest.yaml name used in this mod.
                    ZipEntry yaml = zip.getEntry("everest.yaml");
                    if (yaml == null) {
                        yaml = zip.getEntry("everest.yml");
                    }
                    if (yaml == null) {
                        yaml = zip.getEntry("multimetadata.yaml");
                    }

                    logger.debug("Extracting {}", yaml.getName());
                    Path destination = Paths.get("/tmp", yaml.getName());
                    try (InputStream is = zip.getInputStream(yaml)) {
                        FileUtils.copyToFile(is, destination.toFile());
                    }

                    logger.debug("Sending to validator");
                    HttpPostMultipart submit = new HttpPostMultipart("https://max480-random-stuff.appspot.com/celeste/everest-yaml-validator", "UTF-8", new HashMap<>());
                    submit.addFilePart("file", destination.toFile());
                    HttpURLConnection result = submit.finish();

                    logger.debug("Deleting temp file");
                    Files.delete(destination);

                    String resultBody = IOUtils.toString(result.getInputStream(), StandardCharsets.UTF_8);
                    logger.debug("Checking result");
                    if (!resultBody.contains("Your everest.yaml file seems valid!")) {
                        // this doesn't sound good...
                        String resultHtml = Jsoup.parse(resultBody).select(".alert").html();

                        // turn <pre> blocks into <code> blocks for proper formatting, and remove the extended explanations for everest.yaml structure
                        // (because it is formatted wrong and that people we're sending that to already know how everest.yamls are formatted :p)
                        resultHtml = resultHtml.replace("<pre>", "<code>").replace("</pre>", "</code>");
                        if (resultHtml.contains("<p class=\"error-description\">")) {
                            resultHtml = resultHtml.substring(0, resultHtml.indexOf("<p class=\"error-description\">"));
                        }

                        String resultMd = new CopyDown(markdownOptions).convert(resultHtml);

                        // clean up the output a bit to make it sound less weird
                        resultMd = resultMd
                                .replace("Your everest.yaml", "This mod's everest.yaml")
                                .replace("your everest.yaml", "this mod's everest.yaml")
                                .replace("-   ", "- ");

                        while (resultMd.contains("\n\n")) {
                            resultMd = resultMd.replace("\n\n", "\n");
                        }
                        if (resultMd.length() > 1000) {
                            resultMd = resultMd.substring(0, 1000) + "...";
                        }
                        sendAlertToWebhook(":warning: The mod called **" + modName + "** doesn't pass the everest.yaml validator.\n" +
                                resultMd + "\n:arrow_right: https://gamebanana.com/"
                                + modMap.getValue().get("GameBananaType").toString().toLowerCase(Locale.ROOT) + "s/" + modMap.getValue().get("GameBananaId"));
                    }
                }

                logger.debug("Deleting temporary ZIP");
                FileUtils.forceDelete(new File("/tmp/everest_yaml_police.zip"));
            }

            newAlreadyChecked.add(url);
        }

        try (FileWriter writer = new FileWriter("already_validated_yaml_files.yaml")) {
            new Yaml().dump(newAlreadyChecked, writer);
        }
    }

    public static void checkForFilesBelongingToMultipleMods() throws IOException {
        Map<String, Map<String, Object>> updaterDatabase;
        try (InputStream is = new FileInputStream("uploads/everestupdate.yaml")) {
            updaterDatabase = new Yaml().load(is);
        }

        Set<String> filesWeAlreadyWarnedAbout = new HashSet<>();

        for (Map.Entry<String, Map<String, Object>> modMap : updaterDatabase.entrySet()) {
            String url = modMap.getValue().get(com.max480.everest.updatechecker.Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL").toString();

            // if a URL is present twice, we are going to encounter it twice, but we still want to warn about it only once.
            if (filesWeAlreadyWarnedAbout.contains(url)) {
                continue;
            }

            // go through the database again to find all mods that have the same URL (including the mod we are checking itself)
            List<String> modNames = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> otherModMap : updaterDatabase.entrySet()) {
                String otherUrl = otherModMap.getValue().get(com.max480.everest.updatechecker.Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL").toString();

                if (otherUrl.equals(url)) {
                    modNames.add(otherModMap.getKey());
                }
            }

            if (modNames.size() > 1) {
                // we found a URL associated with 2 or more mods!
                sendAlertToWebhook(":warning: Mods **" + String.join("**, **", modNames) + "** are all associated to file " + url + ".\n" +
                        "This means this file contains multiple mods, which can cause weirdness when it gets updated (multiple entries for the same file appearing in the updater).\n" +
                        "If having multiple mods cannot be avoided, one of them should be updater-blacklisted by max480.");

                filesWeAlreadyWarnedAbout.add(url);
            }
        }
    }

    public static void checkUnapprovedCategories() throws IOException {
        checkUnapprovedCategoriesFor("Mod");
        checkUnapprovedCategoriesFor("Tool");
        checkUnapprovedCategoriesFor("Request");
        checkUnapprovedCategoriesFor("Wip");
    }

    private static void checkUnapprovedCategoriesFor(String name) throws IOException {
        // "unapproved categories" are categories that definitely exist, where people can add mods...
        // but that don't appear in the list when you just browse the Mods section because that requires admin approval.
        // so they're categories that exist... but don't exist. This makes no sense and that's why it needs fixing.

        JSONArray listOfCategories = ConnectionUtils.runWithRetry(() -> {
            try (InputStream is = ConnectionUtils.openStreamWithTimeout(new URL("https://gamebanana.com/apiv7/" + name + "Category/ByGame?_aGameRowIds[]=6460&" +
                    "_csvProperties=_idRow,_idParentCategoryRow&_sOrderBy=_idRow,ASC&_nPage=1&_nPerpage=50"))) {

                return new JSONArray(IOUtils.toString(is, UTF_8));
            }
        });

        // first, let's get the categories that exist in the list.
        Set<Integer> categoriesThatExist = new HashSet<>();
        Set<Integer> parentCategoriesThatExist = new HashSet<>();

        for (int i = 0; i < listOfCategories.length(); i++) {
            categoriesThatExist.add(listOfCategories.getJSONObject(i).getInt("_idRow"));
            parentCategoriesThatExist.add(listOfCategories.getJSONObject(i).getInt("_idParentCategoryRow"));
        }
        parentCategoriesThatExist.remove(0); // this means "no parent category"

        // take the existing parent categories, remove the existing categories from it...
        // and what you have left is parent categories that exist, but don't exist.
        Set<Integer> categoriesThatExistButDont = new HashSet<>(parentCategoriesThatExist);
        categoriesThatExistButDont.removeAll(categoriesThatExist);

        // now we want to go through all mods on GameBanana to check their categories.
        int page = 1;
        while (true) {
            // load a page of mods.
            final int thisPage = page;
            JSONArray pageContents = ConnectionUtils.runWithRetry(() -> {
                try (InputStream is = ConnectionUtils.openStreamWithTimeout(new URL("https://gamebanana.com/apiv7/" + name + "/ByGame?_aGameRowIds[]=6460&" +
                        "_csvProperties=_aCategory&_sOrderBy=_idRow,ASC&_nPage=" + thisPage + "&_nPerpage=50"))) {

                    return new JSONArray(IOUtils.toString(is, UTF_8));
                }
            });

            // check their categories
            for (int i = 0; i < pageContents.length(); i++) {
                int category = pageContents.getJSONObject(i).getJSONObject("_aCategory").getInt("_idRow");
                if (!categoriesThatExist.contains(category)) {
                    // the category exists since the mod is in it, but doesn't since it doesn't appear in the list. :p
                    categoriesThatExistButDont.add(category);
                }
            }

            // if we just got an empty page, this means we reached the end of the list!
            if (pageContents.isEmpty()) {
                break;
            }

            // otherwise, go on.
            page++;
        }

        for (int category : categoriesThatExistButDont) {
            sendAlertToWebhook(":warning: The category at <https://gamebanana.com/" + name.toLowerCase(Locale.ROOT) + "s/cats/" + category + "> does not seem to be approved by site admins!\n" +
                    "This means it will not appear in the categories list (neither in Olympus nor on GameBanana itself).");
        }
    }

    private static void sendAlertToWebhook(String message) throws IOException {
        for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
            WebhookExecutor.executeWebhook(webhook,
                    "https://cdn.discordapp.com/avatars/793432836912578570/0a3f716e15c8c3adca6c461c2d64553e.png?size=128",
                    "Banana Watch",
                    message,
                    ImmutableMap.of("X-Everest-Log", "true"));
        }
    }
}
