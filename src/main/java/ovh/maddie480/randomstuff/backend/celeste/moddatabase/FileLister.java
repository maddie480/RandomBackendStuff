package ovh.maddie480.randomstuff.backend.celeste.moddatabase;

import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.FileRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.MapEditorRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ModRecord;
import ovh.maddie480.randomstuff.backend.utils.ZipFileWithAutoEncoding;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FileLister {
    private static final Logger log = LoggerFactory.getLogger(FileLister.class);

    public static String[] getFileList(Path file, ModRecord mod, FileRecord fileR, UpdateCheckerTracker tracker) {
        List<String> filePaths = new LinkedList<>();
        try (ZipFile zipFile = ZipFileWithAutoEncoding.open(file.toAbsolutePath().toString(), tracker, fileR)) {
            final Enumeration<? extends ZipEntry> entriesEnum = zipFile.entries();
            while (entriesEnum.hasMoreElements()) {
                try {
                    ZipEntry entry = entriesEnum.nextElement();
                    if (!entry.isDirectory()) {
                        filePaths.add(entry.getName());
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("Encountered error while going through zip file", e);
                    tracker.zipFileWalkthroughError(mod.pageUrl, fileR.mainUrl, e);
                    return new String[0];
                }
            }

            log.debug("Found {} files in archive.", filePaths.size());
            tracker.scannedZipContents(fileR.mainUrl, filePaths.size());
            return ModUpdater.toArray(filePaths);
        } catch (IOException e) {
            log.warn("Encountered error while going opening zip file", e);
            tracker.zipFileIsUnreadableForFileListing(mod, fileR, e);
            return new String[0];
        }
    }

    public static MapEditorRecord listAhornPlugins(Path zipFilePath, String[] fileList, String fileUrl, UpdateCheckerTracker tracker) {
        if (Arrays.stream(fileList).anyMatch(f -> f.startsWith("Ahorn/"))) {
            List<String> ahornEntities = new LinkedList<>();
            List<String> ahornTriggers = new LinkedList<>();
            List<String> ahornEffects = new LinkedList<>();

            try (ZipFile zipFile = ZipFileWithAutoEncoding.open(zipFilePath.toAbsolutePath().toString())) {
                for (String file : fileList) {
                    if (file.startsWith("Ahorn/") && file.endsWith(".jl")) {
                        InputStream inputStream = zipFile.getInputStream(zipFile.getEntry(file));
                        extractAhornEntities(ahornEntities, ahornTriggers, ahornEffects, file, inputStream);
                    }
                }

                log.debug("Found {} entities, {} triggers, {} effects for Ahorn", ahornEntities.size(), ahornTriggers.size(), ahornEffects.size());

                MapEditorRecord record = new MapEditorRecord();
                record.entities = ModUpdater.toArray(ahornEntities);
                record.triggers = ModUpdater.toArray(ahornTriggers);
                record.effects = ModUpdater.toArray(ahornEffects);
                tracker.scannedAhornEntities(fileUrl, ahornEntities.size(), ahornTriggers.size(), ahornEffects.size());

                return record;
            } catch (IOException | IllegalArgumentException e) {
                // if a file cannot be read as a zip, no need to worry about it.
                // we will just write an empty array.
                log.warn("Could not analyze Ahorn plugins", e);
                tracker.ahornPluginScanError(fileUrl, e);
            }
        } else {
            log.trace("File doesn't have any Ahorn plugin, skipping.");
        }

        MapEditorRecord empty = new MapEditorRecord();
        empty.entities = new String[0];
        empty.triggers = new String[0];
        empty.effects = new String[0];
        return empty;
    }

    public static void extractAhornEntities(List<String> ahornEntities, List<String> ahornTriggers, List<String> ahornEffects,
                                            String file, InputStream inputStream) throws IOException {

        Pattern mapdefMatcher = Pattern.compile(".*@mapdef(?:data)? [A-Za-z]+ \"([^\"]+)\".*");
        Pattern pardefMatcher = Pattern.compile(".*Entity\\(\"([^\"]+)\".*");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                String entityID = null;

                Matcher mapdefMatch = mapdefMatcher.matcher(line);
                if (mapdefMatch.matches()) {
                    entityID = mapdefMatch.group(1);
                }
                Matcher pardefMatch = pardefMatcher.matcher(line);
                if (pardefMatch.matches()) {
                    entityID = pardefMatch.group(1);
                }

                if (entityID != null) {
                    if (file.startsWith("Ahorn/effects/")) {
                        ahornEffects.add(entityID);
                    } else if (file.startsWith("Ahorn/entities/")) {
                        ahornEntities.add(entityID);
                    } else if (file.startsWith("Ahorn/triggers/")) {
                        ahornTriggers.add(entityID);
                    }
                }
            }
        }
    }

    public static MapEditorRecord listLoennPlugins(Path zipFilePath, String[] fileList, String fileUrl, UpdateCheckerTracker tracker) {
        if (Arrays.stream(fileList).anyMatch(f -> f.startsWith("Loenn/"))) {
            Set<String> loennEntities = new HashSet<>();
            Set<String> loennTriggers = new HashSet<>();
            Set<String> loennEffects = new HashSet<>();

            // extract the en_gb.lang file
            try (ZipFile zipFile = ZipFileWithAutoEncoding.open(zipFilePath.toAbsolutePath().toString())) {
                for (String file : fileList) {
                    if (file.startsWith("Loenn/") && file.endsWith(".lua")) {
                        InputStream inputStream = zipFile.getInputStream(zipFile.getEntry(file));
                        extractLoennEntitiesFromPlugin(loennEntities, loennTriggers, loennEffects, file, inputStream);
                    }

                    if (file.equals("Loenn/lang/en_gb.lang")) {
                        try (InputStream inputStream = zipFile.getInputStream(zipFile.getEntry("Loenn/lang/en_gb.lang"));
                             BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

                            Triple<Set<String>, Set<String>, Set<String>> extractedLoennEntities = extractLoennEntitiesFromLangFile(br);
                            loennEntities.addAll(extractedLoennEntities.getLeft());
                            loennTriggers.addAll(extractedLoennEntities.getMiddle());
                            loennEffects.addAll(extractedLoennEntities.getRight());
                        }
                    }
                }

                log.debug("Found {} entities, {} triggers, {} effects for Lönn", loennEntities.size(), loennTriggers.size(), loennEffects.size());
                tracker.scannedLoennEntities(fileUrl, loennEntities.size(), loennTriggers.size(), loennEffects.size());

                MapEditorRecord record = new MapEditorRecord();
                record.entities = ModUpdater.toArray(loennEntities);
                record.triggers = ModUpdater.toArray(loennTriggers);
                record.effects = ModUpdater.toArray(loennEffects);
                return record;
            } catch (IOException | IllegalArgumentException e) {
                // if a file cannot be read as a zip, no need to worry about it.
                // we will just write an empty array.
                log.warn("Could not analyze Lönn plugins");
                tracker.loennPluginScanError(fileUrl, e);
            }
        } else {
            log.trace("File doesn't have any Loenn plugin, skipping.");
        }

        MapEditorRecord empty = new MapEditorRecord();
        empty.entities = new String[0];
        empty.triggers = new String[0];
        empty.effects = new String[0];
        return empty;
    }

    public static Triple<Set<String>, Set<String>, Set<String>> extractLoennEntitiesFromLangFile(BufferedReader inputReader) throws IOException {
        Set<String> loennEntities = new HashSet<>();
        Set<String> loennTriggers = new HashSet<>();
        Set<String> loennEffects = new HashSet<>();

        // read line per line, and extract the entity ID from each line starting with entities., triggers. or style.effects.
        Pattern regex = Pattern.compile("^(entities|triggers|style\\.effects)\\.([^.]+)\\..*$");

        String line;
        while ((line = inputReader.readLine()) != null) {
            Matcher match = regex.matcher(line);
            if (match.matches()) {
                String entityName = match.group(2);
                switch (match.group(1)) {
                    case "entities" -> loennEntities.add(entityName);
                    case "triggers" -> loennTriggers.add(entityName);
                    case "style.effects" -> loennEffects.add(entityName);
                }
            }
        }

        return Triple.of(loennEntities, loennTriggers, loennEffects);
    }

    private static void extractLoennEntitiesFromPlugin(Set<String> loennEntities, Set<String> loennTriggers, Set<String> loennEffects,
                                                       String file, InputStream inputStream) throws IOException {

        // match on: name = "[something]/[something]" :david_goodenough:
        Pattern nameMatcher = Pattern.compile(".*name = [^\"]*\"([^/\" ]+/[^\" ]+)\".*");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                Matcher nameMatch = nameMatcher.matcher(line);
                if (nameMatch.matches()) {
                    String entityID = nameMatch.group(1);

                    if (file.startsWith("Loenn/effects/")) {
                        loennEffects.add(entityID);
                    } else if (file.startsWith("Loenn/entities/")) {
                        loennEntities.add(entityID);
                    } else if (file.startsWith("Loenn/triggers/")) {
                        loennTriggers.add(entityID);
                    }
                }
            }
        }
    }
}
