package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.apache.commons.collections4.keyvalue.AbstractKeyValue;
import org.apache.commons.collections4.keyvalue.DefaultKeyValue;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.ModDatabase;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.CategoryRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.FileRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.MapEditorRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ModRecord;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * This class generates a JSON file containing all information necessary for the Custom Entity Catalog.
 * This should be run once a day, in the same working directory as the update checker bot.
 * In Maddie's bot setup, it is run every day.
 */
public class CustomEntityCatalogGenerator {
    private static final Logger logger = LoggerFactory.getLogger(CustomEntityCatalogGenerator.class);

    public static void main() throws IOException {
        CustomEntityCatalogGenerator gen = new CustomEntityCatalogGenerator();
        gen.reloadList();

        JSONObject output = new JSONObject();
        output.put("modInfo", gen.modInfo);
        output.put("entityDescriptions", gen.entityDescriptions);
        output.put("lastUpdated", gen.lastUpdated);

        Files.writeString(Paths.get("/shared/celeste/custom-entity-catalog.json"), output.toString(), UTF_8);
        Files.writeString(Paths.get("/shared/celeste/custom-entity-dictionary.csv"),
                gen.fullDictionary.entrySet().stream()
                        .map(entry -> entry.getKey() + ";" + entry.getValue())
                        .collect(Collectors.joining("\n")), UTF_8);
    }

    public static class QueriedModInfo {
        private final String modId;
        private String categoryId;
        private String categoryName;
        private String modName;
        private String modEverestYamlId;
        private String pageUrl;
        private String latestVersion;
        private int dependentCount;
        private final Map<String, List<String>> entityList = new HashMap<>();
        private final Map<String, List<String>> triggerList = new HashMap<>();
        private final Map<String, List<String>> effectList = new HashMap<>();
        private final List<AbstractKeyValue<String, String>> documentationLinks = new ArrayList<>();

        private QueriedModInfo(String modId) {
            this.modId = modId;
        }

        // mandatory getter flood to make org.json serialize the fields

        public String getModId() {
            return modId;
        }

        public String getCategoryId() {
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

        public String getPageUrl() {
            return pageUrl;
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
    private Map<String, Map<String, Map<String, String>>> entityDescriptions = null;
    private ZonedDateTime lastUpdated = null;

    private Map<String, Map<String, String>> dictionary;
    private Set<String> unusedDictionaryKeys;
    private Map<String, String> fullDictionary; // includes generated names, populated as they are assigned

    /**
     * Formats an entity ID: FrostHelper/KeyIce => Key Ice
     *
     * @param input The entity ID
     * @return The name from dictionary if present, or an automatically formatted name,
     * and a map associating each name with its description
     */
    private Pair<String, Map<String, String>> lookUpInDictionary(String input) {
        if (dictionary.containsKey(input)) {
            // the plugin name is in the dictionary
            unusedDictionaryKeys.remove(input);
            String dictionaryEntry = String.join(" / ", dictionary.get(input).keySet());
            if (!dictionary.get(input).isEmpty()) fullDictionary.put(input, dictionaryEntry);
            return Pair.of(dictionaryEntry, dictionary.get(input));
        }

        String origInput = input;

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
        fullDictionary.put(origInput, result);

        return Pair.of(result, Collections.emptyMap());
    }

    /**
     * Loads the Ahorn plugin list, runs some post-processing on it, and puts it in modInfo.
     *
     * @throws IOException If an error occurs while reading the database
     */
    private void reloadList() throws IOException {
        fullDictionary = new TreeMap<>();
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
                Map<String, String> newEntry = new LinkedHashMap<>();
                newEntry.put(entry.getValue(), null);
                if (dictionary.containsKey(entry.getKey())) {
                    logger.info("Value {} from modcatalogdictionary.txt overwrites value {} generated for key {}", newEntry, dictionary.get(entry.getKey()), entry.getKey());
                }
                dictionary.put(entry.getKey(), newEntry);
            }
        }

        modInfo = new ArrayList<>();

        try (ModDatabase database = new ModDatabase()) {
            refreshList(database);

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

            for (QueriedModInfo info : new HashSet<>(modInfo)) {
                logger.debug("Attaching documentation entries, categories and dependent information for {}...", info.modName);

                // if found, attach any docs to it.
                if (documentationLinks.containsKey(info.modEverestYamlId)) {
                    Map<String, String> links = documentationLinks.get(info.modEverestYamlId);
                    for (Map.Entry<String, String> link : links.entrySet()) {
                        info.documentationLinks.add(new DefaultKeyValue<>(link.getKey(), link.getValue()));
                    }
                }

                // count leaders that have this mod as a non-optional dependency.
                info.dependentCount = database.allMods.stream()
                        .mapToInt(m -> Arrays.stream(m.files)
                                .anyMatch(f -> f.isLeader && Arrays.stream(f.dependencies)
                                        .anyMatch(d -> d.name.equals(info.modEverestYamlId)))
                                ? 1 : 0)
                        .sum();
            }
        }

        // sort the list by ascending name.
        modInfo.sort(Comparator.comparing(a -> a.modName.toLowerCase(Locale.ROOT)));

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

    /**
     * Loads the Ahorn plugin list and puts it in modInfo.
     */
    private void refreshList(ModDatabase database) {
        // get the stuff that ships with More Lönn Plugins
        MapEditorRecord mlpStuff = database.allMods.stream()
                .map(m -> Arrays.stream(m.files)
                        .filter(f -> "MoreLoennPlugins".equals(f.modId) && f.isLeader)
                        .findFirst()
                        .map(f -> f.loennEntities)
                        .orElse(null))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow();

        Set<String> mlpEntities = new HashSet<>(List.of(mlpStuff.entities));
        Set<String> mlpTriggers = new HashSet<>(List.of(mlpStuff.triggers));
        Set<String> mlpEffects = new HashSet<>(List.of(mlpStuff.effects));

        logger.debug("Loaded {} entities, {} triggers and {} effects from More Loenn Plugins.", mlpEntities.size(), mlpTriggers.size(), mlpEffects.size());

        entityDescriptions = new HashMap<>();

        for (ModRecord mod : database.allMods) {
            // create a QueriedModInfo for it
            QueriedModInfo thisModInfo = new QueriedModInfo(mod.id);
            thisModInfo.modName = mod.name;

            for (FileRecord file : mod.files) {
                if (file.isLeader && !"MoreLoennPlugins".equals(file.modId)) continue;

                checkMapEditor("ahorn", mod, file.ahornEntities, thisModInfo, mlpEntities, mlpTriggers, mlpEffects);
                checkMapEditor("loenn", mod, file.loennEntities, thisModInfo, Collections.emptySet(), Collections.emptySet(), Collections.emptySet());

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
                    thisModInfo.modEverestYamlId = file.modId;
                    thisModInfo.pageUrl = mod.pageUrl;
                    thisModInfo.latestVersion = file.modVersion;

                    CategoryRecord topCategory = mod.category;
                    while (topCategory.parent != null) topCategory = topCategory.parent;
                    thisModInfo.categoryId = topCategory.id;
                    thisModInfo.categoryName = topCategory.name;
                    break;
                }
            }

            // add the mod to the custom entity catalog if it has any entity.
            if (thisModInfo.modEverestYamlId != null) {
                logger.debug("Found {} entities, {} triggers and {} effects for {}", thisModInfo.entityList.size(), thisModInfo.triggerList.size(), thisModInfo.effectList.size(), thisModInfo.modName);
                modInfo.add(thisModInfo);
            }
        }
    }

