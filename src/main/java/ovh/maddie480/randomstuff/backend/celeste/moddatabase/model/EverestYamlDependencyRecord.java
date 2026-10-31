package ovh.maddie480.randomstuff.backend.celeste.moddatabase.model;

public class EverestYamlDependencyRecord {
    public String Name;
    public String Version;

    public EverestYamlDependencyRecord() {
    }

    public EverestYamlDependencyRecord(String name, String version) {
        Name = name;
        Version = version;
    }

    public EverestYamlDependencyRecord(EverestYamlEntryRecord entry) {
        this(entry.Name, entry.Version);
    }
}
