package ovh.maddie480.randomstuff.backend.celeste.moddatabase.model;

import java.util.Objects;

public class ContentWarningRecord {
    public String name;
    public int severity;
    public String severityLabel;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ContentWarningRecord that = (ContentWarningRecord) o;
        return severity == that.severity && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, severity);
    }
}
