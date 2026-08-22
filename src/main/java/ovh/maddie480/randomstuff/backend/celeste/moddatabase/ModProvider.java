package ovh.maddie480.randomstuff.backend.celeste.moddatabase;

import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ModRecord;

import java.util.List;

public interface ModProvider {
    /**
     * Updates the database with all changes that happened since the given time.
     */
    List<ModRecord> incrementalUpdate(long since);

    /**
     * Fetches all mods from scratch.
     */
    List<ModRecord> fullUpdate();
}
