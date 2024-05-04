package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.Main;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.everest.updatechecker.ZipFileWithAutoEncoding;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.HttpPostMultipart;
import ovh.maddie480.randomstuff.backend.utils.OutputStreamLogger;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * This class is intended to be run periodically to check mods on GameBanana for issues.
 * In Maddie's bot setup, all public methods are run every day.
 */
public class GameBananaAutomatedChecks {
    private static final Logger logger = LoggerFactory.getLogger(GameBananaAutomatedChecks.class);

    // files that should trigger a warning when present in a mod (files that ship with Celeste or Everest)
    private static final List<String> BAD_FILE_LIST = Arrays.asList("Celeste.exe",
            "CSteamworks.dll", "Celeste.Mod.mm.dll", "DotNetZip.dll", "FNA.dll", "I18N.CJK.dll", "I18N.MidEast.dll",
            "I18N.Other.dll", "I18N.Rare.dll", "I18N.West.dll", "I18N.dll", "Jdenticon.dll", "KeraLua.dll", "MMHOOK_Celeste.dll", "MojoShader.dll",
            "Mono.Cecil.Mdb.dll", "Mono.Cecil.Pdb.dll", "Mono.Cecil.Rocks.dll", "Mono.Cecil.dll", "MonoMod.RuntimeDetour.dll", "MonoMod.Utils.dll", "NLua.dll",
            "Newtonsoft.Json.dll", "SDL2.dll", "SDL2_image.dll", "Steamworks.NET.dll", "YamlDotNet.dll", "discord-rpc.dll", "fmod.dll", "fmodstudio.dll",
            "libEGL.dll", "libGLESv2.dll", "libjpeg-9.dll", "libpng16-16.dll", "lua53.dll", "steam_api.dll", "zlib1.dll", "Microsoft.Xna.Framework.dll",
            "Microsoft.Xna.Framework.Game.dll", "Microsoft.Xna.Framework.Graphics.dll");

    private static final Pattern gamebananaLinkRegex = Pattern.compile(".*(https://gamebanana.com/mmdl/[0-9]+).*");
    private static final Pattern objDirectoryRegex = Pattern.compile(".*(?:/|^)obj/(Debug|Release)(?:/|$).*");

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
        List<String> newResults = new ArrayList<>();

        // and we want to load the previous state to be sure we don't handle already handled mods.
        List<String> oldResults;
        try (InputStream is = new FileInputStream("already_validated_dll_files.yaml")) {
            oldResults = YamlUtil.load(is);
        }

        // download the updater database to figure out which mods we should scan...
        Map<String, Map<String, Object>> updaterDatabase;
        try (InputStream is = new FileInputStream("uploads/everestupdate.yaml")) {
            updaterDatabase = YamlUtil.load(is);
        }

