package ovh.maddie480.randomstuff.backend.celeste.moddatabase.model;

import java.util.Objects;

public class ScreenshotRecord {
    public String mainUrl;
    public String mirrorName;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ScreenshotRecord that = (ScreenshotRecord) o;
        return Objects.equals(mainUrl, that.mainUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mainUrl);
    }
}
