package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.ModDatabase;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.ParallelzUtilz;
import ovh.maddie480.randomstuff.backend.utils.ZipFileWithAutoEncoding;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ModCatalogDictionaryGenerator {
    private static final Pattern regex = Pattern.compile("^(?:entities|triggers|style\\.effects)\\.([^.]+)\\.(?:placements\\.)?name(?:\\.[^=]+)?=(.*)$", Pattern.MULTILINE);
    private static final Logger logger = LoggerFactory.getLogger(ModCatalogDictionaryGenerator.class);

    static Map<String, Map<String, String>> generateModCatalogDictionary() throws IOException {
        List<String> toCheck;
        try (ModDatabase database = new ModDatabase()) {
            toCheck = database.allMods.stream()
                    .map(m -> Arrays.stream(m.files)
                            .filter(f -> f.isLeader && Arrays.asList(f.fileListing).contains("Loenn/lang/en_gb.lang"))
                            .map(f -> f.mainUrl)
                            .toList())
                    .flatMap(List::stream)
                    .toList();
        }

        Map<String, Map<String, String>> dictionary = new TreeMap<>();
        ParallelzUtilz.runInParallel(toCheck.stream()
                .<ParallelzUtilz.ExplodyRunnable>map(url -> (() -> {
                    Path tempZip = Files.createTempFile("catalogscan-", ".zip");
                    try {
                        scanFile(dictionary, tempZip, url);
                    } finally {
                        if (Files.exists(tempZip)) Files.delete(tempZip);
                    }
                }))
                .toList());

        return dictionary;
    }

    private static void scanFile(Map<String, Map<String, String>> dictionary, Path tempZip, String url) throws IOException {
        logger.debug("Scanning {}", url);
        ConnectionUtils.runWithRetry(() -> {
            try (InputStream is = ConnectionUtils.openStreamWithTimeout(url);
                 OutputStream os = Files.newOutputStream(tempZip)) {

                IOUtils.copy(is, os);
                return null;
            }
        });

        try (ZipFile file = ZipFileWithAutoEncoding.open(tempZip.toString())) {
            ZipEntry entry = file.getEntry("Loenn/lang/en_gb.lang");
            if (entry == null) return;

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

                    synchronized (dictionary) {
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
    }
}
