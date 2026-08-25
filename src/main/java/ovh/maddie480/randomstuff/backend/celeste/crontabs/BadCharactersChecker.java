package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.ModDatabase;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.FileRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ModRecord;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Looks for bad characters in the mod files database, that would suggest the wrong encoding was used
 * to list the files in the zip (curse you, file encodings!).
 */
public class BadCharactersChecker {
    private static final Logger log = LoggerFactory.getLogger(BadCharactersChecker.class);

    public static void main() throws Exception {
        Set<Integer> allowlist = "  /-_.,=$~()'\"!+[]#{}%&;:?@^（）\\`—–，⣆！￥…？".codePoints().boxed().collect(Collectors.toSet());

        boolean noGood = false;

        try (ModDatabase database = new ModDatabase()) {
            for (ModRecord mod : database.allMods) {
                for (FileRecord file : mod.files) {
                    Set<Integer> badChars = new HashSet<>();
                    for (String line : file.fileListing) {
                        Set<Integer> badCharsLine = line.codePoints()
                                .boxed()
                                .filter(i -> !Character.isLetterOrDigit(i) && !allowlist.contains(i))
                                .collect(Collectors.toSet());

                        if (!badCharsLine.isEmpty()) {
                            log.warn("BAD LINE: {}", line);
                            badChars.addAll(badCharsLine);
                        }
                    }

                    if (!badChars.isEmpty()) {
                        log.warn("BAD: {} => {} / {}", file.id,
                                badChars.stream().map(Character::toString).collect(Collectors.joining()),
                                file.mainUrl);

                        noGood = true;
                    }
                }
            }

            if (noGood) {
                throw new Exception("Some suspicious characters were found! Check logs for more details.");
            }
        }
    }
}
