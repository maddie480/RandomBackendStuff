package ovh.maddie480.randomstuff.backend.celeste.moddatabase.model;

import java.util.Objects;

public class AuthorRecord {
    public String id;
    public String name;
    public String avatarUrl;
    public String profileUrl;

    public AuthorRecord() {
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        AuthorRecord that = (AuthorRecord) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
