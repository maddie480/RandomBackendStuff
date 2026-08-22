package ovh.maddie480.randomstuff.backend.celeste.moddatabase.model;

import java.util.Objects;

public class FileRecord {
    public String id;
    public String name;
    public String mainUrl;
    public String mirrorName;
    public String description;
    public int size;
    public boolean hasEverestYaml;
    public long createdDate;
    public int downloads;
    public String modId;
    public String supersededByFileId;

    public FileRecord() {
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        FileRecord that = (FileRecord) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