        for (Map.Entry<String, Map<String, Object>> modEntry : updaterDatabase.entrySet()) {
            String modName = modEntry.getKey();
            Map<String, Object> mod = modEntry.getValue();

            // does the file have a dll?
            String fileName = mod.get(Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL")
                    .toString().substring("https://gamebanana.com/mmdl/".length());

            if (oldResults.contains(fileName)) {
                // skip scanning already scanned files.
                newResults.add(fileName);
            } else {
                // check file listing
                List<String> fileList;
                try (InputStream is = new FileInputStream(
                        "modfilesdatabase/" + mod.get("GameBananaType") + "/" + mod.get("GameBananaId") + "/" + fileName + ".yaml")) {

                    fileList = YamlUtil.load(is);
                }

                if (fileList.stream().anyMatch(file -> file.toLowerCase(Locale.ROOT).endsWith(".dll"))) {
                    // file listing contains dll, so download!
                    logger.debug("Downloading mod {} (file id {})", modName, fileName);

                    try (InputStream is = ConnectionUtils.openStreamWithTimeout(mod.get(Main.serverConfig.mainServerIsMirror ? "URL" : "MirrorURL").toString())) {
                        FileUtils.copyToFile(is, new File("/tmp/mod_yield_police.zip"));
                    }

                    logger.debug("Searching for DLL");

                    try (ZipFile zip = ZipFileWithAutoEncoding.open("/tmp/mod_yield_police.zip")) {
                        // find the everest.yaml name used in this mod.
                        ZipEntry yaml = zip.getEntry("everest.yaml");
                        if (yaml == null) {
                            yaml = zip.getEntry("everest.yml");
                        }

                        // read everest.yaml without extracting
                        List<Map<String, Object>> yamlContent;
                        try (InputStream is = zip.getInputStream(yaml)) {
                            yamlContent = YamlUtil.load(is);
                        }

                        boolean yieldReturnIssue = false;
                        boolean intPtrIssue = false;
                        boolean consoleWriteLine = false;
                        boolean dllEntryFoundInYaml = false;

                        // read "DLL" fields for each everest.yaml entry
                        for (Map<String, Object> yamlEntry : yamlContent) {
                            Object dllPath = yamlEntry.get("DLL");
                            if (dllPath == null) {
                                logger.info("Mod actually has no DLL, skipping");
                            } else {
                                dllEntryFoundInYaml = true;
                                ZipEntry entry = zip.getEntry(dllPath.toString());

                                if (entry == null) {
                                    logger.info("The DLL specified in the yaml file \"{}\" does not exist! Skipping.", dllPath);
                                } else {
                                    logger.debug("Extracting DLL from {}", dllPath);

                                    try (InputStream is = zip.getInputStream(entry)) {
                                        FileUtils.copyToFile(is, new File("/tmp/mod_yield_police.dll"));
                                    }

                                    // invoke ilspycmd to decompile the mod.
                                    logger.debug("Decompiling DLL...");
                                    Process p = OutputStreamLogger.redirectErrorOutput(logger,
                                            new ProcessBuilder("/home/debian/.dotnet/tools/ilspycmd", "/tmp/mod_yield_police.dll").start());

                                    String fullDecompile;
                                    try (InputStream is = p.getInputStream()) {
                                        fullDecompile = IOUtils.toString(is, StandardCharsets.UTF_8);
                                    }

                                    try {
                                        p.waitFor();
                                    } catch (InterruptedException e) {
                                        throw new IOException(e);
                                    }

                                    if (p.exitValue() != 0) {
                                        throw new IOException("ilspycmd returned exit code " + p.exitValue());
                                    }

                                    logger.debug("Decompiled {} lines of code",
                                            fullDecompile.chars().filter(c -> c == '\n').count());

                                    // search for anything looking like yield return orig(self)
                                    if (fullDecompile.contains("yield return orig.Invoke")) {
                                        logger.warn("Mod {} uses yield return orig(self)!", modName);
                                        yieldReturnIssue = true;
                                    }
                                    if (fullDecompile.contains(".MethodHandle.GetFunctionPointer()")) {
                                        logger.warn("Mod {} might be using the IntPtr trick", modName);
                                        intPtrIssue = true;
                                    }
                                    if (fullDecompile.contains("Console.WriteLine")) {
                                        logger.warn("Mod {} contains Console.WriteLine", modName);
                                        consoleWriteLine = true;
                                    }

                                    logger.debug("Deleting temporary DLL");
                                    FileUtils.forceDelete(new File("/tmp/mod_yield_police.dll"));
                                }
                            }
                        }

                        newResults.add(fileName);

                        if (yieldReturnIssue) {
                            sendAlertToWebhook(":warning: The mod called **" + modName + "** uses `yield return orig(self)`!" +
                                    " This is illegal <:landeline:458158726558384149>\n:arrow_right: https://gamebanana.com/"
                                    + mod.get("GameBananaType").toString().toLowerCase() + "s/" + mod.get("GameBananaId"));
                        }

                        if (intPtrIssue) {
                            sendAlertToWebhook(":warning: The mod called **" + modName + "** might be using the `IntPtr` trick to call base methods!" +
                                    " This is illegal <:landeline:458158726558384149>\n:arrow_right: https://gamebanana.com/"
                                    + mod.get("GameBananaType").toString().toLowerCase() + "s/" + mod.get("GameBananaId"));
                        }

                        if (consoleWriteLine) {
                            sendAlertToWebhook(":warning: The mod called **" + modName + "** uses `Console.WriteLine`!" +
                                    " This is illegal <:landeline:458158726558384149>\n:arrow_right: https://gamebanana.com/"
                                    + mod.get("GameBananaType").toString().toLowerCase() + "s/" + mod.get("GameBananaId"));
                        }

                        if (!dllEntryFoundInYaml) {
                            sendAlertToWebhook(":warning: The mod called **" + modName + "** ships with DLLs, but does not refer to any in its everest.yaml." +
                                    " This is suspicious <:thinkeline:410534291462815745>\n:arrow_right: https://gamebanana.com/"
                                    + mod.get("GameBananaType").toString().toLowerCase() + "s/" + mod.get("GameBananaId"));
                        }
                    } catch (ZipException e) {
                        logger.warn("Error while reading zip. Adding to the whitelist so that it isn't retried.", e);
                        newResults.add(fileName);

                        // send an angry ping to the owner to have the mod manually checked
                        WebhookExecutor.executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK,
                                "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/gamebanana.png",
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

        try (OutputStream os = new FileOutputStream("already_validated_dll_files.yaml")) {
            YamlUtil.dump(newResults, os);
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
            mods = YamlUtil.load(is);
        }

        // the new file list is built from scratch (only files that still exist are copied over from the previous list).
        List<String> alreadyCheckedNew = new ArrayList<>();

        // and we want to load the previous state to be sure we don't handle already handled mods.
        List<String> alreadyCheckedOld;
        try (InputStream is = new FileInputStream("already_checked_for_illegal_files.yaml")) {
            alreadyCheckedOld = YamlUtil.load(is);
        }

        for (String mod : mods) {
            scanModFileListings(mod, alreadyCheckedOld, alreadyCheckedNew);
        }

        try (OutputStream os = new FileOutputStream("already_checked_for_illegal_files.yaml")) {
            YamlUtil.dump(alreadyCheckedNew, os);
        }
    }

    private static void scanModFileListings(String mod, List<String> alreadyCheckedOld, List<String> alreadyCheckedNew) throws IOException {
        // load file list for the mod
        String modName;
        List<String> files;
        try (InputStream is = new FileInputStream("modfilesdatabase/" + mod + "/info.yaml")) {
            Map<String, Object> info = YamlUtil.load(is);
            modName = info.get("Name").toString();
            files = (List<String>) info.get("Files");
        }

        for (String file : files) {
            // check for forbidden files if not already done
            alreadyCheckedNew.add(file);
            if (!alreadyCheckedOld.contains(file)) {
                logger.debug("Checking for illegal files in file {} of {}...", file, mod);

                List<String> contents;
                try (InputStream is = new FileInputStream("modfilesdatabase/" + mod + "/" + file + ".yaml")) {
                    contents = YamlUtil.load(is);
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

                    Matcher objDirectoryMatcher = objDirectoryRegex.matcher(entry);
                    if (objDirectoryMatcher.matches()) {
                        String nameForUrl = mod.split("/")[0].toLowerCase(Locale.ROOT) + "s/" + mod.split("/")[1];
                        sendAlertToWebhook(":warning: The mod called **" + modName + "** contains an `obj/" + objDirectoryMatcher.group(1) + "` folder! " +
                                "This is illegal <:landeline:458158726558384149>\n:arrow_right: https://gamebanana.com/" + nameForUrl);
                        return;
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
            excludedFilesList = YamlUtil.load(is);
        }

        // the new file list is built from scratch (only files that still exist are copied over from the previous list).
        List<String> alreadyCheckedNew = new ArrayList<>();

        // and we want to load the previous state to be sure we don't handle already handled mods.
        List<String> alreadyCheckedOld;
        try (InputStream is = new FileInputStream("already_checked_for_duplicates.yaml")) {
            alreadyCheckedOld = YamlUtil.load(is);
        }

        Set<Pair<String, String>> alreadyFoundConflicts = new HashSet<>();

        for (Map.Entry<String, String> excludedFilesListEntry : excludedFilesList.entrySet()) {
            alreadyCheckedNew.add(excludedFilesListEntry.getKey());
            if (alreadyCheckedOld.contains(excludedFilesListEntry.getKey())) {
                continue;
            }

            Matcher descriptionMatcher = gamebananaLinkRegex.matcher(excludedFilesListEntry.getValue());
            if (descriptionMatcher.matches()) {
                String gbLink = descriptionMatcher.group(1);

                String fileId1 = excludedFilesListEntry.getKey().substring("https://gamebanana.com/mmdl/".length());
                String fileId2 = gbLink.substring("https://gamebanana.com/mmdl/".length());

                logger.debug("Checking whether {} and {} belong to different mods...", fileId1, fileId2);

                // this method is quite inefficient (we are reading files from disk in a loop),
                // but RAM is more of a constraint than time here...
                Pair<String, String> mod1 = whichModDoesFileBelongTo(fileId1);
                Pair<String, String> mod2 = whichModDoesFileBelongTo(fileId2);

                if (!mod1.getValue().equals(mod2.getValue()) && !alreadyFoundConflicts.contains(Pair.of(mod1.getValue(), mod2.getValue()))) {
                    // both files belong to different mods! this is fishy.
                    String nameForUrl1 = mod1.getValue().split("/")[0].toLowerCase(Locale.ROOT) + "s/" + mod1.getValue().split("/")[1];
                    String nameForUrl2 = mod2.getValue().split("/")[0].toLowerCase(Locale.ROOT) + "s/" + mod2.getValue().split("/")[1];

                    // if one of the mods is a wip, check if they are linked.
                    boolean modAndWipMatch = false;
                    if (mod1.getValue().startsWith("Wip/") && !mod2.getValue().startsWith("Wip/")) {
                        modAndWipMatch = modAndWipAreLinked(mod2.getValue(), mod1.getValue());
                    } else if (!mod1.getValue().startsWith("Wip/") && mod2.getValue().startsWith("Wip/")) {
                        modAndWipMatch = modAndWipAreLinked(mod1.getValue(), mod2.getValue());
                    }

                    if (!modAndWipMatch && !modIsObsolete(mod1.getValue()) && !modIsObsolete(mod2.getValue())) {
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
            } else {
                logger.debug("No link found in description of excluded file list entry {} ('{}')", excludedFilesListEntry.getKey(), excludedFilesListEntry.getValue());
            }
        }

        try (OutputStream os = new FileOutputStream("already_checked_for_duplicates.yaml")) {
            YamlUtil.dump(alreadyCheckedNew, os);
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
                try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://gamebanana.com/apiv8/" + mod + "?_csvProperties=_bIsObsolete")) {
                    JSONObject modInfo = new JSONObject(new JSONTokener(is));
                    return modInfo.getBoolean("_bIsObsolete");
                }
            });
        } catch (IOException e) {
            logger.error("Cannot get whether {} is obsolete or not, so we will assume it is not.", mod, e);
            return false;
        }
    }

    private static boolean modAndWipAreLinked(String mod, String wip) {
        try {
            return ConnectionUtils.runWithRetry(() -> {
                try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://gamebanana.com/apiv8/" + mod + "?_csvProperties=_aWip")) {
                    JSONObject modInfo = new JSONObject(new JSONTokener(is));

                    // check that the mod has a linked wip, and that it is the wip we were given
                    return !modInfo.isNull("_aWip") && wip.equals("Wip/" + modInfo.getJSONObject("_aWip").getInt("_idRow"));
                }
            });
        } catch (IOException e) {
            logger.error("Cannot get whether {} is linked to {}, so we will assume it is not.", mod, wip, e);
            return false;
        }
    }

    static Pair<String, String> whichModDoesFileBelongTo(String fileId) throws IOException {
        for (String itemtype : new File("modfilesdatabase").list()) {
            if (!new File("modfilesdatabase/" + itemtype).isDirectory()) continue;

            for (String itemid : new File("modfilesdatabase/" + itemtype).list()) {
                if (new File("modfilesdatabase/" + itemtype + "/" + itemid + "/" + fileId + ".yaml").exists()) {
                    String modName;
                    try (InputStream is = new FileInputStream("modfilesdatabase/" + itemtype + "/" + itemid + "/info.yaml")) {
                        Map<String, Object> info = YamlUtil.load(is);
                        modName = info.get("Name").toString();
                    }
                    return Pair.of(modName, itemtype + "/" + itemid);
                }
            }
        }

        return null;
    }

    public static void checkDuplicateModIdsCaseInsensitive() throws IOException {
        Path alreadyReportedStorage = Paths.get("already_reported_duplicates.yaml");

        List<List<String>> oldDuplicateList;
        List<List<String>> newDuplicateList = new ArrayList<>();
        try (InputStream is = Files.newInputStream(alreadyReportedStorage)) {
            oldDuplicateList = YamlUtil.load(is);
        }

        Map<String, Map<String, Object>> everestUpdate;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/everest_update.yaml")) {
            everestUpdate = YamlUtil.load(is);
        }

        for (String name1 : everestUpdate.keySet()) {
            for (String name2 : everestUpdate.keySet()) {
                if (!name1.equals(name2) && name1.equalsIgnoreCase(name2)) {
                    // :landeline: those are case-insensitive duplicates!
                    List<String> pair = new ArrayList<>(Arrays.asList(name1, name2));
                    pair.sort(Comparator.naturalOrder());

                    if (!oldDuplicateList.contains(pair) && !newDuplicateList.contains(pair)) {
                        sendAlertToWebhook(":warning: Mods " +
                                "https://gamebanana.com/" + everestUpdate.get(name1).get("GameBananaType").toString().toLowerCase() + "s/" + everestUpdate.get(name1).get("GameBananaId") +
                                " (**" + name1 + "**) and " +
                                "https://gamebanana.com/" + everestUpdate.get(name2).get("GameBananaType").toString().toLowerCase() + "s/" + everestUpdate.get(name2).get("GameBananaId") +
                                " (**" + name2 + "**) have the same mod ID with different cases.\nThis will cause them to overwrite each other when downloading both on Windows!"
                        );
                    }
                    newDuplicateList.add(pair);
                }
            }
        }

        try (OutputStream os = Files.newOutputStream(alreadyReportedStorage)) {
            YamlUtil.dump(newDuplicateList, os);
        }
    }

    public static void checkAllModsWithEverestYamlValidator() throws IOException {
        List<String> oldAlreadyChecked;
        List<String> newAlreadyChecked = new ArrayList<>();
        try (InputStream is = new FileInputStream("already_validated_yaml_files.yaml")) {
            oldAlreadyChecked = YamlUtil.load(is);
        }

        Map<String, Map<String, Object>> updaterDatabase;
        try (InputStream is = new FileInputStream("uploads/everestupdate.yaml")) {
            updaterDatabase = YamlUtil.load(is);
        }

        for (Map.Entry<String, Map<String, Object>> modMap : updaterDatabase.entrySet()) {
            String modName = modMap.getKey();
            String url = modMap.getValue().get(Main.serverConfig.mainServerIsMirror ? "URL" : "MirrorURL").toString();
            if (!oldAlreadyChecked.contains(url)) {
                logger.debug("Downloading {} ({}) for everest.yaml checking", url, modName);
                try (InputStream is = ConnectionUtils.openStreamWithTimeout(url)) {
                    FileUtils.copyToFile(is, new File("/tmp/everest_yaml_police.zip"));
                }

                try (ZipFile zip = ZipFileWithAutoEncoding.open("/tmp/everest_yaml_police.zip")) {
                    // find the everest.yaml name used in this mod.
                    ZipEntry yaml = zip.getEntry("everest.yaml");
                    if (yaml == null) {
                        yaml = zip.getEntry("everest.yml");
                    }

                    logger.debug("Extracting {}", yaml.getName());
                    Path destination = Paths.get("/tmp", yaml.getName());
                    try (InputStream is = zip.getInputStream(yaml)) {
                        FileUtils.copyToFile(is, destination.toFile());
                    }

                    logger.debug("Sending to validator");
                    HttpPostMultipart submit = new HttpPostMultipart("https://maddie480.ovh/celeste/everest-yaml-validator", "UTF-8", new HashMap<>());
                    submit.addFilePart("file", destination.toFile());
                    submit.addFormField("outputFormat", "json");
                    HttpURLConnection result = submit.finish();

                    JSONObject resultBody;
                    try (InputStream is = ConnectionUtils.connectionToInputStream(result)) {
                        resultBody = new JSONObject(new JSONTokener(is));
                    }

                    logger.debug("Checking result");
                    if (resultBody.has("parseError")) {
                        sendAlertToWebhook(":warning: The mod called **" + modName + "** has an everest.yaml file with invalid syntax:\n```\n"
                                + resultBody.getString("parseError")
                                + "\n```\n:arrow_right: https://gamebanana.com/"
                                + modMap.getValue().get("GameBananaType").toString().toLowerCase(Locale.ROOT) + "s/" + modMap.getValue().get("GameBananaId"));
                    } else if (resultBody.has("validationErrors")) {
                        List<String> allErrors = new ArrayList<>();
                        for (Object o : resultBody.getJSONArray("validationErrors")) {
                            allErrors.add((String) o);
                        }
                        sendAlertToWebhook(":warning: The mod called **" + modName + "** does not pass the everest.yaml validator:\n- "
                                + String.join("\n- ", allErrors)
                                + "\n:arrow_right: https://gamebanana.com/"
                                + modMap.getValue().get("GameBananaType").toString().toLowerCase(Locale.ROOT) + "s/" + modMap.getValue().get("GameBananaId"));
                    } else {
                        // let's check that it refers to DLLs that actually exist.
                        List<Map<String, Object>> yamlFile;
                        try (InputStream is = Files.newInputStream(destination)) {
                            yamlFile = YamlUtil.load(is);
                        }

                        boolean problem = false;
                        for (Map<String, Object> entry : yamlFile) {
                            if (entry.containsKey("DLL") && entry.get("DLL") != null) {
                                if (zip.getEntry(entry.get("DLL").toString()) == null) {
                                    logger.warn("File referred by DLL field {} does not exist in archive for mod {}!", entry.get("DLL"), modName);
                                    problem = true;
                                } else {
                                    logger.debug("File referred by DLL field {} exists", entry.get("DLL"));
                                }
                            }
                        }

                        if (problem) {
                            sendAlertToWebhook(":warning: The mod called **" + modName + "** has an everest.yaml file that refers to a DLL that does not exist." +
                                    " This is suspicious <:thinkeline:410534291462815745>\n:arrow_right: https://gamebanana.com/"
                                    + modMap.getValue().get("GameBananaType").toString().toLowerCase(Locale.ROOT) + "s/" + modMap.getValue().get("GameBananaId"));
                        }
                    }

                    logger.debug("Deleting temp file");
                    Files.delete(destination);
                }

                logger.debug("Deleting temporary ZIP");
                FileUtils.forceDelete(new File("/tmp/everest_yaml_police.zip"));
            }

            newAlreadyChecked.add(url);
        }

        try (OutputStream os = new FileOutputStream("already_validated_yaml_files.yaml")) {
            YamlUtil.dump(newAlreadyChecked, os);
        }
    }

    public static void checkForFilesBelongingToMultipleMods() throws IOException {
        Map<String, Map<String, Object>> updaterDatabase;
        try (InputStream is = new FileInputStream("uploads/everestupdate.yaml")) {
            updaterDatabase = YamlUtil.load(is);
        }

        List<String> oldAlreadyChecked;
        List<String> newAlreadyChecked = new ArrayList<>();
        try (InputStream is = new FileInputStream("already_checked_multiple_mods.yaml")) {
            oldAlreadyChecked = YamlUtil.load(is);
        }

        Set<String> filesWeAlreadyWarnedAbout = new HashSet<>();

        for (Map.Entry<String, Map<String, Object>> modMap : updaterDatabase.entrySet()) {
            String url = modMap.getValue().get(Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL").toString();

            newAlreadyChecked.add(url);
            if (oldAlreadyChecked.contains(url)) {
                continue;
            }

            // if a URL is present twice, we are going to encounter it twice, but we still want to warn about it only once.
            if (filesWeAlreadyWarnedAbout.contains(url)) {
                continue;
            }

            // go through the database again to find all mods that have the same URL (including the mod we are checking itself)
            List<String> modNames = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> otherModMap : updaterDatabase.entrySet()) {
                String otherUrl = otherModMap.getValue().get(Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL").toString();

                if (otherUrl.equals(url)) {
                    modNames.add(otherModMap.getKey());
                }
            }

            logger.debug("URL {} belongs to mod(s) {}", url, modNames);

            if (modNames.size() > 1) {
                // we found a URL associated with 2 or more mods!
                sendAlertToWebhook(":warning: Mods **" + String.join("**, **", modNames) + "** are all associated to file " + url + ".\n" +
                        "This means this file contains multiple mods, which can cause weirdness when it gets updated (multiple entries for the same file appearing in the updater).\n" +
                        "If having multiple mods cannot be avoided, one of them should be updater-blacklisted by Maddie.");

                filesWeAlreadyWarnedAbout.add(url);
            }
        }

        try (OutputStream os = new FileOutputStream("already_checked_multiple_mods.yaml")) {
            YamlUtil.dump(newAlreadyChecked, os);
        }
    }

    public static void checkUnapprovedCategories() throws IOException {
        List<Map<String, Object>> modSearchDatabase;
        try (InputStream is = new FileInputStream("uploads/modsearchdatabase.yaml")) {
            modSearchDatabase = YamlUtil.load(is);
        }

        for (String itemtype : modSearchDatabase.stream().map(m -> (String) m.get("GameBananaType")).collect(Collectors.toSet())) {
            checkUnapprovedCategoriesFor(itemtype);
        }
    }

    private static void checkUnapprovedCategoriesFor(String name) throws IOException {
        // "unapproved categories" are categories that definitely exist, where people can add mods...
        // but that don't appear in the list when you just browse the Mods section because that requires admin approval.
        // so they're categories that exist... but don't exist. This makes no sense and that's why it needs fixing.
        logger.debug("Checking for unapproved {} categories", name);

        JSONArray listOfCategories = ConnectionUtils.runWithRetry(() -> {
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://gamebanana.com/apiv8/" + name + "Category/ByGame?_aGameRowIds[]=6460&" +
                    "_csvProperties=_idRow,_idParentCategoryRow&_sOrderBy=_idRow,ASC&_nPage=1&_nPerpage=50")) {

                return new JSONArray(new JSONTokener(is));
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
                try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://gamebanana.com/apiv8/" + name + "/ByGame?_aGameRowIds[]=6460&" +
                        "_csvProperties=_aCategory&_sOrderBy=_idRow,ASC&_nPage=" + thisPage + "&_nPerpage=50")) {

                    return new JSONArray(new JSONTokener(is));
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

        List<Integer> alreadyWarned;
        try (InputStream is = new FileInputStream("already_warned_unapproved_cats_" + name + ".yaml")) {
            alreadyWarned = YamlUtil.load(is);
        }

        for (int category : categoriesThatExistButDont) {
            if (!alreadyWarned.contains(category)) {
                sendAlertToWebhook(":warning: The category at <https://gamebanana.com/" + name.toLowerCase(Locale.ROOT) + "s/cats/" + category + "> does not seem to be approved by site admins!\n" +
                        "This means it will not appear in the categories list (neither in Olympus nor on GameBanana itself).");
            }
        }

        try (OutputStream os = new FileOutputStream("already_warned_unapproved_cats_" + name + ".yaml")) {
            YamlUtil.dump(new ArrayList<>(categoriesThatExistButDont), os);
        }
    }

    public static void checkPngFilesArePngFiles() throws IOException {
        List<String> oldAlreadyChecked;
        List<String> newAlreadyChecked = new ArrayList<>();
        try (InputStream is = new FileInputStream("already_validated_png_files.yaml")) {
            oldAlreadyChecked = YamlUtil.load(is);
        }

        // load mod list
        List<String> mods;
        try (InputStream is = new FileInputStream("modfilesdatabase/list.yaml")) {
            mods = YamlUtil.load(is);
        }

        for (String mod : mods) {
            // load file list for the mod
            String modName;
            List<String> files;
            try (InputStream is = new FileInputStream("modfilesdatabase/" + mod + "/info.yaml")) {
                Map<String, Object> info = YamlUtil.load(is);
                modName = info.get("Name").toString();
                files = (List<String>) info.get("Files");
            }

            for (String file : files) {
                newAlreadyChecked.add(file);

                // skip already checked mods
                if (oldAlreadyChecked.contains(file)) {
                    continue;
                }

                // load file listing for the mod, so that we know which PNG files to check for
                List<String> filesToCheck;
                try (InputStream is = new FileInputStream("modfilesdatabase/" + mod + "/" + file + ".yaml")) {
                    List<String> fileList = YamlUtil.load(is);
                    filesToCheck = fileList.stream()
                            .filter(fileName -> fileName.startsWith("Graphics/") && fileName.endsWith(".png"))
                            .collect(Collectors.toList());
                }

                // skip downloading entirely if there is no PNG file (if the file is not a zip, the file listing will be empty)
                if (filesToCheck.isEmpty()) {
                    logger.debug("Skipping file {} because it has no PNG file!", file);
                    continue;
                }

                // download the file from GameBanana...
                String url = "https://gamebanana.com/mmdl/" + file;
                logger.debug("Downloading {} ({}) for PNG file checking, we have {} files to check", url, modName, filesToCheck.size());
                ConnectionUtils.runWithRetry(() -> {
                    try (InputStream is = ConnectionUtils.openStreamWithTimeout(url)) {
                        FileUtils.copyToFile(is, new File("/tmp/png_police.zip"));
                        return null;
                    }
                });

                // extract its PNG files and check for the signature.
                List<String> badPngs = new LinkedList<>();
                try (ZipFile zip = ZipFileWithAutoEncoding.open("/tmp/png_police.zip")) {
                    for (String fileName : filesToCheck) {
                        if (!checkPngSignature(zip, zip.getEntry(fileName))) {
                            badPngs.add(fileName);
                        }
                    }
                }

                logger.debug("Deleting temporary ZIP");
                FileUtils.forceDelete(new File("/tmp/png_police.zip"));

                if (!badPngs.isEmpty()) {
                    // write the file listing to a file we will be able to attach to the alert.
                    File tempListFile = new File("/tmp/bad_png_files.txt");
                    FileUtils.writeStringToFile(tempListFile, String.join("\n", badPngs), UTF_8);

                    String nameForUrl = mod.split("/")[0].toLowerCase(Locale.ROOT) + "s/" + mod.split("/")[1];

                    for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
                        if (webhook.startsWith("https://discord.com/") && tempListFile.length() < 8_388_608L) {
                            // Discord webhook: send the file with attachment
                            WebhookExecutor.executeWebhook(webhook,
                                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/gamebanana.png",
                                    "Banana Watch",
                                    ":warning: The file at " + url + " (mod **" + modName + "**) has invalid PNG files! You will find the list attached. " +
                                            "This is illegal <:landeline:458158726558384149>\n:arrow_right: https://gamebanana.com/" + nameForUrl,
                                    false,
                                    Collections.singletonList(tempListFile));
                        } else {
                            // Discord-compatible webhook or file is too big(???): send the file with special header but without the attachment
                            WebhookExecutor.executeWebhook(webhook,
                                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/gamebanana.png",
                                    "Banana Watch",
                                    ":warning: The file at " + url + " (mod **" + modName + "**) has invalid PNG files! " +
                                            "This is illegal <:landeline:458158726558384149>\n:arrow_right: https://gamebanana.com/" + nameForUrl,
                                    ImmutableMap.of("X-Everest-Log", "true"));
                        }
                    }

                    // delete temp file
                    FileUtils.forceDelete(tempListFile);
                }
            }
        }

        try (OutputStream os = new FileOutputStream("already_validated_png_files.yaml")) {
            YamlUtil.dump(newAlreadyChecked, os);
        }
    }

    static boolean checkPngSignature(ZipFile file, ZipEntry entry) throws IOException {
        logger.debug("Checking file {}", entry.getName());

        try (InputStream is = file.getInputStream(entry)) {
            byte[] signature = new byte[8];
            int readBytes = is.read(signature);

            return readBytes == 8
                    && signature[0] == -119 // 0x89
                    && signature[1] == 0x50
                    && signature[2] == 0x4E
                    && signature[3] == 0x47
                    && signature[4] == 0x0D
                    && signature[5] == 0x0A
                    && signature[6] == 0x1A
                    && signature[7] == 0x0A;
        }
    }

    private static void sendAlertToWebhook(String message) throws IOException {
        for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
            WebhookExecutor.executeWebhook(webhook,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/gamebanana.png",
                    "Banana Watch",
                    message,
                    ImmutableMap.of("X-Everest-Log", "true"));
        }
    }
}
