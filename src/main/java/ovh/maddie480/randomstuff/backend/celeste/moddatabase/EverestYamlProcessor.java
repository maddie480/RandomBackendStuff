package ovh.maddie480.randomstuff.backend.celeste.moddatabase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.*;
import ovh.maddie480.randomstuff.backend.utils.YamlUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class EverestYamlProcessor {
    private static final Logger logger = LoggerFactory.getLogger(EverestYamlProcessor.class);

    private static final Set<String> blacklistedMods = new HashSet<>(Arrays.asList(
            // reserved
            "Celeste",
            "Everest",
            "EverestCore",
            // part of other mods
            "CrowControl-WS", // Crow Control
            "CrowControl-WebSockets", // Crow Control
            "GhostMod" // GhostNet
    ));

    public static void parseEverestYamlFromZipFile(InputStream yamlInputStream, ModRecord modRecord, FileRecord fileRecord, UpdateCheckerTracker tracker) {
        try {
            List<EverestYamlEntryRecord> info = YamlUtil.loadEverestYaml(yamlInputStream);

            for (EverestYamlEntryRecord infoMod : info) {
                if (infoMod.Name == null) {
                    throw new IOException("One of the entries in the everest.yaml file has no Name");
                }

                if (blacklistedMods.contains(infoMod.Name)) {
                    logger.warn("Skipping mod {} because it is in the blacklist.", infoMod.Name);
                    tracker.modIsExcludedByName(infoMod.Name);
                    continue;
                }

                List<DependencyRecord> dependencies = new ArrayList<>();
                List<DependencyRecord> optionalDependencies = new ArrayList<>();

                // merge the Dependencies and OptionalDependencies of all mods defined in the everest.yaml
                for (EverestYamlEntryRecord yamlEntry : info) {
                    addDependenciesFromList(dependencies, yamlEntry.Dependencies, info);
                    addDependenciesFromList(optionalDependencies, yamlEntry.OptionalDependencies, info);
                }

                logger.info("Reading everest.yaml of file {} finished: name {}, version {}, {} dependencies, {} optional dependencies.",
                        fileRecord.id, infoMod.Name, infoMod.Version, dependencies.size(), optionalDependencies.size());
                tracker.scannedModDependencies(infoMod.Name, dependencies.size(), optionalDependencies.size());

                fileRecord.modId = infoMod.Name;
                fileRecord.modVersion = infoMod.Version;
                fileRecord.dependencies = toArray(dependencies);
                fileRecord.optionalDependencies = toArray(optionalDependencies);
                break;
            }
        } catch (Exception e) {
            logger.warn("Error while reading the YAML file from {}", fileRecord.id, e);
            tracker.yamlFileIsUnreadable(modRecord, fileRecord, e);
            fileRecord.modId = null;
            fileRecord.modVersion = null;
            fileRecord.dependencies = new DependencyRecord[0];
            fileRecord.optionalDependencies = new DependencyRecord[0];
        }
    }

    private static void addDependenciesFromList(List<DependencyRecord> addTo, List<EverestYamlDependencyRecord> toAdd, List<EverestYamlEntryRecord> everestYamlContents) {
        for (EverestYamlDependencyRecord dependencyEntry : toAdd) {
            DependencyRecord record = new DependencyRecord();
            record.name = dependencyEntry.Name;
            record.version = dependencyEntry.Version;

            // only keep the dependencies if they weren't already added, and they aren't defined in the same yaml file.
            if (!addTo.contains(record) && everestYamlContents.stream().noneMatch(entry -> record.name.equals(entry.Name))) {
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
