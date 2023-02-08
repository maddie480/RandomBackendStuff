package com.max480.randomstuff.backend.celeste.crontabs;

import com.max480.everest.updatechecker.ModFilesDatabaseBuilder;
import com.max480.everest.updatechecker.YamlUtil;
import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import com.max480.randomstuff.backend.utils.WebhookExecutor;
import org.apache.commons.collections4.keyvalue.AbstractKeyValue;
import org.apache.commons.collections4.keyvalue.DefaultKeyValue;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * This class generates a JSON file containing all information necessary for the Custom Entity Catalog.
 * This should be run once a day, in the same working directory as the update checker bot.
 * In max480's bot setup, it is run every day.
 */
public class CustomEntityCatalogGenerator {
    private static final Logger logger = LoggerFactory.getLogger(CustomEntityCatalogGenerator.class);

    public static void main(String[] args) throws IOException {
        CustomEntityCatalogGenerator gen = new CustomEntityCatalogGenerator();
        gen.reloadList();

        JSONObject output = new JSONObject();
        output.put("modInfo", gen.modInfo);
        output.put("lastUpdated", gen.lastUpdated);

        Files.writeString(Paths.get("/shared/celeste/custom-entity-catalog.json"), output.toString(), UTF_8);
    }

    public static class QueriedModInfo {
        private final String itemtype;
        private final int itemid;
        private int categoryId;
        private String categoryName;
        private String modName;
        private String modEverestYamlId;
        private String latestVersion;
        private int dependentCount;
        private final Map<String, List<String>> entityList = new HashMap<>();
        private final Map<String, List<String>> triggerList = new HashMap<>();
        private final Map<String, List<String>> effectList = new HashMap<>();
        private final List<AbstractKeyValue<String, String>> documentationLinks = new ArrayList<>();
        private String fileId;

        private QueriedModInfo(String itemtype, int itemid) {
            this.itemtype = itemtype;
            this.itemid = itemid;
        }

        // mandatory getter flood to make org.json serialize the fields

        public String getItemtype() {
            return itemtype;
        }

        public int getItemid() {
            return itemid;
        }

        public int getCategoryId() {
            return categoryId;
        }

        public String getCategoryName() {
            return categoryName;
        }

        public String getModName() {
            return modName;
        }

        public String getModEverestYamlId() {
            return modEverestYamlId;
        }

        public String getLatestVersion() {
            return latestVersion;
        }

        public int getDependentCount() {
            return dependentCount;
        }

        public Map<String, List<String>> getEntityList() {
            return entityList;
        }

        public Map<String, List<String>> getTriggerList() {
            return triggerList;
        }

        public Map<String, List<String>> getEffectList() {
            return effectList;
        }

        public List<AbstractKeyValue<String, String>> getDocumentationLinks() {
            return documentationLinks;
        }
    }

    private List<QueriedModInfo> modInfo = null;
    private ZonedDateTime lastUpdated = null;

    private Map<String, String> dictionary;
    private Set<String> unusedDictionaryKeys;

    /**
     * Formats an entity ID: FrostHelper/KeyIce => Key Ice
     *
     * @param input The entity ID
     * @return The name from dictionary if present, or an automatically formatted name
     */
    private String formatName(String input) {
        if (dictionary.containsKey(input)) {
            // the plugin name is in the dictionary
            unusedDictionaryKeys.remove(input);
            return dictionary.get(input);
        }

        // trim the helper prefix
        if (input.contains("/")) {
            input = input.substring(input.lastIndexOf("/") + 1);
        }

        // replace - and _ with spaces
        input = input.replace('-', ' ').replace('_', ' ');

        // apply the spaced pascal case from Everest
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(input.charAt(i - 1)))
                builder.append(' ');

