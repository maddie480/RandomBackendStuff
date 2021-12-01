package com.max480.discord.randombots;

import org.apache.commons.collections4.keyvalue.AbstractKeyValue;
import org.apache.commons.collections4.keyvalue.DefaultKeyValue;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * This class generates a JSON file containing all information necessary for the Custom Entity Catalog.
 * This should be run once a day, in the same working directory as the update checker bot.
 * In max480's bot setup, it is run everyday at midnight French time (10pm or 11pm UTC depending on daylight saving).
 */
public class CustomEntityCatalogGenerator {
    private static final Logger logger = LoggerFactory.getLogger(CustomEntityCatalogGenerator.class);

    public static void main(String[] args) throws IOException {
        CustomEntityCatalogGenerator gen = new CustomEntityCatalogGenerator();
        gen.reloadList();

        JSONObject output = new JSONObject();
        output.put("modInfo", gen.modInfo);
        output.put("lastUpdated", gen.lastUpdated);
        FileUtils.writeStringToFile(new File("uploads/customentitycatalog.json"), output.toString(4), UTF_8);

        CloudStorageUtils.sendToCloudStorage("uploads/customentitycatalog.json", "custom_entity_catalog.json", "application/json");
    }

    public static class QueriedModInfo {
        private String itemtype;
        private int itemid;
        private int categoryId;
        private String categoryName;
        private String modName;
        private String modEverestYamlId;
        private String latestVersion;
        private int dependentCount;
        private Set<String> entityList = new TreeSet<>();
        private Set<String> triggerList = new TreeSet<>();
        private Set<String> effectList = new TreeSet<>();
        private List<AbstractKeyValue<String, String>> documentationLinks = new ArrayList<>();

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

        public Set<String> getEntityList() {
            return entityList;
        }

        public Set<String> getTriggerList() {
            return triggerList;
        }

        public Set<String> getEffectList() {
            return effectList;
        }

        public List<AbstractKeyValue<String, String>> getDocumentationLinks() {
            return documentationLinks;
        }


    }

    private List<QueriedModInfo> modInfo = null;
    private ZonedDateTime lastUpdated = null;

