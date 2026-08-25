package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.FileLister;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.YamlUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class RefreshMapEditorVanillaEntities {
    private static final Logger log = LoggerFactory.getLogger(RefreshMapEditorVanillaEntities.class);

    public static void checkForAhornPlugins() throws IOException {
        log.debug("Loading vanilla map editor plugin info...");

        List<String> ahornEntities = ConnectionUtils.runWithRetry(() -> {
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://raw.githubusercontent.com/CelestialCartographers/Maple/master/src/entity.jl")) {
                List<String> entities = new LinkedList<>();
                FileLister.extractAhornEntities(entities, null, null, "Ahorn/entities/vanilla.jl", is);
                return entities;
            }
        });
        List<String> ahornTriggers = ConnectionUtils.runWithRetry(() -> {
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://raw.githubusercontent.com/CelestialCartographers/Maple/master/src/trigger.jl")) {
                List<String> triggers = new LinkedList<>();
                FileLister.extractAhornEntities(null, triggers, null, "Ahorn/triggers/vanilla.jl", is);
                return triggers;
            }
        });
        List<String> ahornEffects = ConnectionUtils.runWithRetry(() -> {
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://raw.githubusercontent.com/CelestialCartographers/Maple/master/src/style.jl")) {
                List<String> effects = new LinkedList<>();
                FileLister.extractAhornEntities(null, null, effects, "Ahorn/effects/vanilla.jl", is);
                return effects;
            }
        });

        try (OutputStream os = new FileOutputStream(Paths.get("ahorn_vanilla.yaml").toFile())) {
            Map<String, List<String>> ahornPlugins = new HashMap<>();
            ahornPlugins.put("Entities", ahornEntities);
            ahornPlugins.put("Triggers", ahornTriggers);
            ahornPlugins.put("Effects", ahornEffects);
            YamlUtil.dump(ahornPlugins, os);
        }

        ConnectionUtils.runWithRetry(() -> {
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://raw.githubusercontent.com/CelestialCartographers/Loenn/master/src/lang/en_gb.lang");
                 BufferedReader br = new BufferedReader(new InputStreamReader(is));
                 OutputStream os = Files.newOutputStream(Paths.get("loenn_vanilla.yaml"))) {

                Triple<Set<String>, Set<String>, Set<String>> extractedLoennEntities = FileLister.extractLoennEntitiesFromLangFile(br);

                Map<String, List<String>> loennPlugins = new HashMap<>();
                loennPlugins.put("Entities", new ArrayList<>(extractedLoennEntities.getLeft()));
                loennPlugins.put("Triggers", new ArrayList<>(extractedLoennEntities.getMiddle()));
                loennPlugins.put("Effects", new ArrayList<>(extractedLoennEntities.getRight()));
                YamlUtil.dump(loennPlugins, os);

                return null;
            }
        });
    }

}
