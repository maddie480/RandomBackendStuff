package ovh.maddie480.randomstuff.backend.celeste.moddatabase.model;

import java.util.Collections;
import java.util.List;

public class EverestYamlEntryRecord extends EverestYamlDependencyRecord {
    public String DLL;
    public List<EverestYamlDependencyRecord> Dependencies = Collections.emptyList();
    public List<EverestYamlDependencyRecord> OptionalDependencies = Collections.emptyList();
}
