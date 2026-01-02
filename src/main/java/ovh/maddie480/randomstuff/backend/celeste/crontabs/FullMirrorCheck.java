package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.apache.commons.io.function.IORunnable;
import org.json.JSONArray;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.DatabaseUpdater;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class FullMirrorCheck {
    private static final Logger logger = LoggerFactory.getLogger(FullMirrorCheck.class);

    public static void main(String[] args) throws IOException {
        AtomicBoolean allGood = new AtomicBoolean(true);

        // To avoid cluttering the output, the progress is shown in a window with a progress bar.
        // This only appears if run directly (the crontabs invoke it with args = null),
        // and if the environment can actually display the window.
        boolean popup = args != null && !GraphicsEnvironment.isHeadless();

        {
            logger.debug("Checking match between celestemodupdater-storage.0x0a.de and updater database");
            Map<String, String> hashes;
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/everest_update.yaml")) {
                hashes = YamlUtil.<Map<String, Map<String, Object>>>load(is).values().stream()
                        .collect(Collectors.toMap(
                                v -> "https://celestemodupdater-storage.0x0a.de/banana-mirror/" + v.get("GameBananaFileId") + ".zip",
                                v -> ((List<String>) v.get("xxHash")).getFirst()));
            }
            doTheParallelStuff(hashes.entrySet(), 5, popup, entry -> retryAndCatch(() -> {
                String actualHash;
                try (InputStream is = ConnectionUtils.openStreamWithTimeout(entry.getKey())) {
                    actualHash = DatabaseUpdater.computeXXHash(is);
                }
                if (!actualHash.equals(entry.getValue())) {
                    logger.error("Hash doesn't match for file {}", entry.getKey());
                    allGood.set(false);
                }
            }, allGood));

            logger.debug("Checking match between mods on all mirrors");
            doTheParallelStuff(hashes.keySet(), 5, popup, entry -> retryAndCatch(() -> compareStreams(() -> {
                try (InputStream i1 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout(entry));
                     InputStream i2 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://celestemodupdater-mirror.papyrus.0x0a.de/"
                             + entry.substring("https://celestemodupdater-storage.0x0a.de/".length())));
                     InputStream i3 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://banana-mirror-mods.celestemods.com/"
                             + entry.substring("https://celestemodupdater-storage.0x0a.de/banana-mirror/".length())))) {

                    return compareStreams(Arrays.asList(i1, i2, i3));
                }
            }, "mod " + entry, allGood), allGood));
        }

        {
            logger.debug("Checking match between screenshots on all mirrors");
            List<String> mirroredScreenshots;
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/mod_search_database.yaml")) {
                mirroredScreenshots = YamlUtil.<List<Map<String, Object>>>load(is).stream()
                        .map(item -> (List<String>) item.get("MirroredScreenshots"))
                        .flatMap(List::stream)
                        .collect(Collectors.toList());
            }

            doTheParallelStuff(mirroredScreenshots, 25, popup, entry -> retryAndCatch(() -> compareStreams(() -> {
                try (InputStream i1 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://celestemodupdater-storage.0x0a.de/"
                        + entry.substring("https://celestemodupdater.0x0a.de/".length())));
                     InputStream i2 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://celestemodupdater-mirror.papyrus.0x0a.de/"
                             + entry.substring("https://celestemodupdater.0x0a.de/".length())));
                     InputStream i3 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://banana-mirror-images.celestemods.com/"
                             + entry.substring("https://celestemodupdater.0x0a.de/banana-mirror-images/".length())))) {

                    return compareStreams(Arrays.asList(i1, i2, i3));
                }
            }, "image " + entry, allGood), allGood));
        }

        {
            logger.debug("Checking match between Rich Presence icons on all mirrors");
            List<String> richPresenceIcons = new ArrayList<>();
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://celestemodupdater-storage.0x0a.de/rich-presence-icons/list.json")) {
                JSONArray a = new JSONArray(new JSONTokener(is));
                for (int i = 0; i < a.length(); i++) richPresenceIcons.add(a.getString(i));
            }

            doTheParallelStuff(richPresenceIcons, 25, popup, entry -> retryAndCatch(() -> compareStreams(() -> {
                try (InputStream i1 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://celestemodupdater-storage.0x0a.de/rich-presence-icons/" + entry + ".png"));
                     InputStream i2 = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout("https://banana-mirror-rich-presence-icons.celestemods.com/" + entry + ".png"))) {

                    return compareStreams(Arrays.asList(i1, i2));
                }
            }, "Rich Presence icon " + entry, allGood), allGood));
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

    private static void compareStreams(Supplier<Boolean> checker, String log, AtomicBoolean allGood) {
        // if there are differences, try 3 times to be sure this isn't a connection cutting off
        for (int i = 0; i < 3; i++) {
            if (checker.get()) return;
        }

        // the checker returned false 3 times, whoops
        logger.error("Mirrors aren't identical for {}", log);
        allGood.set(false);
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

    private static <T> void doTheParallelStuff(Collection<T> items, int nb, boolean popup, Consumer<T> processOne) {
        JFrame jf = null;
        JProgressBar progress = null;
        if (popup) {
            jf = new JFrame("Mirror Check");
            jf.add(progress = new JProgressBar(0, items.size()));
            jf.pack();
            jf.setBounds(0, 0, 400, 75);
            jf.setVisible(true);
            progress.setStringPainted(true);
            progress.setFont(progress.getFont().deriveFont(36f));
        }

        final Semaphore sync = new Semaphore(nb);

        int processed = 0;

        for (T item : items) {
            // wait for one of the spots to be available
            sync.acquireUninterruptibly();

            final T thisItem = item;
            new Thread(() -> {
                processOne.accept(thisItem);
                sync.release();
            }).start();

            if (popup) {
                processed++;
                progress.setValue(processed);
                progress.setString(progress.getValue() + "/" + progress.getMaximum());
            }
        }

        // wait for everything to end
        sync.acquireUninterruptibly(nb);

        if (popup) jf.dispose();
    }
}
