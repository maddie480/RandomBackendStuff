package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.everest.updatechecker.ZipFileWithAutoEncoding;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ModCatalogDictionaryGenerator {
    private static final Pattern regex = Pattern.compile("^(?:entities|triggers|style\\.effects)\\.([^.]+)\\.(?:placements\\.)?name(?:\\.[^=]+)?=(.*)$", Pattern.MULTILINE);
    private static final Logger logger = LoggerFactory.getLogger(ModCatalogDictionaryGenerator.class);

    static Map<String, String> generateModCatalogDictionary() throws IOException {
        List<String> toCheck;
        try (InputStream is = new FileInputStream("uploads/everestupdate.yaml")) {
            toCheck = YamlUtil.<Map<String, Map<String, Object>>>load(is)
                    .values().stream()
                    .filter(map -> {
                        try (InputStream iss = Files.newInputStream(Paths.get("modfilesdatabase", map.get("GameBananaType").toString(), map.get("GameBananaId").toString(), map.get("GameBananaFileId") + ".yaml"))) {
                            return YamlUtil.<List<String>>load(iss).contains("Loenn/lang/en_gb.lang");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .map(map -> "https://celestemodupdater.0x0a.de/banana-mirror/" + map.get("GameBananaFileId") + ".zip")
                    .toList();
        }

        Map<String, Set<String>> dictionary = new TreeMap<>();

        Path tempZip = Paths.get("/tmp/catalogscanner.zip");

        for (String url : toCheck) {
            logger.debug("Scanning {}", url);
            try (InputStream is = ConnectionUtils.openStreamWithTimeout(url);
                 OutputStream os = Files.newOutputStream(tempZip)) {

                IOUtils.copy(is, os);
            }

            try (ZipFile file = ZipFileWithAutoEncoding.open(tempZip.toString())) {
                ZipEntry entry = file.getEntry("Loenn/lang/en_gb.lang");
                if (entry == null) continue;

                try (InputStream is = file.getInputStream(entry)) {
                    Matcher matcher = regex.matcher(IOUtils.toString(is, StandardCharsets.UTF_8));
                    while (matcher.find()) {
                        if (dictionary.containsKey(matcher.group(1))) {
                            logger.debug("Adding {} => {}", matcher.group(1), matcher.group(2).trim());
                            dictionary.get(matcher.group(1)).add(matcher.group(2).trim());
                        } else {
                            logger.debug("Found {} => {}", matcher.group(1), matcher.group(2).trim());
                            dictionary.put(matcher.group(1), new LinkedHashSet<>(Collections.singleton(matcher.group(2).trim())));
                        }
                    }
                }
            }
        }

        Files.delete(tempZip);

        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : dictionary.entrySet()) {
            result.put(entry.getKey(), String.join(" / ", entry.getValue()));
        }
        return result;
    }
}
