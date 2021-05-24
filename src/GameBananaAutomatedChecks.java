package com.max480.discord.randombots;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * This class is intended to be run periodically to check mods on GameBanana for issues.
 * In max480's bot setup, it is run everyday at midnight French time (10pm or 11pm UTC depending on daylight saving).
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

    private static Pattern gamebananaLinkRegex = Pattern.compile(".*(https://gamebanana.com/mmdl/[0-9]+).*");

    public static void main(String[] args) throws IOException {
        checkYieldReturnOrigAndIntPtrTrick();
        checkForForbiddenFiles();
        checkForDuplicateModIds();
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
    private static void checkYieldReturnOrigAndIntPtrTrick() throws IOException {
        List<String> newGoodFileList = new ArrayList<>();

        List<String> goodFiles;
        try (InputStream is = new FileInputStream("yield_return_orig_police_whitelist.yaml")) {
            goodFiles = new Yaml().load(is);
        }
        Map<String, Map<String, Object>> updaterDatabase;
        try (InputStream is = new FileInputStream("uploads/everestupdate.yaml")) {
            updaterDatabase = new Yaml().load(is);
        }

        for (Map.Entry<String, Map<String, Object>> modEntry : updaterDatabase.entrySet()) {
            String modName = modEntry.getKey();
            Map<String, Object> mod = modEntry.getValue();

            // does the file have a dll?
            String fileName = mod.get("URL").toString().substring("https://gamebanana.com/mmdl/".length());

            if (goodFiles.contains(fileName)) {
                // skip
                newGoodFileList.add(fileName);
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

                    try (InputStream is = new URL(mod.get("MirrorURL").toString()).openStream()) {
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
                                } else {
                                    logger.info("No yield return orig(self) detected in mod {}", modName);
                                }

                                logger.debug("Deleting temporary DLL");
                                FileUtils.forceDelete(new File("/tmp/mod_yield_police.dll"));
                            }
                        }

                        if (!yieldReturnIssue && !intPtrIssue) {
                            newGoodFileList.add(fileName);
                        } else {
                            // yell because mod bad :MADeline:
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
                        }
                    } catch (ZipException e) {
                        logger.warn("Error while reading zip. Adding to the whitelist so that it isn't retried.", e);
                        newGoodFileList.add(fileName);

                        // send an angry ping to the owner to have the mod manually checked
                        try {
                            WebhookExecutor.executeWebhook(SecretConstants.YIELD_RETURN_ALERT_HOOKS.get(0),
                                    "https://cdn.discordapp.com/avatars/793432836912578570/0a3f716e15c8c3adca6c461c2d64553e.png?size=128",
                                    "Banana Watch",
                                    "<@" + SecretConstants.OWNER_ID + "> The mod called **" + modName + "** could not be checked. Please check it manually.\n" +
                                            ":arrow_right: https://gamebanana.com/" + mod.get("GameBananaType").toString().toLowerCase() + "s/" + mod.get("GameBananaId"),
                                    false, SecretConstants.OWNER_ID, Collections.emptyList());
                        } catch (InterruptedException e2) {
                            logger.error("Sleep interrupted(???)", e2);
                        }
                    }

                    logger.debug("Deleting temporary ZIP");
                    FileUtils.forceDelete(new File("/tmp/mod_yield_police.zip"));
                }
            }
        }

        try (FileWriter writer = new FileWriter("yield_return_orig_police_whitelist.yaml")) {
            new Yaml().dump(newGoodFileList, writer);
        }
    }

    /**
     * Goes across all the zips that are more recent than Crowd Control (502895)
     * and reports all mods that ship with a file that also ships with Celeste or Everest.
     * (That arbitrary limit is here because that rule is not retroactive.)
     */
    private static void checkForForbiddenFiles() throws IOException {
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
            // check for forbidden files in any file more recent that Crowd Control (502895)
            if (Integer.parseInt(file) > 502895) {
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
     */
    private static void checkForDuplicateModIds() throws IOException {
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

                    System.out.println(":warning: Mods **" + mod1.getKey() + "** and **" + mod2.getKey() + "** seem to be using the same mod ID! " +
                            "This is illegal <:landeline:458158726558384149>\n:arrow_right: " +
                            "https://gamebanana.com/" + nameForUrl1 + " and https://gamebanana.com/" + nameForUrl2);

                    // avoid warning about this conflict again.
                    alreadyFoundConflicts.add(Pair.of(mod1.getValue(), mod2.getValue()));
                    alreadyFoundConflicts.add(Pair.of(mod2.getValue(), mod1.getValue()));
                }
            }
        }
    }

    private static Pair<String, String> whichModDoesFileBelongTo(String fileId) throws IOException {
        // load mod list
        List<String> mods;
        try (InputStream is = new FileInputStream("modfilesdatabase/list.yaml")) {
            mods = new Yaml().load(is);
        }

        for (String mod : mods) {
            // load file list for the mod
            String modName;
            List<String> files;
            try (InputStream is = new FileInputStream("modfilesdatabase/" + mod + "/info.yaml")) {
                Map<String, Object> info = new Yaml().load(is);
                modName = info.get("Name").toString();
                files = (List<String>) info.get("Files");
            }

            if (files.contains(fileId)) {
                return Pair.of(modName, mod);
            }
        }

        return null;
    }

    private static void sendAlertToWebhook(String message) throws IOException {
        for (String webhook : SecretConstants.YIELD_RETURN_ALERT_HOOKS) {
            try {
                WebhookExecutor.executeWebhook(webhook,
                        "https://cdn.discordapp.com/avatars/793432836912578570/0a3f716e15c8c3adca6c461c2d64553e.png?size=128",
                        "Banana Watch",
                        message, false, null, Collections.emptyList());
            } catch (InterruptedException e) {
                logger.error("Sleep interrupted(???)", e);
            }
        }
    }
}