package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.apache.commons.io.function.IORunnable;
import org.apache.commons.io.function.IOSupplier;
import org.json.JSONArray;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.ModDatabase;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.ModUpdater;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ModRecord;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.ParallelzUtilz;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FullMirrorCheck {
    private static final Logger logger = LoggerFactory.getLogger(FullMirrorCheck.class);

    public static void main(String[] args) throws IOException {
        AtomicBoolean allGood = new AtomicBoolean(true);

        final Set<String> filesToBonk = new HashSet<>();

        {
            logger.debug("Checking match between celestemodupdater-storage.0x0a.de and updater database");
            Map<String, String> hashes;
            try (ModDatabase database = new ModDatabase()) {
                hashes = database.listLatestVersions().stream()
                        .collect(Collectors.toMap(
                                mf -> mf.file().mirrorName,
                                mf -> mf.file().xxHash
                        ));
            }
            doTheParallelStuff(hashes.entrySet(), entry -> retryAndCatch(() -> {
                String actualHash;
                try (InputStream is = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://celestemodupdater-storage.0x0a.de/banana-mirror/" + entry.getKey() + ".zip"))) {
                    actualHash = ModUpdater.computeXXHash(is);
                }
                if (!actualHash.equals(entry.getValue())) {
                    logger.error("Hash doesn't match for file {}", entry.getKey());
                    synchronized (filesToBonk) {
                        filesToBonk.add(entry.getKey());
                    }
                    allGood.set(false);
                }
            }, allGood));

            logger.debug("Checking match between mods on all mirrors");
            doTheParallelStuff(hashes.keySet(), entry -> retryAndCatch(() -> compareStreams(() -> {
                try (InputStream i1 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://celestemodupdater-storage.0x0a.de/banana-mirror/" + entry + ".zip"));
                     InputStream i2 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://celestemodupdater-mirror.papyrus.0x0a.de/banana-mirror/" + entry + ".zip"));
                     InputStream i3 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://banana-mirror-mods.celestemods.com/" + entry + ".zip"))) {

                    return compareStreams(Arrays.asList(i1, i2, i3));
                }
            }, entry, filesToBonk, allGood), allGood));
        }

        bonkFiles(filesToBonk,
                (mod, name) -> Arrays.stream(mod.files)
                        .filter(f -> name.equals(f.mirrorName)),
                f -> f.mirrorName = "remirror_" + f.mirrorName + "_please");

        {
            logger.debug("Checking match between screenshots on all mirrors");
            List<String> mirroredScreenshots;
            try (ModDatabase database = new ModDatabase()) {
                mirroredScreenshots = database.allMods.stream()
                        .map(m -> Arrays.stream(m.screenshots)
                                .filter(s -> s.mirrorName != null)
                                .map(s -> s.mirrorName)
                                .toList())
                        .flatMap(List::stream)
                        .toList();
            }

            doTheParallelStuff(mirroredScreenshots, entry -> retryAndCatch(() -> compareStreams(() -> {
                try (InputStream i1 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://celestemodupdater-storage.0x0a.de/banana-mirror-images/" + entry + ".png"));
                     InputStream i2 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://celestemodupdater-mirror.papyrus.0x0a.de/banana-mirror-images/" + entry + ".png"));
                     InputStream i3 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://banana-mirror-images.celestemods.com/" + entry + ".png"))) {

                    return compareStreams(Arrays.asList(i1, i2, i3));
                }
            }, entry, filesToBonk, allGood), allGood));
        }

        bonkFiles(filesToBonk,
                (mod, name) -> Arrays.stream(mod.screenshots)
                        .filter(f -> name.equals(f.mirrorName)),
                f -> f.mirrorName = "remirror_" + f.mirrorName + "_please");

        {
            logger.debug("Checking match between Rich Presence icons on all mirrors");
            List<String> richPresenceIcons = new ArrayList<>();
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://celestemodupdater-storage.0x0a.de/rich-presence-icons/list.json")) {
                JSONArray a = new JSONArray(new JSONTokener(is));
                for (int i = 0; i < a.length(); i++) richPresenceIcons.add(a.getString(i));
            }

            doTheParallelStuff(richPresenceIcons, entry -> retryAndCatch(() -> compareStreams(() -> {
                try (InputStream i1 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://celestemodupdater-storage.0x0a.de/rich-presence-icons/" + entry + ".png"));
                     InputStream i2 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://banana-mirror-rich-presence-icons.celestemods.com/" + entry + ".png"))) {

                    return compareStreams(Arrays.asList(i1, i2));
                }
            }, entry, filesToBonk, allGood), allGood));
        }

        if (!allGood.get()) {
            throw new IOException("Some mirror checks failed! Check logs for more details.");
        }
    }

    private static void retryAndCatch(IORunnable thing, AtomicBoolean allGood) {
        try {
            for (int i = 1; i < 10; i++) {
                try {
                    thing.run();
                    return;
                } catch (IOException e) {
                    Thread.sleep(i * 5000);
                }
            }
            thing.run();
        } catch (Exception e) {
            logger.error("Could not process item", e);
            allGood.set(false);
        }
    }

    private static void compareStreams(IOSupplier<Boolean> checker, String log, Set<String> filesToBonk, AtomicBoolean allGood) throws IOException {
        // if there are differences, try 3 times to be sure this isn't a connection cutting off
        for (int i = 0; i < 3; i++) {
            if (checker.get()) return;
        }

        // the checker returned false 3 times, whoops
        logger.error("Mirrors aren't identical for {}", log);
        allGood.set(false);
        synchronized (filesToBonk) {
            filesToBonk.add(log);
        }
    }

    private static boolean compareStreams(List<InputStream> streams) throws IOException {
        int b;
        while (true) {
            b = streams.getFirst().read();
            for (int i = 1; i < streams.size(); i++) {
                if (streams.get(i).read() != b) return false;
            }
            if (b == -1) return true;
        }
    }

    private static <T> void doTheParallelStuff(Collection<T> items, Consumer<T> processOne) throws IOException {
        ParallelzUtilz.runInParallel(items.stream()
                .<ParallelzUtilz.ExplodyRunnable>map(item -> (() -> processOne.accept(item)))
                .toList());
    }

    // "bonking" the files just involves changing their mirrorName, so that the updater
    // mirrors them again. The next full update will change the mirrorNames back anyway.
    private static <T> void bonkFiles(Set<String> filesToBonk, BiFunction<ModRecord, String, Stream<T>> howToFilter,
                                      Consumer<T> howToBonk) throws IOException {
        if (filesToBonk.isEmpty()) return;

        try (ModDatabase database = new ModDatabase()) {
            for (String file : filesToBonk) {
                T itemToBonk = database.allMods.stream()
                        .map(m -> howToFilter.apply(m, file)
                                .findFirst()
                                .orElse(null))
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);

                if (itemToBonk == null) {
                    logger.warn("Couldn't find file {} to bonk, skipping", file);
                    continue;
                }

                howToBonk.accept(itemToBonk);
                logger.info("Bonked file {}", file);
            }
            database.commit();
            filesToBonk.clear();
        }
    }
}
