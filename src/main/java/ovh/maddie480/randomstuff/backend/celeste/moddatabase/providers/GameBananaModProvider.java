package ovh.maddie480.randomstuff.backend.celeste.moddatabase.providers;

import ovh.maddie480.randomstuff.backend.celeste.moddatabase.ModProvider;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ModRecord;

import java.util.List;

public class GameBananaModProvider implements ModProvider {
    @Override
    public List<ModRecord> incrementalUpdate(long since) {
        return List.of();
    }

    @Override
    public List<ModRecord> fullUpdate() {
        return List.of();
    }
}