            if (i != 0 && builder.charAt(builder.length() - 1) == ' ') {
                builder.append(Character.toUpperCase(c));
            } else {
                builder.append(c);
            }
        }

        String result = builder.toString();
        result = result.substring(0, 1).toUpperCase() + result.substring(1);

        return result;
    }

    /**
     * Turns a plural string into singular (game files => game file).
     */
    private static String unpluralize(String s) {
        if (s.endsWith("s")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Loads the Ahorn plugin list, runs some post-processing on it, and puts it in modInfo.
     *
     * @throws IOException If an error occurs while reading the database
     */
    private void reloadList() throws IOException {
        // download the custom entity catalog dictionary.
        {
            Map<String, String> tempdic = new HashMap<>();
            try {
                tempdic = Arrays.stream(ConnectionUtils.toStringWithTimeout("https://raw.githubusercontent.com/max4805/RandomDiscordBots/main/modcatalogdictionary.txt", UTF_8).split("\n"))
                        .collect(Collectors.toMap(a -> a.substring(0, a.lastIndexOf("=")), a -> a.substring(a.lastIndexOf("=") + 1)));
            } catch (Exception e) {
                logger.warn("Could not fetch dictionary for entity names: " + e);
            }
            dictionary = tempdic;
            unusedDictionaryKeys = new HashSet<>(dictionary.keySet());
        }

        modInfo = new ArrayList<>();

        // get the update checker database.
        Map<String, Map<String, Object>> everestUpdateYaml;
        try (InputStream is = new FileInputStream("uploads/everestupdate.yaml")) {
            everestUpdateYaml = YamlUtil.load(is);
        }

        refreshList(everestUpdateYaml);

        // mod name -> (link name, link)
        Map<String, Map<String, String>> documentationLinks = new HashMap<>();

        // get the documentation links on the Everest wiki.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                ConnectionUtils.openStreamWithTimeout("https://raw.githubusercontent.com/wiki/EverestAPI/Resources/Mapping/Helper-Manuals.md")))) {

            // we're expecting - [label](link)
            Pattern linkPattern = Pattern.compile("^- \\[(.*)]\\((.*)\\)$");

            String sectionName = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("## ")) {
                    // we met a section name (mod name): ModName (alias), trim ## and the alias + trim extra spaces.
                    sectionName = line.substring(3).trim();
                    if (sectionName.contains("(")) {
                        sectionName = sectionName.substring(0, sectionName.indexOf("(")).trim();
                    }
                } else if (sectionName != null) {
                    Matcher match = linkPattern.matcher(line.trim());
                    if (match.matches()) {
                        // this is a documentation link, store it.
                        Map<String, String> links = documentationLinks.getOrDefault(sectionName, new LinkedHashMap<>());
                        links.put(match.group(1), match.group(2));
                        documentationLinks.put(sectionName, links);
                    } else {
                        // we ran past the links!
                        sectionName = null;
                    }
                }
            }
        }

        // get the dependency graph.
        Map<String, Map<String, Object>> dependencyGraphYaml;
        try (InputStream is = new FileInputStream("uploads/moddependencygraph.yaml")) {
            dependencyGraphYaml = YamlUtil.load(is);
        }

        for (QueriedModInfo info : new HashSet<>(modInfo)) {
            // find the mod name based on GameBanana file URL.
            Map.Entry<String, Map<String, Object>> updateCheckerDatabaseEntry = getUpdateCheckerDatabaseEntry(everestUpdateYaml, info.fileId);

            // if found, attach any docs to it.
            if (updateCheckerDatabaseEntry != null && documentationLinks.containsKey(updateCheckerDatabaseEntry.getKey())) {
                Map<String, String> links = documentationLinks.get(updateCheckerDatabaseEntry.getKey());
                for (Map.Entry<String, String> link : links.entrySet()) {
                    info.documentationLinks.add(new DefaultKeyValue<>(link.getKey(), link.getValue()));
                }
            }

            if (updateCheckerDatabaseEntry != null) {
                info.modEverestYamlId = updateCheckerDatabaseEntry.getKey();
                info.latestVersion = updateCheckerDatabaseEntry.getValue().get("Version").toString();
            }

            // count dependents using the dependency graph.
            int dependents = 0;
            if (updateCheckerDatabaseEntry != null) {
                for (Map<String, Object> dependencyGraphEntry : dependencyGraphYaml.values()) {
                    if (((Map<String, Object>) dependencyGraphEntry.get("Dependencies")).containsKey(updateCheckerDatabaseEntry.getKey())) {
                        dependents++;
                    }
                }
            }
            info.dependentCount = dependents;
        }

        // sort the list by ascending name.
        modInfo.sort(Comparator.comparing(a -> a.modName.toLowerCase(Locale.ROOT)));

        // fill out the category IDs for all mods.
        List<Map<String, Object>> modSearchDatabase;
        try (InputStream is = new FileInputStream("uploads/modsearchdatabase.yaml")) {
            modSearchDatabase = YamlUtil.load(is);
        }

        for (QueriedModInfo modInfo : modInfo) {
            // by default, the category name will just be the item type.
            modInfo.categoryName = formatGameBananaItemtype(modInfo.itemtype);

            for (Map<String, Object> mod : modSearchDatabase) {
                if (mod.containsKey("CategoryId") && modInfo.itemtype.equals(mod.get("GameBananaType").toString())
                        && Integer.toString(modInfo.itemid).equals(mod.get("GameBananaId").toString())) {

                    // we found the mod in the mod files database and it has a category: fill it in mod info.
                    modInfo.categoryId = (int) mod.get("CategoryId");
                    modInfo.categoryName = unpluralize(mod.get("CategoryName").toString());
                }
            }
        }

        logger.info("Found " + modInfo.size() + " mods.");
        lastUpdated = ZonedDateTime.now();

        if (!unusedDictionaryKeys.isEmpty()) {
            WebhookExecutor.executeWebhook(
                    SecretConstants.UPDATE_CHECKER_LOGS_HOOK,
                    "https://cdn.discordapp.com/attachments/445236692136230943/921309225697804299/compute_engine.png",
                    "Custom Entity Catalog Generator",
                    ":warning: The following keys are unused in the mod catalog dictionary: `" + String.join("`, `", unusedDictionaryKeys) + "`");
        }
    }

    private Map.Entry<String, Map<String, Object>> getUpdateCheckerDatabaseEntry(Map<String, Map<String, Object>> everestUpdateYaml, String fileId) {
        return everestUpdateYaml.entrySet()
                .stream().filter(entry -> entry.getValue().get(com.max480.everest.updatechecker.Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL")
                        .equals("https://gamebanana.com/mmdl/" + fileId)).findFirst().orElse(null);
    }

    /**
     * Formats a GameBanana itemtype (Gamefile => Game file, MapCategory => Map Category).
     */
    private static String formatGameBananaItemtype(String input) {
        // specific formatting for a few categories
        switch (input) {
            case "Gamefile" -> {
                return "Game file";
            }
            case "Wip" -> {
                return "WiP";
            }
            case "Gui" -> {
                return "GUI";
            }
        }

        // apply the spaced pascal case from Everest
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(input.charAt(i - 1)))
                builder.append(' ');

            if (i != 0 && builder.charAt(builder.length() - 1) == ' ') {
                builder.append(Character.toUpperCase(c));
            } else {
                builder.append(c);
            }
        }

        // capitalize the first letter
        String result = builder.toString();
        result = result.substring(0, 1).toUpperCase() + result.substring(1);

        return result;
    }

    /**
     * Loads the Ahorn plugin list and puts it in modInfo.
     *
     * @param everestUpdateYaml The mod updater database contents (everest_update.yaml)
     * @throws IOException If an error occurs while reading the database
     */
    private void refreshList(Map<String, Map<String, Object>> everestUpdateYaml) throws IOException {
        // load the entire mod list
        List<String> mods;
        try (InputStream is = new FileInputStream("modfilesdatabase/list.yaml")) {
            mods = YamlUtil.load(is);
        }

        // get the stuff that ships with More Lönn Plugins
        Set<String> mlpEntities = new HashSet<>();
        Set<String> mlpTriggers = new HashSet<>();
        Set<String> mlpEffects = new HashSet<>();

        {
            String downloadLink = (String) everestUpdateYaml.get("MoreLoennPlugins")
                    .get(com.max480.everest.updatechecker.Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL");

            try (InputStream is = ConnectionUtils.openStreamWithTimeout(downloadLink)) {
                FileUtils.copyToFile(is, new File("/tmp/mlp.zip"));
            }

            try (ZipFile file = new ZipFile("/tmp/mlp.zip")) {
                Enumeration<? extends ZipEntry> entries = file.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && entry.getName().startsWith("Loenn/lang/") && entry.getName().endsWith(".lang")) {
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(entry), UTF_8))) {
                            Triple<Set<String>, Set<String>, Set<String>> allStuff = ModFilesDatabaseBuilder.extractLoennEntities(Paths.get("/tmp/trash.yaml"), br);
                            mlpEntities.addAll(allStuff.getLeft());
                            mlpTriggers.addAll(allStuff.getMiddle());
                            mlpEffects.addAll(allStuff.getRight());
                            FileUtils.forceDelete(new File("/tmp/trash.yaml"));
                        }
                    }
                }
            }

            FileUtils.forceDelete(new File("/tmp/mlp.zip"));
        }

        for (String mod : mods) {
            // load this mod's info
            Map<String, Object> fileInfo;
            try (InputStream is = new FileInputStream("modfilesdatabase/" + mod + "/info.yaml")) {
                fileInfo = YamlUtil.load(is);
            }

            // create a QueriedModInfo for it
            QueriedModInfo thisModInfo = new QueriedModInfo(mod.split("/")[0], Integer.parseInt(mod.split("/")[1]));
            thisModInfo.modName = fileInfo.get("Name").toString();
            List<String> files = (List<String>) fileInfo.get("Files");

            for (String file : files) {
                if (getUpdateCheckerDatabaseEntry(everestUpdateYaml, file) != null) {
                    checkMapEditor("ahorn", mod, file, thisModInfo);
                    checkMapEditor("loenn", mod, file, thisModInfo);

                    // More Lönn Plugins
                    for (String entity : mlpEntities) {
                        String formatted = formatName(entity);
                        if (thisModInfo.entityList.containsKey(formatted)) {
                            thisModInfo.entityList.get(formatted).add("mlp");
                        }
                    }
                    for (String trigger : mlpTriggers) {
                        String formatted = formatName(trigger);
                        if (thisModInfo.triggerList.containsKey(formatted)) {
                            thisModInfo.triggerList.get(formatted).add("mlp");
                        }
                    }
                    for (String effect : mlpEffects) {
                        String formatted = formatName(effect);
                        if (thisModInfo.effectList.containsKey(formatted)) {
                            thisModInfo.effectList.get(formatted).add("mlp");
                        }
                    }

                    // check if we found plugins!
                    if (!thisModInfo.entityList.isEmpty() || !thisModInfo.triggerList.isEmpty() || !thisModInfo.effectList.isEmpty()) {
                        thisModInfo.fileId = file;
                        break;
                    }
                }
            }

            // add the mod to the custom entity catalog if it has any entity.
            if (thisModInfo.fileId != null) {
                modInfo.add(thisModInfo);
            }
        }
    }

    /**
     * Checks whethr the given mod has any map editor entities registered for it.
     *
     * @param editor  The map editor to check
     * @param mod     The itemtype/itemid of the mod
     * @param file    The ID of the file to check
     * @param modInfo The mod info to fill out with any map editor info we found
     * @throws IOException If an error occurs while reading the database
     */
    private void checkMapEditor(String editor, String mod, String file, QueriedModInfo modInfo) throws IOException {
        if (new File("modfilesdatabase/" + mod + "/" + editor + "_" + file + ".yaml").exists()) {
            Map<String, List<String>> entityList;
            try (InputStream is = new FileInputStream("modfilesdatabase/" + mod + "/" + editor + "_" + file + ".yaml")) {
                entityList = YamlUtil.load(is);
            }

            for (String entity : entityList.get("Entities")) {
                String formatted = formatName(entity);
                if (!modInfo.entityList.containsKey(formatted)) {
                    modInfo.entityList.put(formatted, new ArrayList<>(Collections.singletonList(editor)));
                } else {
                    modInfo.entityList.get(formatted).add(editor);
                }
            }
            for (String trigger : entityList.get("Triggers")) {
                String formatted = formatName(trigger);
                if (!modInfo.triggerList.containsKey(formatted)) {
                    modInfo.triggerList.put(formatted, new ArrayList<>(Collections.singletonList(editor)));
                } else {
                    modInfo.triggerList.get(formatted).add(editor);
                }
            }
            for (String effect : entityList.get("Effects")) {
                String formatted = formatName(effect);
                if (!modInfo.effectList.containsKey(formatted)) {
                    modInfo.effectList.put(formatted, new ArrayList<>(Collections.singletonList(editor)));
                } else {
                    modInfo.effectList.get(formatted).add(editor);
                }
            }
        }
    }
}
