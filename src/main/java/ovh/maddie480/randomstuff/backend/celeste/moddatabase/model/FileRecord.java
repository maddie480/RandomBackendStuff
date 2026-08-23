package ovh.maddie480.randomstuff.backend.celeste.moddatabase.model;

import java.util.Objects;

public class FileRecord {
    public String id;
    public String name;
    public String mainUrl;
    public String mirrorName;
    public String description;
    public int size;
    public long createdDate;
    public int downloads;

    // these aren't expected from mod providers, most are filled out by downloading the zip
    public String xxHash;
    public boolean hasEverestYaml;
    public String modId;
    public String modVersion;
    public boolean isLeader;
    public boolean bannedFromBeingLeader;
    public String[] fileListing;
    public DependencyRecord[] dependencies;
    public DependencyRecord[] optionalDependencies;
    public RichPresenceIconRecord[] richPresenceIcons;
    public MapEditorRecord ahornEntities;
    public MapEditorRecord loennEntities;

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