    /**
     * Formats the name of a Ahorn plugin: dummyAhornName.jl => Dummy Ahorn Name
     *
     * @param input      The Ahorn file name
     * @param dictionary A list of all name overrides
     * @return The name from dictionary if present, or an automatically formatted name
     */
    private static String formatName(String input, Map<String, String> dictionary) {
        // trim the .jl
        input = input.substring(0, input.length() - 3);

        if (dictionary.containsKey(input.toLowerCase())) {
            // the plugin name is in the dictionary
            return dictionary.get(input.toLowerCase());
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
        final Map<String, String> dictionary;
        {
            Map<String, String> tempdic = new HashMap<>();
            try {
                tempdic = Arrays.stream(IOUtils.toString(new URL("https://raw.githubusercontent.com/max4805/RandomDiscordBots/main/modcatalogdictionary.txt"), UTF_8).split("\n"))
                        .collect(Collectors.toMap(a -> a.split("=")[0], a -> a.split("=")[1]));
            } catch (Exception e) {
                logger.warn("Could not fetch dictionary for entity names: " + e.toString());
            }
            dictionary = tempdic;
        }

        modInfo = new ArrayList<>();

        refreshList();

        // mod name -> (link name, link)
        Map<String, Map<String, String>> documentationLinks = new HashMap<>();

        // get the documentation links on the Everest wiki.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                ConnectionUtils.openStreamWithTimeout(new URL("https://raw.githubusercontent.com/wiki/EverestAPI/Resources/Mapping/Helper-Manuals.md"))))) {

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

        // get the update checker database.
        Map<String, Map<String, Object>> everestUpdateYaml;
        try (InputStream is = new FileInputStream("uploads/everestupdate.yaml")) {
            everestUpdateYaml = new Yaml().load(is);
        }

        // get the dependency graph.
        Map<String, Map<String, Object>> dependencyGraphYaml;
        try (InputStream is = new FileInputStream("uploads/moddependencygraph.yaml")) {
            dependencyGraphYaml = new Yaml().load(is);
        }

        for (QueriedModInfo info : new HashSet<>(modInfo)) {
            // find the mod name based on GameBanana itemtype/itemid.
            Map.Entry<String, Map<String, Object>> updateCheckerDatabaseEntry = everestUpdateYaml.entrySet()
                    .stream().filter(entry -> info.itemtype.equals(entry.getValue().get("GameBananaType").toString())
                            && info.itemid == (int) entry.getValue().get("GameBananaId")).findFirst().orElse(null);

            // if found, attach any docs to it.
            if (updateCheckerDatabaseEntry != null && documentationLinks.containsKey(updateCheckerDatabaseEntry.getKey())) {
                Map<String, String> links = documentationLinks.get(updateCheckerDatabaseEntry.getKey());
                for (Map.Entry<String, String> link : links.entrySet()) {
                    info.documentationLinks.add(new DefaultKeyValue<>(link.getKey(), link.getValue()));
                }
            }

            // deal with some mods that need special display treatment by hand
            if (info.itemtype.equals("Mod")) {
                switch (info.itemid) {
                    case 53664: // Canyon Helper: one file containing all entities
                        info.entityList.remove("canyon.jl");
                        info.entityList.add("SpinOrb.jl");
                        info.entityList.add("PushBlock.jl");
                        info.entityList.add("ToggleBlock.jl");
                        info.entityList.add("GrannyTemple.jl");
                        info.entityList.add("GrannyMonster.jl");
                        info.entityList.add("GrannyStart.jl");
                        info.entityList.add("UnbreakableStone.jl");

                        info.triggerList.remove("canyon.jl");
                        info.triggerList.add("MonsterTrigger.jl");
                        info.triggerList.add("TempleFallTrigger.jl");

                        break;

                    case 53641: // Cavern Helper: one file containing all entities
                        info.entityList.remove("cavern.jl");
                        info.entityList.add("CrystalBomb.jl");
                        info.entityList.add("IcyFloor.jl");
                        info.entityList.add("FakeCavernHeart.jl");

                        info.triggerList.remove("dialogcutscene.jl");

                        break;

                    case 53645: // Isa's Grab Bag: one file containing all entities
                        info.entityList.remove("isaentities.jl");
                        info.entityList.add("BadelineFriend.jl");
                        info.entityList.add("ColorBlock.jl");
                        info.entityList.add("ColorSwitch.jl");
                        info.entityList.add("WaterBoost.jl");
                        info.entityList.add("Zipline.jl");

                        info.triggerList.remove("isatriggers.jl");
                        info.triggerList.add("CoreWindTrigger.jl");
                        info.triggerList.add("VariantTrigger.jl");
                        info.triggerList.add("ColorSwitchTrigger.jl");

                        break;

                    case 53632: // Outback Helper: one file containing all entities
                        info.entityList.remove("outback.jl");
                        info.entityList.add("MovingTouchSwitch.jl");
                        info.entityList.add("Portal.jl");
                        info.entityList.add("TimedTouchSwitch.jl");

                        break;

                    case 53687: // max480's Helping Hand: common prefix to remove
                        replacePrefix(info, "maxHelpingHand");
                        break;

                    case 53650: // Extended Variant Mode: common prefix to remove
                        replacePrefix(info, "extendedVariants");
                        break;

                    case 53699: // Honly Helper: common prefix to remove
                        replacePrefix(info, "Honly");
                        break;

                    case 320381: // ParrotHelper: common prefix to remove
                        replacePrefix(info, "ParrotHelper");
                        break;

                    case 338413: // Nerd Helper: common prefix to remove
                        replacePrefix(info, "NerdHelper");
                        break;

                    case 53636: // Dialog Textbox Trigger: obsolete
                    case 53659: // Simple Cutscenes: obsolete
                        modInfo.remove(info);
                        break;

                    case 150413: // Early Core: obsolete Dialog Cutscene Trigger
                        info.triggerList.remove("dialogcutscene.jl");
                        if (info.entityList.isEmpty() && info.triggerList.isEmpty() && info.effectList.isEmpty()) {
                            modInfo.remove(info);
                        }
                        break;

                    case 150396: // Crystalized: one file containing all entities
                        info.entityList.remove("featherBarrier.jl");
                        info.entityList.add("CutsceneHelper.jl");
                        info.entityList.add("CraneLift.jl");

                        info.triggerList.remove("trigger.jl");
                        info.triggerList.add("CrystalDialog.jl");
                        info.triggerList.add("RevertCore.jl");
                        info.triggerList.add("SaveCore.jl");
                        info.triggerList.add("HideTheo.jl");
                        break;
                }
            }

            if (info.itemtype.equals("Tool")) {
                switch (info.itemid) {
                    case 6970: // Ahorn Additives: blacklisted on request
                        modInfo.remove(info);
                        break;
                }
            }

            // sort the lists by ascending name.
            info.entityList = info.entityList.stream().map(a -> formatName(a, dictionary)).collect(Collectors.toCollection(TreeSet::new));
            info.triggerList = info.triggerList.stream().map(a -> formatName(a, dictionary)).collect(Collectors.toCollection(TreeSet::new));
            info.effectList = info.effectList.stream().map(a -> formatName(a, dictionary)).collect(Collectors.toCollection(TreeSet::new));

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
            modSearchDatabase = new Yaml().load(is);
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
    }

    /**
     * Formats a GameBanana itemtype (Gamefile => Game file, MapCategory => Map Category).
     */
    private static String formatGameBananaItemtype(String input) {
        // specific formatting for a few categories
        if (input.equals("Gamefile")) {
            return "Game file";
        } else if (input.equals("Wip")) {
            return "WiP";
        } else if (input.equals("Gui")) {
            return "GUI";
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
     * Removes a prefix in all the entities/triggers/effects in a given mod.
     *
     * @param info   The mod to remove the prefix from
     * @param prefix The prefix to remove
     */
    private void replacePrefix(QueriedModInfo info, String prefix) {
        for (String s : new HashSet<>(info.entityList)) {
            if (s.startsWith(prefix)) {
                info.entityList.remove(s);
                s = s.substring(prefix.length());
                info.entityList.add(s);
            }
        }
        for (String s : new HashSet<>(info.triggerList)) {
            if (s.startsWith(prefix)) {
                info.triggerList.remove(s);
                s = s.substring(prefix.length());
                info.triggerList.add(s);
            }
        }
        for (String s : new HashSet<>(info.effectList)) {
            if (s.startsWith(prefix)) {
                info.effectList.remove(s);
                s = s.substring(prefix.length());
                info.effectList.add(s);
            }
        }
    }

    /**
     * Loads the Ahorn plugin list and puts it in modInfo.
     *
     * @throws IOException If an error occurs while reading the database
     */
    private void refreshList() throws IOException {
        // load the entire mod list
        List<String> mods;
        try (InputStream is = new FileInputStream("modfilesdatabase/list.yaml")) {
            mods = new Yaml().load(is);
        }

        for (String mod : mods) {
            // load this mod's info
            Map<String, Object> fileInfo;
            try (InputStream is = new FileInputStream("modfilesdatabase/" + mod + "/info.yaml")) {
                fileInfo = new Yaml().load(is);
            }

            // create a QueriedModInfo for it
            QueriedModInfo thisModInfo = new QueriedModInfo(mod.split("/")[0], Integer.parseInt(mod.split("/")[1]));
            thisModInfo.modName = fileInfo.get("Name").toString();
            List<String> files = (List<String>) fileInfo.get("Files");

            // only show files from the first version listed.
            boolean filesWereAlreadyFound = false;

            for (String file : files) {
                // load the file list for this zip
                List<String> fileList;
                try (InputStream is = new FileInputStream("modfilesdatabase/" + mod + "/" + file + ".yaml")) {
                    fileList = new Yaml().load(is);
                }

                // search for Ahorn plugins if we didn't already find them
                if (!filesWereAlreadyFound) {
                    for (String fileName : fileList) {
                        if (fileName.startsWith("Ahorn/entities/") && fileName.endsWith(".jl")) {
                            thisModInfo.entityList.add(fileName.substring(fileName.lastIndexOf("/") + 1));
                        }
                        if (fileName.startsWith("Ahorn/triggers/") && fileName.endsWith(".jl")) {
                            thisModInfo.triggerList.add(fileName.substring(fileName.lastIndexOf("/") + 1));
                        }
                        if (fileName.startsWith("Ahorn/effects/") && fileName.endsWith(".jl")) {
                            thisModInfo.effectList.add(fileName.substring(fileName.lastIndexOf("/") + 1));
                        }
                    }
                }

                // check if we found plugins!
                if (!thisModInfo.entityList.isEmpty() || !thisModInfo.triggerList.isEmpty() || !thisModInfo.effectList.isEmpty()) {
                    filesWereAlreadyFound = true;
                }
            }

            // add the mod to the custom entity catalog if it has any entity.
            if (filesWereAlreadyFound) {
                modInfo.add(thisModInfo);
            }
        }
    }
}
