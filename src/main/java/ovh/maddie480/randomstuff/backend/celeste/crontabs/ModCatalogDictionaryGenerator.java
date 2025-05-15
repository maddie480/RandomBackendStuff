package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.everest.updatechecker.ZipFileWithAutoEncoding;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ModCatalogDictionaryGenerator {
    private static final Pattern regex = Pattern.compile("^(?:entities|triggers|style\\.effects)\\.([^.]+)\\.(?:placements\\.)?name(?:\\.[^=]+)?=(.*)$", Pattern.MULTILINE);
    private static final Logger logger = LoggerFactory.getLogger(ModCatalogDictionaryGenerator.class);

    static Map<String, Map<String, String>> generateModCatalogDictionary() throws IOException {
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

        Map<String, Map<String, String>> dictionary = new TreeMap<>();
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
                        String descriptionKey = matcher.group();
                        descriptionKey = descriptionKey.substring(0, descriptionKey.indexOf("=") + 1).replaceFirst("\\.name\\.", ".description.");
                        String description = null;
                        try (InputStream is2 = file.getInputStream(entry);
                             BufferedReader br2 = new BufferedReader(new InputStreamReader(is2, StandardCharsets.UTF_8))) {

                            String s;
                            while ((s = br2.readLine()) != null) {
                                if (s.toLowerCase().startsWith(descriptionKey.toLowerCase())) {
                                    description = s.substring(descriptionKey.length()).trim();
                                    break;
                                }
                            }
                        }

                        if (dictionary.containsKey(matcher.group(1))) {
                            logger.debug("Adding {} => {} / {}", matcher.group(1), matcher.group(2).trim(), description);
                        } else {
                            logger.debug("Found {} => {} / {}", matcher.group(1), matcher.group(2).trim(), description);
                            dictionary.put(matcher.group(1), new LinkedHashMap<>());
                        }
                        dictionary.get(matcher.group(1)).put(matcher.group(2).trim(), description);
                    }
                }
            }
        }

        Files.delete(tempZip);
        return dictionary;
    }
}
