package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Looks for bad characters in the mod files database, that would suggest the wrong encoding was used
 * to list the files in the zip (curse you, file encodings!).
 */
public class BadCharactersChecker {
    private static final Logger log = LoggerFactory.getLogger(BadCharactersChecker.class);

    public static void main(String[] args) throws Exception {
        Set<Integer> allowlist = "  /-_.,=$~()'\"!+[]#{}%&;?@^（）\\`—".codePoints().boxed().collect(Collectors.toSet());

        boolean noGood = false;

        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/mod_files_database.zip");
             ZipInputStream zis = new ZipInputStream(is)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (!entry.getName().matches(".*/[0-9]+.yaml$")) continue;

                Set<Integer> badChars = new HashSet<>();
                List<String> fileList = YamlUtil.load(zis);

                for (String line : fileList) {
                    Set<Integer> badCharsLine = line.substring(2).codePoints()
                            .boxed()
                            .filter(i -> !Character.isLetterOrDigit(i) && !allowlist.contains(i))
                            .collect(Collectors.toSet());

                    if (!badCharsLine.isEmpty()) {
                        log.warn("BAD LINE: {}", line);
                        badChars.addAll(badCharsLine);
                    }
                }

                if (!badChars.isEmpty()) {
                    log.warn("BAD: {} => {} / https://gamebanana.com/dl/{}", entry.getName(),
                            badChars.stream().map(Character::toString).collect(Collectors.joining()),
                            entry.getName().substring(entry.getName().lastIndexOf("/") + 1, entry.getName().lastIndexOf(".")));

                    noGood = true;
                }
            }
        }

        if (noGood) {
            throw new Exception("Some suspicious characters were found! Check logs for more details.");
        }
    }
}
