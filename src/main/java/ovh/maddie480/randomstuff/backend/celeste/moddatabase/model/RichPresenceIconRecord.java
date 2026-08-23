package ovh.maddie480.randomstuff.backend.celeste.moddatabase.model;

import java.util.Objects;

public class RichPresenceIconRecord {
    public String path;
    public String xxHash;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        RichPresenceIconRecord that = (RichPresenceIconRecord) o;
        return Objects.equals(path, that.path) && Objects.equals(xxHash, that.xxHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, xxHash);
    }
}
