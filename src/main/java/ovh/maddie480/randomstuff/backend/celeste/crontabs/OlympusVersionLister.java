package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This class lists all Everest versions, making calls to GitHub and Azure APIs as required.
 * It allows centralizing the logic for version listing, and making as many GitHub API calls as we want without
 * worrying about rate limits.
 */
public class OlympusVersionLister {
    private static final Logger log = LoggerFactory.getLogger(OlympusVersionLister.class);

    private static List<Integer> latestAzureBuilds = new ArrayList<>();

    /**
     * Checks if versions changed, and regenerates the versions list if necessary.
     * Run every 15 minutes.
     */
    public static void checkOlympusVersions() throws IOException {
        Path versionListStateFile = Paths.get("olympus_version_list_state.ser");

        // load state
        if (Files.exists(versionListStateFile)) {
            try (ObjectInputStream is = new ObjectInputStream(Files.newInputStream(versionListStateFile))) {
                latestAzureBuilds = (List<Integer>) is.readObject();
            } catch (ClassNotFoundException e) {
                throw new IOException(e);
            }
        }

        // get the latest Azure builds for main, stable and windows-init branches
        List<Integer> currentAzureBuilds = new ArrayList<>();
        for (String branch : Arrays.asList("main", "stable", "windows-init")) {
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://dev.azure.com/EverestAPI/Olympus/_apis/build/builds?definitions=4&branchName=refs/heads/" + branch + "&statusFilter=completed&resultFilter=succeeded&api-version=5.0")) {
                currentAzureBuilds.addAll(new JSONObject(new JSONTokener(is)).getJSONArray("value")
                        .toList().stream()
                        .map(version -> (int) ((Map<String, Object>) version).get("id"))
                        .collect(Collectors.toList()));
            }
        }

        if (!currentAzureBuilds.equals(latestAzureBuilds)) {
            // one of them changed => trigger an update
            log.info("Updating Olympus versions as the latest versions changed: {} -> {}", latestAzureBuilds, currentAzureBuilds);
            updateOlympusVersions();

            latestAzureBuilds = currentAzureBuilds;
        }

        // save state
        try (ObjectOutputStream os = new ObjectOutputStream(Files.newOutputStream(versionListStateFile))) {
            os.writeObject(latestAzureBuilds);
        }
    }

    private static void updateOlympusVersions() throws IOException {
        List<Map<String, Object>> info = new ArrayList<>();

        for (String branch : Arrays.asList("main", "stable", "windows-init")) {
            JSONObject azureBuilds;
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://dev.azure.com/EverestAPI/Olympus/_apis/build/builds?definitions=4&branchName=refs/heads/" + branch + "&statusFilter=completed&resultFilter=succeeded&api-version=5.0")) {
                azureBuilds = new JSONObject(new JSONTokener(is));
            }

            for (Object b : azureBuilds.getJSONArray("value")) {
                Map<String, Object> entry = new HashMap<>();
                JSONObject build = (JSONObject) b;

                // most of the fields can be determined straight from the build number
                entry.put("branch", branch);
                entry.put("date", build.getString("finishTime"));
                entry.put("version", build.getString("buildNumber"));

                for (String os : Arrays.asList("windows", "macos", "linux")) {
                    entry.put(os + "Download", "https://dev.azure.com/EverestAPI/Olympus/_apis/build/builds/" + build.getInt("id") + "/artifacts?artifactName=" + os + ".main&api-version=5.0&%24format=zip");
                }

                try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://raw.githubusercontent.com/EverestAPI/Olympus/" + build.getString("sourceVersion") + "/changelog.txt")) {
                    String changelogFile = IOUtils.toString(is, StandardCharsets.UTF_8);
                    entry.put("changelog", changelogFile.substring(changelogFile.indexOf("#changelog#") + 11).trim());
                }

                info.add(entry);
            }
        }

        // sort the versions by descending number
        info.sort(Comparator.<Map<String, Object>, String>comparing(build -> (String) build.get("version")).reversed());

        // push to Cloud Storage
        Files.writeString(Paths.get("/shared/celeste/olympus-versions.json"), new JSONArray(info).toString(), StandardCharsets.UTF_8);

        // notify on Discord
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            WebhookExecutor.executeWebhook(webhook,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/update-checker.png",
                    "Everest Update Checker",
                    ":sparkles: Olympus versions were updated. There are now **" + info.size() + "** versions on record.\n" +
                            "Latest Olympus version is **" + info.get(0).get("version") + "** (" + info.get(0).get("branch") + "):\n" + info.get(0).get("changelog"),
                    ImmutableMap.of("X-Everest-Log", "true"));
        }

        UpdateOutgoingWebhooks.changesHappened();
    }
}
