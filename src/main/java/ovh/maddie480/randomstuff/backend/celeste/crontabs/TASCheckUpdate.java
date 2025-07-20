package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.BananaMirrorUploader;
import ovh.maddie480.everest.updatechecker.Main;
import ovh.maddie480.everest.updatechecker.ServerConfig;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.OutputStreamLogger;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static ovh.maddie480.randomstuff.backend.celeste.crontabs.EverestVersionLister.authenticatedGitHubRequest;

public class TASCheckUpdate {
    private static final Logger log = LoggerFactory.getLogger(TASCheckUpdate.class);

    private static final Path[] FILE_PATHS = new Path[]{
            Paths.get("/tmp/Everest/.github/workflows/tas-sync-check.yml"),
            Paths.get("/tmp/Everest/.github/tas-check/run-locally.sh"),
            Paths.get("/tmp/Everest/.github/tas-check/2-2-install-inner.sh")
    };
    private static final Path SJ_BUNDLE = Paths.get("/tmp/sjbundle.zip");

    /*
        variables:
        {{CelesteTAS-SHA}}
        {{StrawberryJamTAS-SHA}}
        {{CelesteTAS-Ver}}
        {{StrawberryJamBundle-CRC}}
     */
    private static final String[] FILE_TEMPLATES = new String[]{
            """
name: TAS Sync Check

on:
  pull_request:
    branches: [dev]

jobs:
  check:
    strategy:
      matrix:
        tas:
          - name: Celeste 100%
            url: "https://github.com/VampireFlower/CelesteTAS/archive/{{CelesteTAS-SHA}}.zip"
            path: "CelesteTAS-{{CelesteTAS-SHA}}/0 - 100%.tas"

          - name: Strawberry Jam All Levels
            url: "https://github.com/VampireFlower/StrawberryJamTAS/archive/{{StrawberryJamTAS-SHA}}.zip"
            path: "StrawberryJamTAS-{{StrawberryJamTAS-SHA}}/0-SJ All Levels.tas"
            bundle: "https://celestemodupdater.0x0a.de/pinned-mods/StrawberryJam2021-Bundle-{{StrawberryJamBundle-CRC}}.zip"

    runs-on: ubuntu-latest
    timeout-minutes: 60
    name: ${{ matrix.tas.name }}

    steps:
    - uses: actions/checkout@v4

    - name: Set up Docker Buildx
      uses: docker/setup-buildx-action@v3

    - name: Wait for Azure build on commit ${{ github.sha }}
      run: cd .github/tas-check && ./1-get-build-url.sh "${{ github.sha }}"

    - name: Install Everest and required mods
      run: cd .github/tas-check && ./2-1-install.sh "${{ matrix.tas.url }}" "${{ matrix.tas.bundle }}"

    - name: Run TAS at ${{ matrix.tas.path }}
      run: cd .github/tas-check && ./3-run.sh "${{ matrix.tas.path }}"
""",
            """
#!/bin/bash
# Builds and runs the TAS locally, calling the same scripts as the pipeline in order to test them.
# Requires Docker and jq.

if [ "$1" == "" ] || [ "$2" == "" ]; then
    echo "Usage: ./run-locally.sh [Celeste|StrawberryJam2021] [commit SHA]"
    exit 1
fi

set -xeo pipefail

case "$1" in
    "Celeste")
        TAS_URL="https://github.com/VampireFlower/CelesteTAS/archive/{{CelesteTAS-SHA}}.zip"
        TAS_PATH="CelesteTAS-{{CelesteTAS-SHA}}/0 - 100%.tas"
        ;;

    "StrawberryJam2021")
        TAS_URL="https://github.com/VampireFlower/StrawberryJamTAS/archive/{{StrawberryJamTAS-SHA}}.zip"
        TAS_PATH="StrawberryJamTAS-{{StrawberryJamTAS-SHA}}/0-SJ All Levels.tas"
        BUNDLE_DOWNLOAD="https://celestemodupdater.0x0a.de/pinned-mods/StrawberryJam2021-Bundle-{{StrawberryJamBundle-CRC}}.zip"
        ;;

    *)
        echo "Unknown TAS: $1"
        exit 1
esac

cd "`dirname "$0"`"
./1-get-build-url.sh "$2"
./2-1-install.sh "${TAS_URL}" "${BUNDLE_DOWNLOAD}"
./3-run.sh "${TAS_PATH}"
""",
            """
#!/bin/bash
# Installs Everest from the branch to test, CelesteTAS and the mod that is going to be TASed.
# Run from within the Docker image, where Celeste is installed at /home/ubuntu/celeste.

set -xeo pipefail

# download Everest
cd /home/ubuntu
curl --fail -Lo everest.zip "${MAIN_BUILD_URL}"
unzip everest.zip
rm -v everest.zip

# copy Everest files to Celeste install
mv -fv main/* celeste/
rm -rfv main

# install Everest in headless mode
cd celeste
chmod -v u+x MiniInstaller-linux
./MiniInstaller-linux headless

# download TAS files
cd ..
curl --fail -Lo t.zip "${TAS_FILES_URL}"
unzip t.zip
rm -v t.zip

# install CelesteTAS
cd celeste/Mods
curl --fail -Lo CelesteTAS.zip "https://github.com/EverestAPI/CelesteTAS-EverestInterop/releases/download/{{CelesteTAS-Ver}}/CelesteTAS.zip"

# install the mod that is going to be TASed, downloaded as a bundle zip containing the mod zip
# and all of its dependencies (https://maddie480.ovh/celeste/bundle-download?id=${TAS_TO_RUN})
if ! [ "${BUNDLE_DOWNLOAD_URL}" == "" ]; then
    curl --fail -Lo t.zip "${BUNDLE_DOWNLOAD_URL}"
    unzip t.zip
    rm -v t.zip
fi
"""
    };

