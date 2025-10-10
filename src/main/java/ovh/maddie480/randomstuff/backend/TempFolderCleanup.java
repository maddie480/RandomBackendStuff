package ovh.maddie480.randomstuff.backend;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.function.IOConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * This file is run every hour, and deletes all files in /shared/temp that are older than a day,
 * and all log files in /logs that are older than 30 days.
 */
public class TempFolderCleanup {
    private static final Logger logger = LoggerFactory.getLogger(TempFolderCleanup.class);

    public static void cleanUpFolder(String folder, int delayDays, Predicate<Path> extraFilter) throws IOException {
        ConnectionUtils.runWithRetry(() -> {
            doStuffOnOldFiles(folder, delayDays, extraFilter, path -> {
                logger.info("Deleting file {} because it was last modified on {}", path.toAbsolutePath(), Files.getLastModifiedTime(path).toInstant());
                Files.delete(path);
            });
            return null;
        });
    }

    public static void zipUpOldFiles(String folder, int delayDays, Predicate<Path> extraFilter) throws IOException {
        doStuffOnOldFiles(folder, delayDays, extraFilter, path -> {
            // compress the file
            Path destination = path.getParent().resolve(path.getFileName() + ".gz");
            logger.info("Compressing file {} to {} because it was last modified on {}", path.toAbsolutePath(), destination.toAbsolutePath(), Files.getLastModifiedTime(path).toInstant());
            try (InputStream is = Files.newInputStream(path);
                 OutputStream os = Files.newOutputStream(destination);
                 GZIPOutputStream gos = new GZIPOutputStream(os)) {

                IOUtils.copy(is, gos);
            }

            // check for integrity
            try (InputStream is1 = new BufferedInputStream(Files.newInputStream(path));
                 InputStream is2 = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(destination)))) {

                int i1 = is1.read();
                int i2 = is2.read();

                while (i1 != -1 & i2 != -1) {
                    if (i1 != i2) throw new IOException("Compressed data mismatch!");
                    i1 = is1.read();
                    i2 = is2.read();
                }
            }

            // copy last modified date and delete uncompressed file
            Files.setLastModifiedTime(destination, Files.getLastModifiedTime(path));
            Files.delete(path);
        });
    }

    private static void doStuffOnOldFiles(String folder, int delayDays, Predicate<Path> extraFilter, IOConsumer<Path> stuff) throws IOException {
        List<Path> filesToDelete;

        try (Stream<Path> walker = Files.walk(Paths.get(folder))) {
            filesToDelete = walker
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toInstant().isBefore(Instant.now().minus(delayDays, ChronoUnit.DAYS));
                        } catch (IOException e) {
                            logger.warn("Could not read last modified time of file {}!", path.toAbsolutePath(), e);
                            return false;
                        }
                    })
                    .filter(extraFilter)
                    .toList();
        }

        for (Path path : filesToDelete) {
            stuff.accept(path);
        }
    }
}
