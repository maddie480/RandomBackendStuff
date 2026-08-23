package ovh.maddie480.randomstuff.backend.celeste.moddatabase;

import net.jpountz.xxhash.StreamingXXHash64;
import net.jpountz.xxhash.XXHashFactory;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.*;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.providers.GameBananaModProvider;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ModUpdater {
    private static final List<ModProvider> modProviders = Collections.singletonList(new GameBananaModProvider());
    private static final Logger logger = LoggerFactory.getLogger(ModUpdater.class);
    private static final XXHashFactory xxHashFactory = XXHashFactory.fastestInstance();

    static void main() throws IOException {
        fullUpdate();
    }

    public static void incrementalUpdate() throws IOException {
        long newestModificationInDatabase;
        try (ModDatabase database = new ModDatabase(true)) {
            newestModificationInDatabase = database.allMods.stream()
                    .mapToLong(m -> m.modifiedDate)
                    .max().orElse(0);
        }

        List<ModRecord> mods = new ArrayList<>();
        for (ModProvider modProvider : modProviders) {
            mods.addAll(modProvider.incrementalUpdate(newestModificationInDatabase));
        }
        update(mods, false);
    }

    public static void fullUpdate() throws IOException {
        List<ModRecord> mods = new ArrayList<>();
        for (ModProvider modProvider : modProviders) {
            mods.addAll(modProvider.fullUpdate());
        }
        update(mods, true);
    }

    private static void update(List<ModRecord> incomingMods, boolean replace) throws IOException {
        try (ModDatabase database = new ModDatabase(false)) {
            Map<String, Pair<ModRecord, FileRecord>> knownFiles = toFileMap(database.allMods);
            Map<String, Pair<ModRecord, FileRecord>> incomingFiles = toFileMap(incomingMods);

            Map<String, Pair<ModRecord, FileRecord>> newFiles = new HashMap<>(incomingFiles);
            for (String id : knownFiles.keySet()) newFiles.remove(id);

            logger.debug("Mod listing finished, we have {} incoming files, including {} new ones", incomingFiles.size(), newFiles.size());

            for (FileRecord f : iterateFiles(incomingFiles)) {
                Pair<ModRecord, FileRecord> knownFilePair = knownFiles.get(f.id);
                if (knownFilePair != null) {
                    FileRecord knownFile = knownFilePair.getRight();
                    f.dependencies = knownFile.dependencies;
                    f.optionalDependencies = knownFile.optionalDependencies;
                    f.hasEverestYaml = knownFile.hasEverestYaml;
                    f.modId = knownFile.modId;
                    f.modVersion = knownFile.modVersion;
                    f.isLeader = knownFile.isLeader;
                    f.bannedFromBeingLeader = knownFile.bannedFromBeingLeader;
                    f.fileListing = knownFile.fileListing;
                    f.xxHash = knownFile.xxHash;
                    f.ahornEntities = knownFile.ahornEntities;
                    f.loennEntities = knownFile.loennEntities;
                    f.richPresenceIcons = knownFile.richPresenceIcons;
                }
            }

            final int workerThreads = 20;
            int progress = 0;
            Semaphore limiter = new Semaphore(workerThreads);
            AtomicReference<Exception> whoops = new AtomicReference<>();

            for (Pair<ModRecord, FileRecord> newFile : newFiles.values()) {
                progress++;
                int current = progress;

                // wait for enough threads to be done first...
                limiter.acquireUninterruptibly();

                // launch a new thread
                new Thread(() -> {
                    Path temp = Paths.get("/tmp/updater_download_" + current);
                    try {
                        logger.debug("Processing new file {}/{}", current, newFiles.size());
                        handleNewFile(newFile.getRight(), temp);
                        logger.debug("Processing of file {}/{} finished", current, newFiles.size());
                    } catch (Exception e) {
                        logger.warn("Exception occurred downloading file {}", current);
                        whoops.set(e);
                    } finally {
                        try {
                            if (Files.exists(temp)) Files.delete(temp);
                        } catch (IOException e) { /* welp */ }

                        // we're done
                        limiter.release();
                    }
                }).start();

                // if some thread crashed, no use in running other ones, stop now!
                if (whoops.get() != null) break;
            }

            // wait for EVERY thread to be done
            limiter.acquireUninterruptibly(workerThreads);
            // if a thread crashed, send the exception to the caller
            if (whoops.get() != null)
                throw new IOException("An exception occurred on a file worker thread", whoops.get());

            if (replace) {
                database.allMods.clear();
                database.allMods.addAll(incomingMods);
            } else {
                for (ModRecord incomingMod : incomingMods) {
                    int modIndex = database.allMods.indexOf(incomingMod);
                    if (modIndex == -1) {
                        logger.debug("Adding mod {} to the database", incomingMod.id);
                        database.allMods.add(incomingMod);
                    } else {
                        logger.debug("Updating mod {} in the database", incomingMod.id);
                        database.allMods.set(modIndex, incomingMod);
                    }
                }
            }

            designateTheNewLeaders(database, knownFiles);
        }
    }

    private static Map<String, Pair<ModRecord, FileRecord>> toFileMap(List<ModRecord> modRecords) {
        return modRecords.stream()
                .map(d -> Arrays.stream(d.files).map(f -> Pair.of(d, f)).toList())
                .flatMap(List::stream)
                .collect(Collectors.toMap(f -> f.getRight().id, f -> f));
    }

    private static List<FileRecord> iterateFiles(Map<String, Pair<ModRecord, FileRecord>> files) {
        return files.values().stream()
                .map(Pair::getRight)
                .toList();
    }

    private static void handleNewFile(FileRecord file, Path target) throws IOException {
        // standard "this doesn't have a valid yaml file" values
        file.xxHash = null;
        file.modId = null;
        file.modVersion = null;
        file.dependencies = new DependencyRecord[0];
        file.optionalDependencies = new DependencyRecord[0];
        file.isLeader = false;
        file.bannedFromBeingLeader = false;
        file.hasEverestYaml = false;
        file.fileListing = new String[0];
        file.loennEntities = new MapEditorRecord();
        file.ahornEntities = new MapEditorRecord();
        file.richPresenceIcons = new RichPresenceIconRecord[0];

        for (MapEditorRecord me : Arrays.asList(file.loennEntities, file.ahornEntities)) {
            me.effects = new String[0];
            me.entities = new String[0];
            me.triggers = new String[0];
        }

        logger.debug("Starting download of {}", file.mainUrl);

        for (int i = 1; i <= 10; i++) {
            boolean nonTimeoutHappened = false;

            try {
                HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(file.mainUrl);
                connection.setInstanceFollowRedirects(true);
                connection.getResponseCode();
                nonTimeoutHappened = true; // ... well, we got a response code, at least.

                try (InputStream is = new BufferedInputStream(ConnectionUtils.connectionToInputStream(connection));
                     OutputStream os = new BufferedOutputStream(Files.newOutputStream(target))) {

                    IOUtils.copy(is, os);
                }

                long actualSize = Files.size(target);
                if (file.size != actualSize) {
                    throw new IOException("The announced file size (" + file.size + ") does not match what we got (" + actualSize + ")" +
                            " for file " + file.mainUrl);
                }
                break;
            } catch (IOException e) {
                logger.warn("I/O exception (try {}/10). Server responded at least once: {}", i, nonTimeoutHappened, e);

                if (i == 10) {
                    if (nonTimeoutHappened) {
                        logger.warn("The server responded at least once and we still couldn't get the file! Considering it to be lost...");
                        return;
                    }
                    throw e;
                } else {
                    ModDatabase.unstoppableSleep(i * 5000);
                }
            }
        }

        try (InputStream is = Files.newInputStream(target)) {
            file.xxHash = computeXXHash(is);
        }

        try {
            checkZipSignature(target);
        } catch (IOException e) {
            // invalid zip!
            logger.warn("File {} could not be read as a zip", file.id, e);
            return;
        }

        file.fileListing = FileLister.getFileList(target);
        file.loennEntities = FileLister.listLoennPlugins(target, file.fileListing);
        file.ahornEntities = FileLister.listAhornPlugins(target, file.fileListing);
        file.hasEverestYaml = Arrays.stream(file.fileListing).anyMatch(
                f -> f.equals("everest.yaml") || f.equals("everest.yml"));
        file.richPresenceIcons = RichPresenceIcons.get(file, target);

        if (!file.hasEverestYaml) return;

        try (ZipFile zip = ZipFileWithAutoEncoding.open(target.toAbsolutePath().toString())) {
            ZipEntry everestYaml = zip.getEntry("everest.yaml");
            if (everestYaml == null) everestYaml = zip.getEntry("everest.yml");

            try (InputStream is = zip.getInputStream(everestYaml)) {
                EverestYamlProcessor.parseEverestYamlFromZipFile(is, file);
            }
        }
    }

    private static void designateTheNewLeaders(ModDatabase database, Map<String, Pair<ModRecord, FileRecord>> previousFiles) {
        Map<String, List<Pair<ModRecord, FileRecord>>> filesByModId = toFileMap(database.allMods).values().stream()
                .filter(f -> f.getRight().modId != null && !f.getRight().bannedFromBeingLeader)
                .collect(Collectors.toMap(
                        f -> f.getRight().modId,
                        Collections::singletonList,
                        (f1, f2) -> {
                            List<Pair<ModRecord, FileRecord>> fusion = new ArrayList<>(f1);
                            fusion.addAll(f2);
                            return fusion;
                        }
                ));

        for (Map.Entry<String, List<Pair<ModRecord, FileRecord>>> contestants : filesByModId.entrySet()) {
            ModRecord currentModLeader = null;
            for (Pair<ModRecord, FileRecord> contestant : contestants.getValue()) {
                if (contestant.getRight().isLeader) {
                    currentModLeader = contestant.getLeft();
                    break;
                }
            }
            if (currentModLeader == null) {
                // does the previous status have a leader?
                for (Pair<ModRecord, FileRecord> contestant : previousFiles.values()) {
                    if (contestants.getKey().equals(contestant.getRight().modId) && contestant.getRight().isLeader) {
                        currentModLeader = contestant.getLeft();
                        break;
                    }
                }
            }

            Pair<ModRecord, FileRecord> newLeader = contestants.getValue().getFirst();
            for (Pair<ModRecord, FileRecord> contestant : contestants.getValue()) {
                boolean leaderIsFromCorrectMod = newLeader.getLeft().equals(currentModLeader);
                boolean contestantIsFromCorrectMod = contestant.getLeft().equals(currentModLeader);

                // whoever is from the correct mod wins instantly
                if (leaderIsFromCorrectMod && !contestantIsFromCorrectMod) {
                    continue;
                }
                if (!leaderIsFromCorrectMod && contestantIsFromCorrectMod) {
                    newLeader = contestant;
                    continue;
                }

                // then the most recent one is selected
                if (newLeader.getRight().createdDate < contestant.getRight().createdDate) {
                    newLeader = contestant;
                }
            }

            if (newLeader.getRight().isLeader) continue;

            logger.info("A new leader has been designated for mod ID {}: {}", contestants.getKey(), newLeader.getRight().id);
            for (Pair<ModRecord, FileRecord> contestant : contestants.getValue()) {
                contestant.getRight().isLeader = contestant.getRight().equals(newLeader.getRight());
            }
        }
    }

    private static String computeXXHash(InputStream is) throws IOException {
        StringBuilder xxHash;

        try (StreamingXXHash64 hash64 = xxHashFactory.newStreamingHash64(0)) {
            byte[] buf = new byte[8192];
            while (true) {
                int read = is.read(buf);
                if (read == -1) break;
                hash64.update(buf, 0, read);
            }
            xxHash = new StringBuilder(Long.toHexString(hash64.getValue()));

            // pad it with zeroes
            while (xxHash.length() < 16) xxHash.insert(0, "0");
        }

        return xxHash.toString();
    }

    private static void checkZipSignature(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            byte[] signature = new byte[4];
            int readBytes = is.read(signature);

            if (readBytes < 4
                    || signature[0] != 0x50
                    || signature[1] != 0x4B
                    || signature[2] != 0x03
                    || signature[3] != 0x04) {

                throw new IOException("Bad ZIP signature!");
            }
        }
    }

    static String[] toArray(Collection<String> strings) {
        String[] result = new String[strings.size()];
        strings.toArray(result);
        return result;
    }
}
