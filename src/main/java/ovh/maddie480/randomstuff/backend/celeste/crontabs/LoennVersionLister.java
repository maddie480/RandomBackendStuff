package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.apache.commons.io.IOUtils;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Mirrors the Lönn versions list API from GitHub (which is severely rate-limited) once an hour.
 */
public class LoennVersionLister {
    public static void update() throws IOException {
        HttpURLConnection connAuth = ConnectionUtils.openConnectionWithTimeout("https://api.github.com/repos/CelestialCartographers/Loenn/releases/latest");
        connAuth.setRequestProperty("Authorization", "Basic " + SecretConstants.GITHUB_BASIC_AUTH);

        try (InputStream is = ConnectionUtils.connectionToInputStream(connAuth);
             OutputStream os = Files.newOutputStream(Paths.get("/shared/celeste/loenn-versions.json"))) {

            IOUtils.copy(is, os);
        }
    }
}
