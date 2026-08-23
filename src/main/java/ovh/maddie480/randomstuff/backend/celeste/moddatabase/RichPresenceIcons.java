package ovh.maddie480.randomstuff.backend.celeste.moddatabase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.DatabaseUpdater;
import ovh.maddie480.everest.updatechecker.ZipFileWithAutoEncoding;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.FileRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.RichPresenceIconRecord;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class RichPresenceIcons {
    private static final Logger log = LoggerFactory.getLogger(RichPresenceIcons.class);

    public static RichPresenceIconRecord[] get(FileRecord fileRecord, Path path) throws IOException {
        Set<String> fileList = Set.of(fileRecord.fileListing);

        List<String> richPresenceIcons = Arrays.stream(fileRecord.fileListing)
                .filter(fileName -> fileName.startsWith("Graphics/Atlases/Gui/")
                        && fileName.endsWith(".png")
                        && (fileName.startsWith("Graphics/Atlases/Gui/areas/")
                        || fileList.contains(fileName.substring(0, fileName.length() - 4) + "_back.png"))
                        && !fileName.endsWith("_back.png")
                        && !fileName.endsWith("hover.png"))
                .collect(Collectors.toList());

        if (!richPresenceIcons.isEmpty()) {
            return processNewFile(path, richPresenceIcons);
        }
        return new RichPresenceIconRecord[0];
    }

    private static RichPresenceIconRecord[] processNewFile(Path path, List<String> filesToProcess) throws IOException {
        List<RichPresenceIconRecord> richPresenceIcons = new ArrayList<>();

        // get the files to process from it!
        try (ZipFile zip = ZipFileWithAutoEncoding.open(path.toAbsolutePath().toString())) {
            for (String fileToProcess : filesToProcess) {
                ZipEntry entry = zip.getEntry(fileToProcess);

                // compute the hash to check if we already have the icon.
                String hash;
                try (InputStream is = zip.getInputStream(entry)) {
                    hash = DatabaseUpdater.computeXXHash(is);
                }

                RichPresenceIconRecord richPresenceIcon = new RichPresenceIconRecord();
                richPresenceIcon.path = fileToProcess;
                richPresenceIcon.xxHash = hash;
                richPresenceIcons.add(richPresenceIcon);
            }
        }

        log.debug("Found {} rich presence icons.", richPresenceIcons.size());

        RichPresenceIconRecord[] records = new RichPresenceIconRecord[richPresenceIcons.size()];
        richPresenceIcons.toArray(records);
        return records;
    }
}