    public static void main(String[] args) throws Exception {
        if (ZonedDateTime.now().getDayOfWeek() != DayOfWeek.SATURDAY) {
            log.info("This isn't Saturday, skipping");
            return;
        }

        log.debug("Fetching versions...");
        Map<String, String> fields = new HashMap<>();
        fields.put("{{CelesteTAS-SHA}}", getLatestCommitSHA("VampireFlower/CelesteTAS"));
        fields.put("{{StrawberryJamTAS-SHA}}", getLatestCommitSHA("VampireFlower/StrawberryJamTAS"));
        fields.put("{{CelesteTAS-Ver}}", getLatestCelesteTASVersion());
        fields.put("{{StrawberryJamBundle-CRC}}", getStrawberryJamBundleCRC());
        log.debug("Finished: {}", fields);

        final Path stateFile = Paths.get("/backend/tas-check-update-state.ser");

        {
            Map<String, String> previousFields = new HashMap<>();
            try (InputStream is = Files.newInputStream(stateFile);
                 ObjectInputStream ois = new ObjectInputStream(is)) {

                previousFields = (Map<String, String>) ois.readObject();
            } catch (Exception e) {
                log.warn("Error while loading latest state", e);
            }

            log.debug("Previous: {}", previousFields);

            boolean somethingChanged = false;
            for (Map.Entry<String, String> field : new HashMap<>(fields).entrySet()) {
                boolean fieldChanged = !field.getValue().equals(previousFields.get(field.getKey()));
                fields.put("{{compare:" + field.getKey().substring(2),
                        fieldChanged ? "updated :arrow_up:" : "up-to-date :white_check_mark:");

                somethingChanged = somethingChanged || fieldChanged;
            }
            log.debug("New template mapping: {}", fields);

            if (!somethingChanged) {
                log.info("Everything is up-to-date!");
                cleanup();
                return;
            }
        }

        try {
            if (fields.get("{{compare:StrawberryJamBundle-CRC}}").equals("updated :arrow_up:")) {
                // load update checker config from secret constants
                ByteArrayInputStream is = new ByteArrayInputStream(SecretConstants.UPDATE_CHECKER_CONFIG.getBytes(StandardCharsets.UTF_8));
                Map<String, Object> config = YamlUtil.load(is);
                Main.serverConfig = new ServerConfig(config);

                log.debug("Uploading new version of the Strawberry Jam bundle...");
                String filename = "StrawberryJam2021-Bundle-" + fields.get("{{StrawberryJamBundle-CRC}}") + ".zip";
                BananaMirrorUploader.uploadFile(SJ_BUNDLE, "pinned-mods", filename);
            }

            log.debug("Preparing git repository...");
            GitOperator.init();

            log.debug("Applying changes...");
            for (int i = 0; i < FILE_TEMPLATES.length; i++) {
                Path file = FILE_PATHS[i];
                String content = FILE_TEMPLATES[i];

                for (Map.Entry<String, String> field : fields.entrySet()) {
                    content = content.replace(field.getKey(), field.getValue());
                }
                Files.writeString(file, content, StandardCharsets.UTF_8);
            }

            log.debug("Committing changes...");
            GitOperator.commitChanges();

            log.debug("Opening pull request...");
            String prDescription = """
                    - CelesteTAS: {{compare:CelesteTAS-Ver}} @ [{{CelesteTAS-Ver}}](https://github.com/EverestAPI/CelesteTAS-EverestInterop/releases/tag/{{CelesteTAS-Ver}})
                    - Celeste TAS files: {{compare:CelesteTAS-SHA}} @ https://github.com/VampireFlower/CelesteTAS/commit/{{CelesteTAS-SHA}}
                    - Strawberry Jam TAS files: {{compare:StrawberryJamTAS-SHA}} @ https://github.com/VampireFlower/StrawberryJamTAS/commit/{{StrawberryJamTAS-SHA}}
                    - Strawberry Jam bundle: {{compare:StrawberryJamBundle-CRC}} @ [{{StrawberryJamBundle-CRC}}](https://celestemodupdater.0x0a.de/pinned-mods/StrawberryJam2021-Bundle-{{StrawberryJamBundle-CRC}}.zip) (crc32 of the zip)
                    """;
            for (Map.Entry<String, String> field : fields.entrySet()) {
                prDescription = prDescription.replace(field.getKey(), field.getValue());
            }
            openPullRequest("Bump TAS Check dependencies", prDescription);

            fields.remove("{{compare:CelesteTAS-SHA}}");
            fields.remove("{{compare:StrawberryJamTAS-SHA}}");
            fields.remove("{{compare:CelesteTAS-Ver}}");
            fields.remove("{{compare:StrawberryJamBundle-CRC}}");
            try (OutputStream os = Files.newOutputStream(stateFile);
                 ObjectOutputStream oos = new ObjectOutputStream(os)) {

                oos.writeObject(fields);
            }

            log.info("Done!");
        } finally {
            log.debug("Cleaning up temporary files...");
            cleanup();
        }
    }

