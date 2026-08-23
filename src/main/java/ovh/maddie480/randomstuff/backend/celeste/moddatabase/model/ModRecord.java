package ovh.maddie480.randomstuff.backend.celeste.moddatabase.model;

import java.util.Objects;

public class ModRecord {
    public String id;
    public String pageUrl;
    public String name;
    public String summary;
    public String description;
    public long createdDate;
    public long modifiedDate;
    public long updatedDate;
    public int likes;
    public int views;
    public int downloads;
    public AuthorRecord author;
    public CategoryRecord category;
    public ScreenshotRecord[] screenshots;
    public FileRecord[] files;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ModRecord modRecord = (ModRecord) o;
        return Objects.equals(id, modRecord.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
