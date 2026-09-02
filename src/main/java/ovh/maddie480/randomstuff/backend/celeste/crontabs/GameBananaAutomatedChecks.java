package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.ModDatabase;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.FileRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ModRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.providers.GameBananaModProvider;
import ovh.maddie480.randomstuff.backend.utils.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
    private static final List<String> BAD_FILE_LIST = Arrays.asList(
            "CSteamworks.dll", "Celeste.Mod.mm.dll", "DotNetZip.dll", "FNA.dll", "I18N.CJK.dll", "I18N.MidEast.dll",
            "I18N.Other.dll", "I18N.Rare.dll", "I18N.West.dll", "I18N.dll", "Jdenticon.dll", "KeraLua.dll", "MMHOOK_Celeste.dll", "MojoShader.dll",
            "Mono.Cecil.Mdb.dll", "Mono.Cecil.Pdb.dll", "Mono.Cecil.Rocks.dll", "Mono.Cecil.dll", "MonoMod.RuntimeDetour.dll", "MonoMod.Utils.dll", "NLua.dll",
            "Newtonsoft.Json.dll", "SDL2.dll", "SDL2_image.dll", "Steamworks.NET.dll", "YamlDotNet.dll", "discord-rpc.dll", "fmod.dll", "fmodstudio.dll",
            "libEGL.dll", "libGLESv2.dll", "libjpeg-9.dll", "libpng16-16.dll", "lua53.dll", "steam_api.dll", "zlib1.dll", "Microsoft.Xna.Framework.dll",
            "Microsoft.Xna.Framework.Game.dll", "Microsoft.Xna.Framework.Graphics.dll");

    private static final Pattern objDirectoryRegex = Pattern.compile("(.*(?:/|^)obj/(?:Debug|Release))(?:/|$).*");

    public static String getMaskedEnhancedEmbedLink(ModRecord mod, FileRecord file) {
        String maskedLink = mod.pageUrl;
        if (mod.id.startsWith("GameBanana/")) {
            String link = mod.pageUrl.replace("https://", "");
            maskedLink = "[" + link + "](https://maddie480.ovh/" + link + ")";
        }
        return maskedLink + " (<" + file.mainUrl + ">)";
    }

    public static String getMaskedEnhancedEmbedLink(ModDatabase.ModLatestVersion v) {
        return getMaskedEnhancedEmbedLink(v.mod(), v.file());
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
    public static void checkYieldReturnOrigAndIntPtrTrick(ModDatabase database) throws IOException {
        // the new file list is built from scratch (only files that still exist are copied over from the previous list).
        List<String> newResults = new ArrayList<>();

        // and we want to load the previous state to be sure we don't handle already handled mods.
        List<String> oldResults;
        try (InputStream is = new FileInputStream("already_validated_dll_files.yaml")) {
            oldResults = YamlUtil.load(is);
        }

        for (ModDatabase.ModLatestVersion record : database.listLatestVersions()) {
            if (oldResults.contains(record.file().id)) {
                // skip scanning already scanned files.
                newResults.add(record.file().id);
            } else {
                if (Arrays.stream(record.file().fileListing).anyMatch(f -> f.toLowerCase(Locale.ROOT).endsWith(".dll"))) {
                    // file listing contains dll, so download!
                    logger.debug("Downloading mod {} (file id {})", record.file().modId, record.file().id);

                    try (InputStream is = ConnectionUtils.openStreamWithTimeout(record.file().mainUrl)) {
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
                        boolean consoleWriteLine = false;
                        boolean fishyProcessStuff = false;
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
                                            new ProcessBuilder("/home/ubuntu/.dotnet/tools/ilspycmd", "/tmp/mod_yield_police.dll").start());

                                    int lines = 0;

                                    try (InputStream is = p.getInputStream();
                                         BufferedReader br = new BufferedReader(new InputStreamReader(is, UTF_8))) {

                                        String line;
                                        while ((line = br.readLine()) != null) {
                                            lines++;
                                            if (line.contains("yield return orig.Invoke")) {
                                                logger.warn("Mod {} uses yield return orig(self)!", record.file().modId);
                                                yieldReturnIssue = true;
                                            }
                                            if (line.contains("Console.WriteLine")) {
                                                logger.warn("Mod {} contains Console.WriteLine", record.file().modId);
                                                consoleWriteLine = true;
                                            }
                                            if (Stream.of("ProcessStartInfo", "Process.Start", "new Process", "UseShellExecute")
                                                    .anyMatch(line::contains)
                                                    && !"CelesteTAS".equals(record.file().modId) // Celeste Studio
                                                    && !"Vidcutter".equals(record.file().modId) // ffmpeg
                                                    && !"ChroniaHelper".equals(record.file().modId) // Open URL Trigger
                                                    && !"Head2Head".equals(record.file().modId) // Open Control Panel option
                                                    && !"ConsistencyTracker".equals(record.file().modId) // Opens stuff in browser
                                                    && !"girlhell1999".equals(record.file().modId) // other Open URL Trigger that causes debates in banana-watch
                                            ) {
                                                logger.warn("Mod {} contains Process usage", record.file().modId);
                                                fishyProcessStuff = true;
                                            }
                                        }
                                    }

                                    try {
                                        p.waitFor();
                                    } catch (InterruptedException e) {
                                        throw new IOException(e);
                                    }

                                    if (p.exitValue() != 0) {
                                        throw new IOException("ilspycmd returned exit code " + p.exitValue());
                                    }

                                    logger.debug("Decompiled {} lines of code", lines);

                                    logger.debug("Deleting temporary DLL");
                                    FileUtils.forceDelete(new File("/tmp/mod_yield_police.dll"));
                                }
                            }
                        }

                        newResults.add(record.file().id);

                        if (yieldReturnIssue) {
                            sendAlertToWebhook(database, ":warning: The mod called **" + record.file().modId + "** uses `yield return orig(self)`!" +
                                    " This might change timings and desync TASes <:UnimpressedPoggersGuneline:971378034441601034>\n:arrow_right: " + getMaskedEnhancedEmbedLink(record));
                        }

                        if (consoleWriteLine) {
                            sendAlertToWebhook(database, ":warning: The mod called **" + record.file().modId + "** uses `Console.WriteLine`!" +
                                    " This might pollute the logs <:faintshiro:463773786819264512>\n:arrow_right: " + getMaskedEnhancedEmbedLink(record));
                        }

                        if (fishyProcessStuff) {
                            sendAlertToWebhook(database, ":warning: The mod called **" + record.file().modId + "** seems to be using `Process` APIs!" +
                                    " Make sure that it isn't doing anything fishy with them :fish:\n:arrow_right: " + getMaskedEnhancedEmbedLink(record));
                        }

                        if (!dllEntryFoundInYaml) {
                            sendAlertToWebhook(database, ":warning: The mod called **" + record.file().modId + "** ships with DLLs, but does not refer to any in its everest.yaml." +
                                    " Might be an oversight? <:laugheline:454887887847030814>\n:arrow_right: " + getMaskedEnhancedEmbedLink(record));
                        }
                    } catch (ZipException e) {
                        logger.warn("Error while reading zip. Adding to the whitelist so that it isn't retried.", e);
                        newResults.add(record.file().id);

                        // send an angry ping to the owner to have the mod manually checked
                        WebhookExecutor.executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK,
                                "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/gamebanana.png",
                                "Banana Watch",
                                "<@" + SecretConstants.OWNER_ID + "> The mod called **" + record.file().modId + "** could not be checked. Please check it manually.\n" +
                                        ":arrow_right: " + getMaskedEnhancedEmbedLink(record),
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
    public static void checkForForbiddenFiles(ModDatabase database) throws IOException {
        // the new file list is built from scratch (only files that still exist are copied over from the previous list).
        List<String> alreadyCheckedNew = new ArrayList<>();

        // and we want to load the previous state to be sure we don't handle already handled mods.
        List<String> alreadyCheckedOld;
        try (InputStream is = new FileInputStream("already_checked_for_illegal_files.yaml")) {
            alreadyCheckedOld = YamlUtil.load(is);
        }

        scanModFileListings(database, alreadyCheckedOld, alreadyCheckedNew);

        try (OutputStream os = new FileOutputStream("already_checked_for_illegal_files.yaml")) {
            YamlUtil.dump(alreadyCheckedNew, os);
        }
    }

    private static void scanModFileListings(ModDatabase database, List<String> alreadyCheckedOld, List<String> alreadyCheckedNew) throws IOException {
        for (ModRecord mod : database.allMods) {
            for (FileRecord file : mod.files) {
                // check for forbidden files if not already done
                alreadyCheckedNew.add(file.id);
                if (!alreadyCheckedOld.contains(file.id)) {
                    logger.debug("Checking for illegal files in file {} of {}...", file.id, mod.id);

                    // check for EXE files
                    List<String> exeList = Arrays.stream(file.fileListing)
                            .filter(f -> f.toLowerCase().endsWith(".exe"))
                            .toList();

                    if (!exeList.isEmpty()) {
                        String message = ":warning: The mod called **" + mod.name + "** contains an EXE file: `" + exeList.getFirst() + "`! " +
                                "This is pretty fishy <:thonkeline:640606520706465804>\n:arrow_right: " + getMaskedEnhancedEmbedLink(mod, file);

                        for (int i = 2; i <= exeList.size(); i++) {
                            String newMessage = ":warning: The mod called **" + mod.name + "** contains EXE files: `" +
                                    exeList.stream().limit(i - 1).collect(Collectors.joining("`, `")) + "` and `" + exeList.get(i - 1) + "`! " +
                                    "This is pretty fishy <:thonkeline:640606520706465804>\n:arrow_right: " + getMaskedEnhancedEmbedLink(mod, file);

                            if (newMessage.length() > 2000) break;
                            message = newMessage;
                        }

                        sendAlertToWebhook(database, message);
                    }

                    // check against the bad file list (tm)
                    for (String entry : file.fileListing) {
                        for (String illegalFile : BAD_FILE_LIST) {
                            if (entry.equalsIgnoreCase(illegalFile) || entry.toLowerCase(Locale.ROOT).endsWith("/" + illegalFile.toLowerCase(Locale.ROOT))) {
                                // this file is illegal!
                                sendAlertToWebhook(database, ":warning: The mod called **" + mod.name + "** contains a file called `" + illegalFile + "`! " +
                                        "It already ships with Everest <:destareline:935372132102311986>\n:arrow_right: " + getMaskedEnhancedEmbedLink(mod, file));
                                return;
                            }
                        }

                        Matcher objDirectoryMatcher = objDirectoryRegex.matcher(entry);
                        if (objDirectoryMatcher.matches()) {
                            sendAlertToWebhook(database, ":warning: The mod called **" + mod.name + "** contains a `" + objDirectoryMatcher.group(1) + "` folder! " +
                                    "You generally don't need to ship this folder with your mod, it makes the zip bigger for no reason <:pausefrogelineatthephone:946115556073934898>\n:arrow_right: " + getMaskedEnhancedEmbedLink(mod, file));
                            return;
                        }
                    }
                }
            }
        }
    }

    public static void checkDuplicateModIdsCaseInsensitive(ModDatabase database) throws IOException {
        Path alreadyReportedStorage = Paths.get("already_reported_duplicates.yaml");

        List<List<String>> oldDuplicateList;
        List<List<String>> newDuplicateList = new ArrayList<>();
        try (InputStream is = Files.newInputStream(alreadyReportedStorage)) {
            oldDuplicateList = YamlUtil.load(is);
        }

        for (ModDatabase.ModLatestVersion mod1 : database.listLatestVersions()) {
            for (ModDatabase.ModLatestVersion mod2 : database.listLatestVersions()) {
                if (!mod1.file().modId.equals(mod2.file().modId) && mod1.file().modId.equalsIgnoreCase(mod2.file().modId)) {
                    // :landeline: those are case-insensitive duplicates!
                    List<String> pair = new ArrayList<>(Arrays.asList(mod1.file().modId, mod2.file().modId));
                    pair.sort(Comparator.naturalOrder());

                    if (!oldDuplicateList.contains(pair) && !newDuplicateList.contains(pair)) {
                        sendAlertToWebhook(database, ":warning: Mods " +
                                getMaskedEnhancedEmbedLink(mod1) + " (**" + mod1.file().modId + "**) and " +
                                getMaskedEnhancedEmbedLink(mod2) + " (**" + mod2.file().modId + "**) " +
                                "have the same mod ID with different cases.\nThis will cause them to overwrite each other when downloading both on Windows!"
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

    public static void checkAllModsWithEverestYamlValidator(ModDatabase database) throws IOException {
        List<String> oldAlreadyChecked;
        List<String> newAlreadyChecked = new ArrayList<>();
        try (InputStream is = new FileInputStream("already_validated_yaml_files.yaml")) {
            oldAlreadyChecked = YamlUtil.load(is);
        }

        for (ModDatabase.ModLatestVersion record : database.listLatestVersions()) {
            if (!oldAlreadyChecked.contains(record.file().id)) {
                logger.debug("Downloading {} ({}) for everest.yaml checking", record.file().mainUrl, record.file().modId);
                try (InputStream is = ConnectionUtils.openStreamWithTimeout(record.file().mainUrl)) {
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
                        handleEverestYamlInvalidSyntax(database, destination, resultBody.getString("parseError"),
                                ":warning: The mod called **" + record.file().modId + "** has an everest.yaml file with invalid syntax:\n```\n"
                                        + resultBody.getString("parseError")
                                        + "\n```\n:arrow_right: " + getMaskedEnhancedEmbedLink(record));
                    } else if (resultBody.has("validationErrors")) {
                        List<String> allErrors = new ArrayList<>();
                        for (Object o : resultBody.getJSONArray("validationErrors")) {
                            allErrors.add((String) o);
                        }
                        sendAlertToWebhook(database, ":warning: The mod called **" + record.file().modId + "** does not pass the everest.yaml validator:\n- "
                                + String.join("\n- ", allErrors)
                                + "\n:arrow_right: " + getMaskedEnhancedEmbedLink(record));
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
                                    logger.warn("File referred by DLL field {} does not exist in archive for mod {}!", entry.get("DLL"), record.file().modId);
                                    problem = true;
                                } else {
                                    logger.debug("File referred by DLL field {} exists", entry.get("DLL"));
                                }
                            }
                        }

                        if (problem) {
                            sendAlertToWebhook(database, ":warning: The mod called **" + record.file().modId + "** has an everest.yaml file that refers to a DLL that does not exist." +
                                    " Might be an oversight? <:laugheline:454887887847030814>\n:arrow_right: " + getMaskedEnhancedEmbedLink(record));
                        }
                    }

                    logger.debug("Deleting temp file");
                    Files.delete(destination);
                }

                logger.debug("Deleting temporary ZIP");
                FileUtils.forceDelete(new File("/tmp/everest_yaml_police.zip"));
            }

            newAlreadyChecked.add(record.file().id);
        }

        try (OutputStream os = new FileOutputStream("already_validated_yaml_files.yaml")) {
            YamlUtil.dump(newAlreadyChecked, os);
        }
    }

    private static void handleEverestYamlInvalidSyntax(ModDatabase database, Path yaml, String error, String message) throws IOException {
        sendAlertToWebhook(database, message);

        if (!error.matches("^Cannot parse Dependencies for .*: No Version is specified for Everest$")) return;

        // I might be able to help! First, let's figure out which version we should add.
        int latestEverestStable;
        try (BufferedReader br = Files.newBufferedReader(Paths.get("/shared/celeste/latest-everest-versions.json"))) {
            latestEverestStable = new JSONObject(new JSONTokener(br)).getInt("stable");
        }

        // create a new folder, so that the file can just be called everest.yaml for simplicity's sake
        Path fixedFile = Paths.get("/tmp/banana_watch_helps_out/everest.yaml");
        Files.createDirectories(fixedFile.getParent());

        try {
            List<Map<String, Object>> yamlFile;
            try (InputStream is = Files.newInputStream(yaml)) {
                yamlFile = YamlUtil.loadNoFloats(is);
            }

            // look for mods that have a Dependencies entry with Name: Everest and no Version, and add it
            boolean changed = false;
            for (Map<String, Object> mod : yamlFile) {
                List<Map<String, String>> dependencies = (List<Map<String, String>>) mod.get("Dependencies");
                if (dependencies == null) continue;
                for (Map<String, String> dependency : dependencies) {
                    if ("Everest".equals(dependency.get("Name")) && !dependency.containsKey("Version")) {
                        dependency.put("Version", "1." + latestEverestStable + ".0");
                        changed = true;
                    }
                }
            }
            if (!changed) return;

            // write back the file to be used for the webhook
            try (OutputStream os = Files.newOutputStream(fixedFile)) {
                YamlUtil.dump(yamlFile, os);
            }
        } catch (Exception e) {
            logger.warn("I tried to help with the Everest version error, but it didn't work!!!", e);
            return;
        }

        for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
            if (!webhook.startsWith("https://discord.com/")) continue;

            WebhookExecutor.executeWebhook(webhook,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/gamebanana.png",
                    "Banana Watch",
                    "Here is a fixed file with the Everest version added:",
                    false,
                    Collections.singletonList(fixedFile.toFile()));
        }

        FileUtils.deleteDirectory(fixedFile.getParent().toFile());
    }

    public static void checkUnapprovedCategories() throws IOException {
        for (String itemtype : GameBananaModProvider.VALID_CATEGORIES) {
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
                sendAlertToWebhook(null, ":warning: The category at <https://gamebanana.com/" + name.toLowerCase(Locale.ROOT) + "s/cats/" + category + "> does not seem to be approved by site admins!\n" +
                        "This means it will not appear in the categories list (neither in Olympus nor on GameBanana itself).");
            }
        }

        try (OutputStream os = new FileOutputStream("already_warned_unapproved_cats_" + name + ".yaml")) {
            YamlUtil.dump(new ArrayList<>(categoriesThatExistButDont), os);
        }
    }

    public static void checkPngFilesArePngFiles(ModDatabase database) throws IOException {
        List<String> oldAlreadyChecked;
        List<String> newAlreadyChecked = new ArrayList<>();
        try (InputStream is = new FileInputStream("already_validated_png_files.yaml")) {
            oldAlreadyChecked = YamlUtil.load(is);
        }

        for (ModRecord mod : database.allMods) {
            for (FileRecord file : mod.files) {
                newAlreadyChecked.add(file.id);

                // skip already checked mods
                if (oldAlreadyChecked.contains(file.id)) {
                    continue;
                }

                // load file listing for the mod, so that we know which PNG files to check for
                List<String> filesToCheck = Arrays.stream(file.fileListing)
                        .filter(fileName -> fileName.startsWith("Graphics/") && fileName.endsWith(".png"))
                        .toList();

                // skip downloading entirely if there is no PNG file (if the file is not a zip, the file listing will be empty)
                if (filesToCheck.isEmpty()) {
                    logger.debug("Skipping file {} because it has no PNG file!", file);
                    continue;
                }

                // download the file from GameBanana...
                logger.debug("Downloading {} ({}) for PNG file checking, we have {} files to check", file.mainUrl, mod.name, filesToCheck.size());
                ConnectionUtils.runWithRetry(() -> {
                    try (InputStream is = ConnectionUtils.openStreamWithTimeout(file.mainUrl)) {
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
                    String badPngListMessage = String.join("\n", badPngs);
                    File tempListFile = new File("/tmp/bad_png_files.txt");
                    FileUtils.writeStringToFile(tempListFile, badPngListMessage, UTF_8);

                    badPngListMessage = ":warning: The file at " + file.mainUrl + " (page title **" + mod.name + "**) has invalid PNG files:\n" +
                            "```\n" +
                            badPngListMessage + "\n" +
                            "```\n" +
                            "This can cause crashes in some configurations. Please open them and resave them as PNGs, just renaming the file is not enough!\n" +
                            ":arrow_right: " + getMaskedEnhancedEmbedLink(mod, file);

                    for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
                        if (badPngListMessage.length() <= 2000) {
                            // list is short enough to fit in the message itself: just include it
                            executeEnhancedWebhook(database, webhook, badPngListMessage);
                        } else if (webhook.startsWith("https://discord.com/") && tempListFile.length() <= 10 * 1024 * 1024) {
                            // Discord webhook and list too long to be included in the message: send the file with attachment
                            executeEnhancedWebhook(database, webhook,
                                    ":warning: The file at " + file.mainUrl + " (page title **" + mod.name + "**) has invalid PNG files! You will find the list attached.\n" +
                                            "This can cause crashes in some configurations. Please open them and resave them as PNGs, just renaming the file is not enough!\n" +
                                            ":arrow_right: " + getMaskedEnhancedEmbedLink(mod, file),
                                    Collections.singletonList(tempListFile)
                            );
                        } else {
                            // Discord-compatible webhook or file is too big(???): send the file with special header but without the attachment
                            executeEnhancedWebhook(database, webhook,
                                    ":warning: The file at " + file.mainUrl + " (page title **" + mod.name + "**) has invalid PNG files!\n" +
                                            "This can cause crashes in some configurations. Please open them and resave them as PNGs, just renaming the file is not enough!\n" +
                                            ":arrow_right: " + getMaskedEnhancedEmbedLink(mod, file)
                            );
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

    public static boolean checkPngSignature(ZipFile file, ZipEntry entry) throws IOException {
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

    public static void checkForBananaGettingDrunkAndServingTheWrongFile(ModDatabase database) throws IOException {
        // load state
        Set<String> alreadyProcessed = new HashSet<>();
        Path statusFile = Paths.get("banana_moment_check.yaml");
        try (InputStream is = Files.newInputStream(statusFile)) {
            alreadyProcessed = new HashSet<>(YamlUtil.<List<String>>load(is));
        } catch (IOException e) {
            logger.warn("Could not read already processed files, starting over from beginning", e);
        }

        List<String> fileIds = new ArrayList<>();
        for (ModRecord mod : database.allMods) {
            for (FileRecord file : mod.files) {
                fileIds.add(file.id);
                if (alreadyProcessed.contains(file.id)) continue;

                logger.debug("Checking file size match for: {}", file.id);

                // query the file server to figure out the size of the actual file...
                int realSize = ConnectionUtils.runWithRetry(() -> {
                    HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(file.mainUrl);
                    connection.setRequestMethod("HEAD");
                    connection.setInstanceFollowRedirects(true);

                    if (connection.getResponseCode() != 200) {
                        throw new IOException("HEAD " + file.mainUrl + " responded with code " + connection.getResponseCode());
                    }

                    String contentLengthHeader = connection.getHeaderField("content-length");
                    try {
                        int sizeReal = Integer.parseInt(contentLengthHeader);
                        if (sizeReal > 0) return sizeReal;
                    } catch (NumberFormatException e) {
                    }

                    throw new IOException("HEAD " + file.mainUrl + " responded with invalid content-length " + contentLengthHeader);
                });

                // ... and compare the two. If they don't match, it's likely that the downloadable file
                // is not actually the file that got uploaded in the first place, and that's a problem!
                // (that did happen multiple times already, mind you)
                if (file.size != realSize) {
                    sendAlertToWebhook(database, ":warning: GameBanana's API and file servers disagree on the file size of <" + file.mainUrl + ">.\nThe download link might lead to another file uploaded to GameBanana with the same name! If this is the case, you should rename the file, then try uploading it again.\n"
                            + ":arrow_right: " + getMaskedEnhancedEmbedLink(mod, file));
                }
            }
        }

        // save state
        try (OutputStream os = Files.newOutputStream(statusFile)) {
            YamlUtil.dump(fileIds, os);
        }
    }

    public static void executeEnhancedWebhook(ModDatabase database, String webhookUrl, String body, List<File> attachments) throws IOException {
        Pair<String, List<Map<String, Object>>> enhanced = enhanceYourWebhook(database, body);
        if (enhanced.getRight().isEmpty() || !webhookUrl.startsWith("https://discord.com/")) {
            WebhookExecutor.executeWebhook(webhookUrl,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/gamebanana.png",
                    "Banana Watch",
                    body,
                    false,
                    attachments);
        } else {
            WebhookExecutor.executeWebhook(webhookUrl,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/gamebanana.png",
                    "Banana Watch",
                    enhanced.getLeft(),
                    attachments,
                    enhanced.getRight());
        }
    }

    public static void executeEnhancedWebhook(ModDatabase database, String webhookUrl, String body) throws IOException {
        Pair<String, List<Map<String, Object>>> enhanced = enhanceYourWebhook(database, body);
        if (enhanced.getRight().isEmpty() || !webhookUrl.startsWith("https://discord.com/")) {
            WebhookExecutor.executeWebhook(webhookUrl,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/gamebanana.png",
                    "Banana Watch",
                    body,
                    ImmutableMap.of("X-Everest-Log", "true"));
        } else {
            WebhookExecutor.executeWebhook(webhookUrl,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/gamebanana.png",
                    "Banana Watch",
                    enhanced.getLeft(),
                    enhanced.getRight());
        }
    }

    private static void sendAlertToWebhook(ModDatabase database, String message) throws IOException {
        Pair<String, List<Map<String, Object>>> enhanced = null;
        if (database != null) enhanced = enhanceYourWebhook(database, message);
        for (String webhook : SecretConstants.GAMEBANANA_ISSUES_ALERT_HOOKS) {
            if (enhanced == null || enhanced.getRight().isEmpty() || !webhook.startsWith("https://discord.com/")) {
                WebhookExecutor.executeWebhook(webhook,
                        "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/gamebanana.png",
                        "Banana Watch",
                        message,
                        ImmutableMap.of("X-Everest-Log", "true"));
            } else {
                WebhookExecutor.executeWebhook(webhook,
                        "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/gamebanana.png",
                        "Banana Watch",
                        enhanced.getLeft(),
                        enhanced.getRight());
            }
        }
    }

    public static Pair<String, List<Map<String, Object>>> enhanceYourWebhook(ModDatabase database, String body) {
        String bananaRegex = "gamebanana\\.com/([a-z]+)s/([0-9]+)";
        List<Triple<String, String, Map<String, Object>>> stuffToEnhance =
                Pattern.compile("\\[" + bananaRegex + "]\\(https://maddie480\\.ovh/" + bananaRegex + "\\)")
                        .matcher(body)
                        .results()
                        .map(result -> Pair.of(result.group(1), Integer.parseInt(result.group(2))))
                        .distinct()
                        .map(pair -> {
                            String itemtype = pair.getLeft().substring(0, 1).toUpperCase() + pair.getLeft().substring(1);
                            int itemid = pair.getRight();

                            logger.debug("Looking for {} {} in mod search database...", itemtype, itemid);
                            ModRecord matchingMod = database.allMods.stream()
                                    .filter(m -> m.id.equals("GameBanana/" + itemtype + "/" + itemid))
                                    .findFirst().orElse(null);

                            if (matchingMod != null) {
                                logger.debug("Mod info found! The mod is called: {}", matchingMod.name);

                                String bananaLink = "gamebanana.com/" + itemtype.toLowerCase() + "s/" + itemid;
                                String bananaFallbackLink = "[" + bananaLink + "](https://maddie480.ovh/" + bananaLink + ")";
                                bananaLink = "<https://" + bananaLink + ">";

                                JSONArray a = EmbedBuilder.buildEmbedFor(matchingMod);
                                return Triple.of(bananaFallbackLink, bananaLink, a.getJSONObject(0).toMap());
                            }

                            logger.error("Mod wasn't found in mod search database, we won't enhance it!");
                            return null;
                        })
                        .toList();

        if (stuffToEnhance.contains(null)) {
            // passing our own embeds prevents Discord from generating its own,
            // and we don't want that to happen if we couldn't generate embeds for some of the links...
            logger.warn("One of the links couldn't be enhanced, we won't enhance anything!");
            return Pair.of(body, Collections.emptyList());
        }

        List<Map<String, Object>> embeds = new ArrayList<>();
        for (Triple<String, String, Map<String, Object>> element : stuffToEnhance) {
            body = body.replace(element.getLeft(), element.getMiddle());
            embeds.add(element.getRight());
        }
        return Pair.of(body, embeds);
    }
}