    /**
     * Opens a pull request to merge maddie480-bot/Everest:dev to EverestAPI/Everest:dev.
     */
    private static void openPullRequest(String title, String description) throws Exception {
        HttpURLConnection connAuth = ConnectionUtils.openConnectionWithTimeout("https://api.github.com/repos/EverestAPI/Everest/pulls");
        connAuth.setRequestProperty("Authorization", "Basic " + SecretConstants.GITHUB_BASIC_AUTH);
        connAuth.setRequestProperty("Content-Type", "application/json");
        connAuth.setRequestMethod("POST");
        connAuth.setDoOutput(true);

        JSONObject req = new JSONObject();
        req.put("title", title);
        req.put("body", description);
        req.put("head", "maddie480-bot:dev");
        req.put("base", "dev");

        try (OutputStream os = connAuth.getOutputStream();
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {

            req.write(bw);
        }

        try (InputStream is = ConnectionUtils.connectionToInputStream(connAuth)) {
            JSONObject resp = new JSONObject(new JSONTokener(is));
            log.debug("Pull request created: {}", resp.getString("url"));
        }
    }

    private static String getLatestCommitSHA(String repo) throws IOException {
        try (InputStream is = authenticatedGitHubRequest("https://api.github.com/repos/" + repo + "/commits")) {
            JSONArray commits = new JSONArray(new JSONTokener(is));
            return commits.getJSONObject(0).getString("sha");
        }
    }

    private static String getLatestCelesteTASVersion() throws IOException {
        try (InputStream is = authenticatedGitHubRequest("https://api.github.com/repos/EverestAPI/CelesteTAS-EverestInterop/releases/latest")) {
            JSONObject release = new JSONObject(new JSONTokener(is));
            return release.getString("tag_name");
        }
    }

    private static String getStrawberryJamBundleCRC() throws IOException {
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/bundle-download?id=StrawberryJam2021");
             OutputStream os = Files.newOutputStream(SJ_BUNDLE)) {

            is.transferTo(os);
        }

        CRC32 crc = new CRC32();

        // to get a stable crc32, we hash the contents of the zip in alphabetic order
        // instead of just hashing the zip itself
        try (ZipFile file = new ZipFile(SJ_BUNDLE.toFile())) {
            List<ZipEntry> sortedEntries = new ArrayList<>();
            file.entries().asIterator().forEachRemaining(sortedEntries::add);
            sortedEntries.sort(Comparator.comparing(ZipEntry::getName));

            for (ZipEntry entry : sortedEntries) {
                byte[] buffer = new byte[4096];
                try (InputStream is = file.getInputStream(entry)) {
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        crc.update(buffer, 0, read);
                    }
                }
            }
        }

        String crcAsString = Long.toHexString(crc.getValue());
        while (crcAsString.length() < 8) crcAsString = "0" + crcAsString;
        return crcAsString;
    }

    private static void cleanup() {
        try {
            OutputStreamLogger.redirectAllOutput(log,
                    new ProcessBuilder("rm", "-rfv", "/tmp/Everest", SJ_BUNDLE.toAbsolutePath().toString())
                            .inheritIO()
                            .start()).waitFor();
        } catch (Exception e) {
            log.warn("Error while running cleanup", e);
        }
    }
}
