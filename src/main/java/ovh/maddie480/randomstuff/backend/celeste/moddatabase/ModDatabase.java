package ovh.maddie480.randomstuff.backend.celeste.moddatabase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ModRecord;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

public class ModDatabase implements AutoCloseable {
    private static final Yaml yaml;
    private static final Logger logger = LoggerFactory.getLogger(ModDatabase.class);

    static {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(100 * 1024 * 1024);
        loaderOptions.setTagInspector(tag -> tag.matches(ModRecord.class));

        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

        yaml = new Yaml(new Constructor(loaderOptions), new Representer(dumperOptions), dumperOptions, loaderOptions);
    }

    private static final Path lockFile = Paths.get("database_lock");
    private static final Path databaseFile = Paths.get("mod_database.yaml");
    private static final Path tempDatabase = Paths.get("/tmp/mod_database_staging.yaml");

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
            logger.debug("Dumping mod database...");
            try (BufferedWriter bw = Files.newBufferedWriter(tempDatabase, StandardCharsets.UTF_8)) {
                yaml.dump(allMods, bw);
            }

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

    private static boolean tryCreate(Path file) throws IOException {
        try {
            Files.createFile(file);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }

    private static void unstoppableSleep(int delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            // this should never happen anyway
        }
    }
}
