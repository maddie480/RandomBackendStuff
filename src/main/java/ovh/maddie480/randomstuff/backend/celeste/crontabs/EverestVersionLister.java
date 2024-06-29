package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
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
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * This class lists all Everest versions, making calls to GitHub and Azure APIs as required.
 * It allows centralizing the logic for version listing, and making as many GitHub API calls as we want without
 * worrying about rate limits.
 */
public class EverestVersionLister {
    private static final Logger log = LoggerFactory.getLogger(EverestVersionLister.class);

    private static final Pattern PULL_REQUEST_MERGE = Pattern.compile("^Merge pull request #([0-9]+) from .*$");
    private static final Pattern VERSION_NUMBER_IN_RELEASE_NAME = Pattern.compile("^[^0-9]*([0-9]+)$");
    private static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern LINK_HEADER_NEXT_PAGE = Pattern.compile("^ ?rel=\"next\", <(.*)>$");

    private static List<Integer> latestAzureBuilds = new ArrayList<>();
    private static List<String> latestGitHubReleases = new ArrayList<>();

    /**
     * Checks if versions changed, and regenerates the versions list if necessary.
     * Run every 15 minutes.
     */
    public static void checkEverestVersions() throws IOException {
        Path versionListStateFile = Paths.get("everest_version_list_state.ser");

        // load state
        if (Files.exists(versionListStateFile)) {
            try (ObjectInputStream is = new ObjectInputStream(Files.newInputStream(versionListStateFile))) {
                latestAzureBuilds = (List<Integer>) is.readObject();
                latestGitHubReleases = (List<String>) is.readObject();
            } catch (ClassNotFoundException e) {
                throw new IOException(e);
            }
        }

        // get the latest Azure builds for dev and beta branches
        List<Integer> currentAzureBuilds = new ArrayList<>();
        for (String branch : Arrays.asList("dev", "beta")) {
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://dev.azure.com/EverestAPI/Everest/_apis/build/builds?definitions=3&branchName=refs/heads/" + branch + "&statusFilter=completed&resultFilter=succeeded&api-version=5.0")) {
                currentAzureBuilds.addAll(new JSONObject(new JSONTokener(is)).getJSONArray("value")
                        .toList().stream()
                        .map(version -> (int) ((Map<String, Object>) version).get("id"))
                        .collect(Collectors.toList()));
            }
        }

        // get the latest GitHub release names
        List<String> currentGitHubReleases = getAllGitHubReleases()
                .toList().stream()
                .map(version -> (String) ((Map<String, Object>) version).get("name"))
                .collect(Collectors.toList());

        if (!currentGitHubReleases.equals(latestGitHubReleases) || !currentAzureBuilds.equals(latestAzureBuilds)) {
            // one of them changed => trigger an update
            log.info("Updating Everest versions as the latest versions changed: Azure {} -> {}, GitHub {} -> {}",
                    latestAzureBuilds, currentAzureBuilds, latestGitHubReleases, currentGitHubReleases);

            updateEverestVersions();

            latestAzureBuilds = currentAzureBuilds;
            latestGitHubReleases = currentGitHubReleases;
        }

