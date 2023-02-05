package com.max480.randomstuff.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This file is run every hour, and deletes all files in /shared/temp that are older than a day,
 * and all log files in /logs that are older than 30 days.
 */
public class TempFolderCleanup {
    private static final Logger logger = LoggerFactory.getLogger(TempFolderCleanup.class);

    public static void cleanUpFolder(String folder, int delayDays, Predicate<Path> extraFilter) throws IOException {
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
                    .collect(Collectors.toList());
        }

        for (Path path : filesToDelete) {
            logger.info("Deleting file {} because it was last modified on {}", path.toAbsolutePath(), Files.getLastModifiedTime(path).toInstant());
            Files.delete(path);
        }
    }
}
