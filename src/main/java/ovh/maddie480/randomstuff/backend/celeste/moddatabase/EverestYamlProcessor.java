package ovh.maddie480.randomstuff.backend.celeste.moddatabase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.DependencyRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.FileRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ModRecord;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class EverestYamlProcessor {
    private static final Logger logger = LoggerFactory.getLogger(EverestYamlProcessor.class);

    public static void parseEverestYamlFromZipFile(InputStream yamlInputStream, FileRecord fileRecord) {
        try {
            List<Map<String, Object>> info = YamlUtil.loadNoFloats(yamlInputStream);

            for (Map<String, Object> infoMod : info) {
                String modName = infoMod.get("Name").toString();

                // some mods have no Version field, it would be a shame to exclude them though
                String modVersion;
                if (infoMod.containsKey("Version")) {
                    modVersion = infoMod.get("Version").toString();
                } else {
                    modVersion = "NoVersion";
                }

                List<DependencyRecord> dependencies = new ArrayList<>();
                List<DependencyRecord> optionalDependencies = new ArrayList<>();

                // merge the Dependencies and OptionalDependencies of all mods defined in the everest.yaml
                for (Map<String, Object> yamlEntry : info) {
                    if (yamlEntry.containsKey("Dependencies")) {
                        addDependenciesFromList(dependencies, (List<Map<String, Object>>) yamlEntry.get("Dependencies"), info);
                    }
                    if (yamlEntry.containsKey("OptionalDependencies")) {
                        addDependenciesFromList(optionalDependencies, (List<Map<String, Object>>) yamlEntry.get("OptionalDependencies"), info);
                    }
                }

                logger.info("Reading everest.yaml of file {} finished: name {}, version {}, {} dependencies, {} optional dependencies.",
                        fileRecord.id, modName, modVersion, dependencies.size(), optionalDependencies.size());

                fileRecord.modId = modName;
                fileRecord.modVersion = modVersion;
                fileRecord.dependencies = toArray(dependencies);
                fileRecord.optionalDependencies = toArray(optionalDependencies);
            }
        } catch (Exception e) {
            logger.warn("Error while reading the YAML file from {}", fileRecord.id, e);
            fileRecord.modId = null;
            fileRecord.modVersion = null;
            fileRecord.dependencies = new DependencyRecord[0];
            fileRecord.optionalDependencies = new DependencyRecord[0];
        }
    }

    private static void addDependenciesFromList(List<DependencyRecord> addTo, List<Map<String, Object>> toAdd, List<Map<String, Object>> everestYamlContents) {
        for (Map<String, Object> dependencyEntry : toAdd) {
            DependencyRecord record = new DependencyRecord();
            record.name = dependencyEntry.get("Name").toString();
            record.version = dependencyEntry.getOrDefault("Version", "NoVersion").toString();

            // only keep the dependencies if they weren't already added, and they aren't defined in the same yaml file.
            if (!addTo.contains(record) && everestYamlContents.stream().noneMatch(entry -> record.name.equals(entry.get("Name").toString()))) {
                addTo.add(record);
            }
        }
    }

    private static DependencyRecord[] toArray(Collection<DependencyRecord> deprecs) {
        DependencyRecord[] result = new DependencyRecord[deprecs.size()];
        deprecs.toArray(result);
        return result;
    }
}
