package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.json.JSONObject;
import org.json.JSONTokener;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Mirrors the Lönn versions list API from GitHub (which is severely rate-limited) once an hour.
 */
public class LoennVersionLister {
    public static void update() throws IOException {
        HttpURLConnection connAuth = ConnectionUtils.openConnectionWithTimeout("https://api.github.com/repos/CelestialCartographers/Loenn/releases/latest");
        connAuth.setRequestProperty("Authorization", "Basic " + SecretConstants.GITHUB_BASIC_AUTH);

        JSONObject releaseInfo;
        try (InputStream is = ConnectionUtils.connectionToInputStream(connAuth)) {
            releaseInfo = new JSONObject(new JSONTokener(is));
        }

        // download_count isn't used and creates noise (fake changes to the JSON)
        for (Object asset : releaseInfo.getJSONArray("assets")) {
            ((JSONObject) asset).remove("download_count");
        }

        try (OutputStream os = Files.newOutputStream(Paths.get("/shared/celeste/loenn-versions.json"));
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {

            releaseInfo.write(bw);
        }
    }
}
