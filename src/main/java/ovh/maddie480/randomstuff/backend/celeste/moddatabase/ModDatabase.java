package ovh.maddie480.randomstuff.backend.celeste.moddatabase;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.CategoryRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.FileRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ModRecord;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class ModDatabase implements AutoCloseable {
    private static final Yaml yaml;
    private static final Logger logger = LoggerFactory.getLogger(ModDatabase.class);

    static {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(1024 * 1024 * 1024);
        loaderOptions.setTagInspector(tag -> tag.matches(ModRecord.class));
        loaderOptions.setMaxAliasesForCollections(1_000_000);

        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

        yaml = new Yaml(new Constructor(loaderOptions), new Representer(dumperOptions), dumperOptions, loaderOptions);
    }

    private static final Path lockFile = Paths.get("/shared/celeste/database_lock");
    private static final Path databaseFile = Paths.get("/shared/celeste/mod_database.yaml");
    private static final Path tempDatabase = Paths.get("/tmp/mod_database_staging.yaml");

    static {
        lockFile.toFile().deleteOnExit();
    }

    public final List<ModRecord> allMods;
    private final boolean readOnly;

    public ModDatabase(boolean readOnly) throws IOException {
        this.readOnly = readOnly;
        acquireDatabaseLock();

        try (BufferedReader br = Files.newBufferedReader(databaseFile, StandardCharsets.UTF_8)) {
            logger.debug("Loading mod database...");
            this.allMods = yaml.load(br);
        } catch (IOException e) {
            releaseDatabaseLock();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        if (readOnly) {
            // gotta go fast
            releaseDatabaseLock();
            return;
        }

        try {
            logger.debug("Optimizing mod database...");
            optimize();

            logger.debug("Dumping mod database...");
            try (BufferedWriter bw = Files.newBufferedWriter(tempDatabase, StandardCharsets.UTF_8)) {
                yaml.dump(allMods, bw);
            }

            // the DB is huge, having it twice in memory requires -Xmx3G
            allMods.clear();

            logger.debug("Checking new database integrity...");
            try (BufferedReader br = Files.newBufferedReader(tempDatabase, StandardCharsets.UTF_8)) {
                yaml.load(br);
            }

            logger.debug("Committing...");
            Files.move(tempDatabase, databaseFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            releaseDatabaseLock();
        }
    }

    private static void acquireDatabaseLock() throws IOException {
        logger.debug("Waiting for database lock to be released...");
        while (!tryCreate(lockFile)) unstoppableSleep(1000);
        logger.debug("Acquired database lock!");
    }

    private static void releaseDatabaseLock() throws IOException {
        Files.delete(lockFile);
        logger.debug("Released database lock!");
    }

    private void optimize() {
        // share as many references as possible
        optimizeForMods(m -> m.author, (m, v) -> m.author = v);
        optimizeForMods(m -> m.category, (m, v) -> m.category = v);
        optimizeForFiles(f -> f.dependencies, (f, v) -> f.dependencies = v);
        optimizeForFiles(f -> f.optionalDependencies, (f, v) -> f.optionalDependencies = v);
        optimizeForFiles(f -> f.fileListing, (f, v) -> f.fileListing = v);
        optimizeForFiles(f -> f.ahornEntities, (f, v) -> f.ahornEntities = v);
        optimizeForFiles(f -> f.loennEntities, (f, v) -> f.loennEntities = v);
        optimizeForFiles(f -> f.richPresenceIcons, (f, v) -> f.richPresenceIcons = v);

        List<Pair<Supplier<CategoryRecord>, Consumer<CategoryRecord>>> allCategoriesRecursively = new ArrayList<>();
        for (ModRecord m : allMods) {
            CategoryRecord c = m.category;
            while (c.parent != null) {
                CategoryRecord c1 = c;
                allCategoriesRecursively.add(Pair.of(() -> c1.parent, v -> c1.parent = v));
                c = c.parent;
            }
        }
        optimizeFields(allCategoriesRecursively);
    }

    private <T> void optimizeForMods(Function<ModRecord, T> getter, BiConsumer<ModRecord, T> setter) {
        optimizeFields(allMods.stream()
                .map(m -> Pair.<Supplier<T>, Consumer<T>>of(() -> getter.apply(m), v -> setter.accept(m, v)))
                .toList());
    }

    private <T> void optimizeForFiles(Function<FileRecord, T> getter, BiConsumer<FileRecord, T> setter) {
        optimizeFields(allMods.stream()
                .map(m -> Arrays.stream(m.files)
                        .map(f -> Pair.<Supplier<T>, Consumer<T>>of(() -> getter.apply(f), v -> setter.accept(f, v)))
                        .toList())
                .flatMap(List::stream)
                .toList());
    }

    private <T> void optimizeFields(List<Pair<Supplier<T>, Consumer<T>>> getsetters) {
        List<T> alreadyMet = new ArrayList<>();
        for (Pair<Supplier<T>, Consumer<T>> getsetter : getsetters) {
            T got = getsetter.getLeft().get();
            T existing = alreadyMet.stream()
                    .filter(t -> Objects.deepEquals(t, got))
                    .findFirst().orElse(null);

            if (existing != null) {
                getsetter.getRight().accept(existing);
            } else {
                alreadyMet.add(got);
            }
        }
    }

    private static boolean tryCreate(Path file) throws IOException {
        try {
            Files.createFile(file);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }

    static void unstoppableSleep(int delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            // this should never happen anyway
        }
    }
}
