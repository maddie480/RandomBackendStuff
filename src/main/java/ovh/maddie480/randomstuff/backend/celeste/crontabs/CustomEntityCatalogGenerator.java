package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.apache.commons.collections4.keyvalue.AbstractKeyValue;
import org.apache.commons.collections4.keyvalue.DefaultKeyValue;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.ModFilesDatabaseBuilder;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import java.io.*;
import java.nio.charset.StandardCharsets;
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
 * In Maddie's bot setup, it is run every day.
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
        dictionary = ModCatalogDictionaryGenerator.generateModCatalogDictionary();

        // download the custom entity catalog dictionary.
        {
            Map<String, String> tempdic = new HashMap<>();
            try {
                tempdic = Arrays.stream(ConnectionUtils.toStringWithTimeout("https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/modcatalogdictionary.txt", UTF_8).split("\n"))
                        .collect(Collectors.toMap(a -> a.substring(0, a.lastIndexOf("=")), a -> a.substring(a.lastIndexOf("=") + 1)));
            } catch (Exception e) {
                logger.warn("Could not fetch dictionary for entity names", e);
            }

            unusedDictionaryKeys = new HashSet<>(tempdic.keySet());
            logger.debug("Loaded mod catalog dictionary with {} entries.", tempdic.size());

            for (Map.Entry<String, String> entry : tempdic.entrySet()) {
                if (dictionary.containsKey(entry.getKey())) {
                    logger.info("Value {} from modcatalogdictionary.txt overwrites value {} generated for key {}", entry.getValue(), dictionary.get(entry.getKey()), entry.getKey());
                }
                dictionary.put(entry.getKey(), entry.getValue());
            }
        }

        modInfo = new ArrayList<>();

        {
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

                // we're expecting - [label :link:](link)
                Pattern linkPattern = Pattern.compile("^- \\[(.*) :link:]\\((.*)\\)$");

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

            logger.debug("{} documentation links loaded.", documentationLinks.size());

            // get the dependency graph.
            Map<String, Map<String, Object>> dependencyGraphYaml;
            try (InputStream is = new FileInputStream("uploads/moddependencygraph.yaml")) {
                dependencyGraphYaml = YamlUtil.load(is);
            }

            logger.debug("Loaded mod dependency graph with {} entries.", documentationLinks.size());

            for (QueriedModInfo info : new HashSet<>(modInfo)) {
                // find the mod name based on GameBanana file URL.
                logger.debug("Attaching documentation entries, categories and dependent information for {}...", info.modName);
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
        }

        // sort the list by ascending name.
        modInfo.sort(Comparator.comparing(a -> a.modName.toLowerCase(Locale.ROOT)));

        // fill out the category IDs for all mods.
        List<Map<String, Object>> modSearchDatabase = loadModSearchDatabase();
        logger.debug("Loaded mod search database with {} entries.", modSearchDatabase.size());

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

        logger.info("Found {} mods.", modInfo.size());
        lastUpdated = ZonedDateTime.now();

        if (!unusedDictionaryKeys.isEmpty()) {
            WebhookExecutor.executeWebhook(
                    SecretConstants.UPDATE_CHECKER_LOGS_HOOK,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/compute-engine.png",
                    "Custom Entity Catalog Generator",
                    ":warning: The following keys are unused in the mod catalog dictionary: `" + String.join("`, `", unusedDictionaryKeys) + "`");
        }
    }

    private static List<Map<String, Object>> loadModSearchDatabase() throws IOException {
        // instead of parsing the entire file and throwing away part of it, parse each entry individually
        // to keep the memory usage as low as possible.
        try (InputStream is = new FileInputStream("uploads/modsearchdatabase.yaml");
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            List<Map<String, Object>> modSearchDatabase = new LinkedList<>();
            StringBuilder entry = new StringBuilder();
            String line;

            while ((line = br.readLine()) != null) {
                if (!entry.isEmpty() && line.startsWith("- ")) {
                    modSearchDatabase.add(parseModSearchDatabaseEntry(entry.toString()));
                    entry.setLength(0);
                }
                entry.append(line).append('\n');
            }

            modSearchDatabase.add(parseModSearchDatabaseEntry(entry.toString()));
            return modSearchDatabase;
        }
    }

    private static Map<String, Object> parseModSearchDatabaseEntry(String entry) throws IOException {
        final Set<String> toKeep = new HashSet<>(Arrays.asList("GameBananaType", "GameBananaId", "CategoryId", "CategoryName"));

        List<Map<String, Object>> parsedEntry;
        try (InputStream is = new ByteArrayInputStream(entry.getBytes(StandardCharsets.UTF_8))) {
            parsedEntry = YamlUtil.load(is);
        }

        return parsedEntry.getFirst().entrySet().stream()
                .filter(e -> toKeep.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map.Entry<String, Map<String, Object>> getUpdateCheckerDatabaseEntry(Map<String, Map<String, Object>> everestUpdateYaml, String fileId) {
        return everestUpdateYaml.entrySet()
                .stream().filter(entry -> entry.getValue().get("URL")
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
            String downloadLink = (String) everestUpdateYaml.get("MoreLoennPlugins").get("URL");

            ConnectionUtils.runWithRetry(() -> {
                try (InputStream is = ConnectionUtils.openStreamWithTimeout(downloadLink)) {
                    FileUtils.copyToFile(is, new File("/tmp/mlp.zip"));
                    return null;
                }
            });

            try (ZipFile file = new ZipFile("/tmp/mlp.zip")) {
                Enumeration<? extends ZipEntry> entries = file.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && entry.getName().startsWith("Loenn/lang/") && entry.getName().endsWith(".lang")) {
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(entry), UTF_8))) {
                            Triple<Set<String>, Set<String>, Set<String>> allStuff = ModFilesDatabaseBuilder.extractLoennEntitiesFromLangFile(br);
                            mlpEntities.addAll(allStuff.getLeft());
                            mlpTriggers.addAll(allStuff.getMiddle());
                            mlpEffects.addAll(allStuff.getRight());
                        }
                    }
                }
            }

            FileUtils.forceDelete(new File("/tmp/mlp.zip"));
        }

        logger.debug("Loaded {} entities, {} triggers and {} effects from More Loenn Plugins.", mlpEntities.size(), mlpTriggers.size(), mlpEffects.size());

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
                Map.Entry<String, Map<String, Object>> databaseEntry = getUpdateCheckerDatabaseEntry(everestUpdateYaml, file);
                if (databaseEntry != null && !databaseEntry.getKey().equals("MoreLoennPlugins")) {
                    checkMapEditor("ahorn", mod, file, thisModInfo, mlpEntities, mlpTriggers, mlpEffects);
                    checkMapEditor("loenn", mod, file, thisModInfo, Collections.emptySet(), Collections.emptySet(), Collections.emptySet());

                    // filter out anything starting with "Sample Entity" and "Sample Trigger"
                    String toRemove;
                    while ((toRemove = thisModInfo.entityList.keySet().stream()
                            .filter(l -> l.startsWith("Sample Entity"))
                            .findFirst().orElse(null)) != null)
                        thisModInfo.entityList.remove(toRemove);

                    while ((toRemove = thisModInfo.triggerList.keySet().stream()
                            .filter(l -> l.startsWith("Sample Trigger"))
                            .findFirst().orElse(null)) != null)
                        thisModInfo.triggerList.remove(toRemove);


                    // check if we found plugins!
                    if (!thisModInfo.entityList.isEmpty() || !thisModInfo.triggerList.isEmpty() || !thisModInfo.effectList.isEmpty()) {
                        thisModInfo.fileId = file;
                        break;
                    }
                }
            }

            // add the mod to the custom entity catalog if it has any entity.
            if (thisModInfo.fileId != null) {
                logger.debug("Found {} entities, {} triggers and {} effects for {}", thisModInfo.entityList.size(), thisModInfo.triggerList.size(), thisModInfo.effectList.size(), thisModInfo.modName);
                modInfo.add(thisModInfo);
            }
        }
    }

    /**
     * Checks whether the given mod has any map editor entities registered for it.
     * If the found entities are also found in More Lönn Plugins (mlp* parameters), an extra "mlp" tag will be added to the editor list.
     *
     * @param editor  The map editor to check
     * @param mod     The itemtype/itemid of the mod
     * @param file    The ID of the file to check
     * @param modInfo The mod info to fill out with any map editor info we found
     * @throws IOException If an error occurs while reading the database
     */
    private void checkMapEditor(String editor, String mod, String file, QueriedModInfo modInfo,
                                Set<String> mlpEntities, Set<String> mlpTriggers, Set<String> mlpEffects) throws IOException {

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
                if (mlpEntities.contains(entity)) {
                    modInfo.entityList.get(formatted).add("mlp");
                }
            }
            for (String trigger : entityList.get("Triggers")) {
                String formatted = formatName(trigger);
                if (!modInfo.triggerList.containsKey(formatted)) {
                    modInfo.triggerList.put(formatted, new ArrayList<>(Collections.singletonList(editor)));
                } else {
                    modInfo.triggerList.get(formatted).add(editor);
                }
                if (mlpTriggers.contains(trigger)) {
                    modInfo.triggerList.get(formatted).add("mlp");
                }
            }
            for (String effect : entityList.get("Effects")) {
                String formatted = formatName(effect);
                if (!modInfo.effectList.containsKey(formatted)) {
                    modInfo.effectList.put(formatted, new ArrayList<>(Collections.singletonList(editor)));
                } else {
                    modInfo.effectList.get(formatted).add(editor);
                }
                if (mlpEffects.contains(effect)) {
                    modInfo.effectList.get(formatted).add("mlp");
                }
            }
        }
    }
}
