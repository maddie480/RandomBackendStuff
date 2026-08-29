package ovh.maddie480.randomstuff.backend.celeste.moddatabase;

import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ModRecord;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface ModProvider {
    /**
     * Updates the database with all changes that happened since the given time.
     */
    List<ModRecord> incrementalUpdate(long since) throws IOException;

    /**
     * Fetches all mods from scratch.
     */
    List<ModRecord> fullUpdate() throws IOException;

    /**
     * Retrieves featured mods (pairs of ID -> featured tier).
     */
    Map<String, Integer> retrieveFeaturedMods() throws IOException;
}
