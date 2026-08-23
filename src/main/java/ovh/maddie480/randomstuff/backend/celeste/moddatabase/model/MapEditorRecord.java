package ovh.maddie480.randomstuff.backend.celeste.moddatabase.model;

import java.util.Arrays;
import java.util.Objects;

public class MapEditorRecord {
    public String[] entities;
    public String[] triggers;
    public String[] effects;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        MapEditorRecord that = (MapEditorRecord) o;
        return Objects.deepEquals(entities, that.entities) && Objects.deepEquals(triggers, that.triggers) && Objects.deepEquals(effects, that.effects);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(entities), Arrays.hashCode(triggers), Arrays.hashCode(effects));
    }
}