    /**
     * Checks whether the given mod has any map editor entities registered for it.
     * If the found entities are also found in More Lönn Plugins (mlp* parameters), an extra "mlp" tag will be added to the editor list.
     *
     * @param editor        The map editor to check
     * @param mod           The itemtype/itemid of the mod
     * @param mapEditorInfo The map editor info retrieved by the updater
     * @param modInfo       The mod info to fill out with any map editor info we found
     */
    private void checkMapEditor(String editor, ModRecord mod, MapEditorRecord mapEditorInfo, QueriedModInfo modInfo,
                                Set<String> mlpEntities, Set<String> mlpTriggers, Set<String> mlpEffects) {

        for (String entity : mapEditorInfo.entities) {
            Pair<String, Map<String, String>> dictionaryEntry = lookUpInDictionary(entity);
            String formatted = dictionaryEntry.getLeft();
            if (formatted.isEmpty()) continue;
            addEntityDescriptionsFrom(mod.id, formatted, dictionaryEntry.getRight());
            if (!modInfo.entityList.containsKey(formatted)) {
                modInfo.entityList.put(formatted, new ArrayList<>(Collections.singletonList(editor)));
            } else {
                modInfo.entityList.get(formatted).add(editor);
            }
            if (mlpEntities.contains(entity)) {
                modInfo.entityList.get(formatted).add("mlp");
            }
        }

        for (String trigger : mapEditorInfo.triggers) {
            Pair<String, Map<String, String>> dictionaryEntry = lookUpInDictionary(trigger);
            String formatted = dictionaryEntry.getLeft();
            if (formatted.isEmpty()) continue;
            addEntityDescriptionsFrom(mod.id, formatted, dictionaryEntry.getRight());
            if (!modInfo.triggerList.containsKey(formatted)) {
                modInfo.triggerList.put(formatted, new ArrayList<>(Collections.singletonList(editor)));
            } else {
                modInfo.triggerList.get(formatted).add(editor);
            }
            if (mlpTriggers.contains(trigger)) {
                modInfo.triggerList.get(formatted).add("mlp");
            }
        }

        for (String effect : mapEditorInfo.effects) {
            Pair<String, Map<String, String>> dictionaryEntry = lookUpInDictionary(effect);
            String formatted = dictionaryEntry.getLeft();
            if (formatted.isEmpty()) continue;
            addEntityDescriptionsFrom(mod.id, formatted, dictionaryEntry.getRight());
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

    private void addEntityDescriptionsFrom(String mod, String fullKey, Map<String, String> namesAndDescriptions) {
        for (Map.Entry<String, String> nameDescriptionPair : namesAndDescriptions.entrySet()) {
            if (nameDescriptionPair.getValue() == null
                || nameDescriptionPair.getKey().equals(nameDescriptionPair.getValue())) continue;

            if (!entityDescriptions.containsKey(mod)) entityDescriptions.put(mod, new HashMap<>());
            if (!entityDescriptions.get(mod).containsKey(fullKey)) entityDescriptions.get(mod).put(fullKey, new HashMap<>());
            entityDescriptions.get(mod).get(fullKey).put(nameDescriptionPair.getKey(), nameDescriptionPair.getValue());
        }
    }
}
