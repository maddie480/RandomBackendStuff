package com.max480.discord.randombots;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * A script that downloads every mod with a DLL and decompiles it looking for a "yield return orig.Invoke",
 * because mods shouldn't use those. Intended to be run periodically.
 * <p>
 * If a mod is okay, its file ID will be saved to a yaml file and it won't be downloaded again.
 * Otherwise, webhooks will be called to warn some people about the mod.
 */
public class YieldReturnOrigPolice {
    private static final Logger logger = LoggerFactory.getLogger(YieldReturnOrigPolice.class);

    public static void main(String[] args) throws IOException {
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
                            for (String webhook : SecretConstants.YIELD_RETURN_ALERT_HOOKS) {
                                try {
                                    if (yieldReturnIssue) {
                                        WebhookExecutor.executeWebhook(webhook,
                                                "https://cdn.discordapp.com/avatars/793432836912578570/0a3f716e15c8c3adca6c461c2d64553e.png?size=128",
                                                "Banana Watch",
                                                ":warning: The mod called **" + modName + "** uses `yield return orig(self)`!" +
                                                        " This is illegal <:landeline:458158726558384149>\n:arrow_right: https://gamebanana.com/"
                                                        + mod.get("GameBananaType").toString().toLowerCase() + "s/" + mod.get("GameBananaId"),
                                                false, null, Collections.emptyList());
                                    }
                                    if (intPtrIssue) {
                                        WebhookExecutor.executeWebhook(webhook,
                                                "https://cdn.discordapp.com/avatars/793432836912578570/0a3f716e15c8c3adca6c461c2d64553e.png?size=128",
                                                "Banana Watch",
                                                ":warning: The mod called **" + modName + "** might be using the `IntPtr` trick to call base methods!" +
                                                        " This is illegal <:landeline:458158726558384149>\n:arrow_right: https://gamebanana.com/"
                                                        + mod.get("GameBananaType").toString().toLowerCase() + "s/" + mod.get("GameBananaId"),
                                                false, null, Collections.emptyList());
                                    }
                                } catch (InterruptedException e) {
                                    logger.error("Sleep interrupted(???)", e);
                                }
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
}