        // save state
        try (ObjectOutputStream os = new ObjectOutputStream(Files.newOutputStream(versionListStateFile))) {
            os.writeObject(latestAzureBuilds);
            os.writeObject(latestGitHubReleases);
        }
    }

    private static void updateEverestVersions() throws IOException {
        List<Map<String, Object>> infoNoNative = new ArrayList<>();
        List<Map<String, Object>> infoWithNative = new ArrayList<>();

        // === GitHub Releases: for stable builds

        {
            JSONArray gitHubReleases = getAllGitHubReleases();

            for (Object b : gitHubReleases) {
                Map<String, Object> entry = new HashMap<>();
                JSONObject build = (JSONObject) b;

                entry.put("date", build.getString("published_at"));
                entry.put("commit", getGitHubReleaseCommit(build));

                // "beta" and "stable" is determined by the "prerelease" flag on the release.
                entry.put("branch", build.getBoolean("prerelease") ? "beta" : "stable");

                // the version number is extracted from the release name (which is auto-generated anyway).
                Matcher numberMatcher = VERSION_NUMBER_IN_RELEASE_NAME.matcher(build.getString("name"));
                if (!numberMatcher.matches()) {
                    continue;
                }

                entry.put("version", Integer.parseInt(numberMatcher.group(1)));

                // then, find the downloads among the release assets.
                for (Object a : build.getJSONArray("assets")) {
                    JSONObject artifact = (JSONObject) a;

                    switch (artifact.getString("name")) {
                        case "main.zip" -> entry.put("mainDownload", artifact.getString("browser_download_url"));
                        case "olympus-meta.zip" ->
                                entry.put("olympusMetaDownload", artifact.getString("browser_download_url"));
                        case "olympus-build.zip" ->
                                entry.put("olympusBuildDownload", artifact.getString("browser_download_url"));
                    }
                }

                entry.put("mainFileSize", getFileSize((String) entry.get("mainDownload"), "main"));
                entry.put("olympusBuildFileSize", getFileSize((String) entry.get("olympusBuildDownload"), "olympusBuild"));
                entry.put("olympusMetaFileSize", getFileSize((String) entry.get("olympusMetaDownload"), "olympusMeta"));

                boolean isNative = isNative((String) entry.get("olympusBuildDownload"));
                entry.put("isNative", isNative);

                if (!isNative) infoNoNative.add(entry);
                infoWithNative.add(entry);
            }
        }

        // === Azure: for dev and beta builds

        for (String branch : Arrays.asList("dev", "beta")) {
            JSONObject azureBuilds;
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://dev.azure.com/EverestAPI/Everest/_apis/build/builds?definitions=3&branchName=refs/heads/" + branch + "&statusFilter=completed&resultFilter=succeeded&api-version=5.0")) {
                azureBuilds = new JSONObject(new JSONTokener(is));
            }

            for (Object b : azureBuilds.getJSONArray("value")) {
                Map<String, Object> entry = new HashMap<>();
                JSONObject build = (JSONObject) b;

                // most of the fields can be determined straight from the build number
                entry.put("branch", branch);
                entry.put("date", build.getString("finishTime"));
                entry.put("commit", build.getString("sourceVersion"));
                entry.put("version", build.getInt("id") + 700);
                entry.put("mainDownload", "https://dev.azure.com/EverestAPI/Everest/_apis/build/builds/" + build.getInt("id") + "/artifacts?artifactName=main&api-version=5.0&%24format=zip");
                entry.put("olympusMetaDownload", "https://dev.azure.com/EverestAPI/Everest/_apis/build/builds/" + build.getInt("id") + "/artifacts?artifactName=olympus-meta&api-version=5.0&%24format=zip");
                entry.put("olympusBuildDownload", "https://dev.azure.com/EverestAPI/Everest/_apis/build/builds/" + build.getInt("id") + "/artifacts?artifactName=olympus-build&api-version=5.0&%24format=zip");

                // only look for an author and description for commit-related builds, not manual ones
                if (!"beta".equals(branch) && build.getString("reason").equals("individualCI")) {
                    Matcher pullRequestMatcher = PULL_REQUEST_MERGE.matcher(build.getJSONObject("triggerInfo").getString("ci.message"));
                    if (pullRequestMatcher.matches()) {
                        // take the author and name from the pull request
                        String prNumber = pullRequestMatcher.group(1);

                        JSONObject prInfo;
                        try (InputStream is = authenticatedGitHubRequest("https://api.github.com/repos/EverestAPI/Everest/pulls/" + prNumber)) {
                            prInfo = new JSONObject(new JSONTokener(is));
                        }

                        entry.put("author", prInfo.getJSONObject("user").getString("login"));
                        entry.put("description", prInfo.getString("title"));
                    } else {
                        // take the author and name from the commit
                        String commitSha = build.getString("sourceVersion");

                        JSONObject commitInfo;
                        try (InputStream is = authenticatedGitHubRequest("https://api.github.com/repos/EverestAPI/Everest/commits/" + commitSha)) {
                            commitInfo = new JSONObject(new JSONTokener(is));
                        }

                        String commitMessage = commitInfo.getJSONObject("commit").getString("message");
                        if (commitMessage.contains("\n")) {
                            commitMessage = commitMessage.substring(0, commitMessage.indexOf("\n"));
                        }

                        entry.put("author", commitInfo.getJSONObject("author").getString("login"));
                        entry.put("description", commitMessage);
                    }
                }

                entry.put("mainFileSize", getFileSize((String) entry.get("mainDownload"), "main"));
                entry.put("olympusBuildFileSize", getFileSize((String) entry.get("olympusBuildDownload"), "olympusBuild"));
                entry.put("olympusMetaFileSize", getFileSize((String) entry.get("olympusMetaDownload"), "olympusMeta"));

                boolean isNative = isNative((String) entry.get("olympusBuildDownload"));
                entry.put("isNative", isNative);

                if (!isNative) infoNoNative.add(entry);
                infoWithNative.add(entry);
            }
        }

        // sort the versions by descending number
        infoNoNative.sort(Comparator.comparingInt(build -> -((int) build.get("version"))));
        infoWithNative.sort(Comparator.comparingInt(build -> -((int) build.get("version"))));

        // push to Cloud Storage
        Files.writeString(Paths.get("/shared/celeste/everest-versions.json"), new JSONArray(infoNoNative).toString(), StandardCharsets.UTF_8);
        Files.writeString(Paths.get("/shared/celeste/everest-versions-with-native.json"), new JSONArray(infoWithNative).toString(), StandardCharsets.UTF_8);

        // update the frontend cache
        HttpURLConnection conn = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/everest-versions-reload?key="
                + SecretConstants.RELOAD_SHARED_SECRET);
        if (conn.getResponseCode() != 200) {
            throw new IOException("Everest Versions Reload API sent non 200 code: " + conn.getResponseCode());
        }

        // notify on Discord
        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            WebhookExecutor.executeWebhook(webhook,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/update-checker.png",
                    "Everest Update Checker",
                    ":sparkles: Everest versions were updated. There are now **" + infoWithNative.size() + "** versions on record.\n" +
                            "Latest Everest version is **" + infoWithNative.get(0).get("version") + "** (" + infoWithNative.get(0).get("branch") + ")"
                            + (infoWithNative.get(0).containsKey("description") ? ": `" + infoWithNative.get(0).get("description") + "` by " + infoWithNative.get(0).get("author") + "." : "."),
                    ImmutableMap.of("X-Everest-Log", "true"));
        }

        UpdateOutgoingWebhooks.changesHappened();
    }

    private static String getGitHubReleaseCommit(JSONObject release) throws IOException {
        String commitish = release.getString("target_commitish");
        if (COMMIT_SHA.matcher(commitish).matches()) {
            return commitish;
        }

        // commitishes are not always commit hashes, it depends on what the release was based on,
        // so try using the tag instead
        JSONObject tagInfo;
        try (InputStream is = authenticatedGitHubRequest("https://api.github.com/repos/EverestAPI/Everest/git/refs/tags/" + release.getString("tag_name"))) {
            tagInfo = new JSONObject(new JSONTokener(is));
        }

        return tagInfo.getJSONObject("object").getString("sha");
    }

    private static InputStream authenticatedGitHubRequest(String url) throws IOException {
        HttpURLConnection connAuth = ConnectionUtils.openConnectionWithTimeout(url);
        connAuth.setRequestProperty("Authorization", "Basic " + SecretConstants.GITHUB_BASIC_AUTH);
        return ConnectionUtils.connectionToInputStream(connAuth);
    }

    private static JSONArray getAllGitHubReleases() throws IOException {
        String link = "https://api.github.com/repos/EverestAPI/Everest/releases";
        JSONArray allReleases = new JSONArray();

        while (true) {
            HttpURLConnection connAuth = ConnectionUtils.openConnectionWithTimeout(link);
            connAuth.setRequestProperty("Authorization", "Basic " + SecretConstants.GITHUB_BASIC_AUTH);

            try (InputStream is = ConnectionUtils.connectionToInputStream(connAuth)) {
                JSONArray page = new JSONArray(new JSONTokener(is));
                allReleases.putAll(page);
            }

            String linkHeader = connAuth.getHeaderField("link");
            if (linkHeader != null) {
                String nextPage = Arrays.stream(linkHeader.split(";"))
                        .map(field -> {
                            Matcher matcher = LINK_HEADER_NEXT_PAGE.matcher(field);
                            return matcher.matches() ? matcher.group(1) : null;
                        })
                        .filter(Objects::nonNull)
                        .findFirst().orElse(null);

                if (nextPage == null) {
                    return allReleases;
                } else {
                    link = nextPage;
                }
            }
        }
    }

    /**
     * Gets a file size by downloading it twice.
     * This is useful for Azure, since it does not tell file sizes through response headers.
     */
    private static long getFileSize(String url, String name) throws IOException {
        Optional<Long> calculated = getPreviouslyCalculatedValue(name + "Download", url, v -> v.getLong(name + "FileSize"));
        if (calculated.isPresent()) return calculated.get();

        long[] size = new long[2];

        for (int i = 0; i < 2; i++) {
            byte[] buffer = new byte[4096];
            try (InputStream is = ConnectionUtils.openStreamWithTimeout(url)) {
                while (true) {
                    int read = is.read(buffer);
                    if (read == -1) break;
                    size[i] += read;
                }
            }
        }

        if (size[0] == size[1]) {
            log.debug("Size of file {} is {} bytes", url, size[0]);
            return size[0];
        }

        throw new IOException("Got different sizes for " + url + ": " + size[0] + " and " + size[1] + "!");
    }

    /**
     * Checks whether the Olympus build at the given URL is a native build,
     * by checking if it contains MiniInstaller.exe.
     */
    private static boolean isNative(String url) throws IOException {
        Optional<Boolean> calculated = getPreviouslyCalculatedValue("olympusBuildDownload", url, v -> v.getBoolean("isNative"));
        if (calculated.isPresent()) return calculated.get();

        try (InputStream is = ConnectionUtils.openStreamWithTimeout(url);
             ZipInputStream outerZip = new ZipInputStream(is)) {

            // seek to the inner zip, which is the only flat file there is in the outer zip
            while (outerZip.getNextEntry().isDirectory()) ;

            try (ZipInputStream innerZip = new ZipInputStream(outerZip)) {
                // check if there is MiniInstaller.exe in there. If there is, the build is not native.
                ZipEntry entry;
                while ((entry = innerZip.getNextEntry()) != null) {
                    if (entry.getName().equals("MiniInstaller.exe")) {
                        return false;
                    }
                }

                return true;
            }
        }
    }

    /**
     * Retrieves a previously calculated value from the existing Everest versions list file,
     * since this is way faster than redownloading the Everest version to check its size or contents.
     */
    private static <T> Optional<T> getPreviouslyCalculatedValue(String fieldToMatch, String valueToMatch, Function<JSONObject, T> getter) throws IOException {
        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/everest-versions-with-native.json"))) {
            JSONArray versions = new JSONArray(new JSONTokener(is));

            for (int i = 0; i < versions.length(); i++) {
                JSONObject version = versions.getJSONObject(i);

                if (valueToMatch.equals(version.getString(fieldToMatch))) {
                    return Optional.of(getter.apply(version));
                }
            }

            return Optional.empty();
        }
    }
}
