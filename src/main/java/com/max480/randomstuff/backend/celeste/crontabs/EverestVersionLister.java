package com.max480.randomstuff.backend.celeste.crontabs;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.CloudStorageUtils;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import com.max480.randomstuff.backend.utils.WebhookExecutor;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This class lists all Everest versions, making calls to GitHub and Azure APIs as required.
 * It allows centralizing the logic for version listing, and making as many GitHub API calls as we want without
 * worrying about rate limits.
 */
public class EverestVersionLister {
    private static final Logger log = LoggerFactory.getLogger(EverestVersionLister.class);

    private static final Pattern PULL_REQUEST_MERGE = Pattern.compile("^Merge pull request #([0-9]+) from .*$");
    private static final Pattern VERSION_NUMBER_IN_RELEASE_NAME = Pattern.compile("^[^0-9]*([0-9]+)$");

    private static List<Integer> latestAzureBuilds = new ArrayList<>();
    private static List<String> latestGitHubReleases = new ArrayList<>();

    /**
     * Checks if versions changed, and regenerates the versions list if necessary.
     * Run every 15 minutes.
     */
    public static void checkEverestVersions() throws IOException {
        // get the latest Azure builds
        List<Integer> currentAzureBuilds;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://dev.azure.com/EverestAPI/Everest/_apis/build/builds?definitions=3&branchName=refs/heads/dev&statusFilter=completed&resultsFilter=succeeded&api-version=5.0")) {
            currentAzureBuilds = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8)).getJSONArray("value")
                    .toList().stream()
                    .map(version -> (int) ((Map<String, Object>) version).get("id"))
                    .collect(Collectors.toList());
        }

        // temporarily add Azure beta builds
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://dev.azure.com/EverestAPI/Everest/_apis/build/builds?definitions=3&branchName=refs/heads/beta&statusFilter=completed&resultsFilter=succeeded&api-version=5.0")) {
            currentAzureBuilds.addAll(new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8)).getJSONArray("value")
                    .toList().stream()
                    .map(version -> (int) ((Map<String, Object>) version).get("id"))
                    .collect(Collectors.toList()));
        }

        // get the latest GitHub release names
        List<String> currentGitHubReleases;
        try (InputStream is = authenticatedGitHubRequest("https://api.github.com/repos/EverestAPI/Everest/releases")) {
            currentGitHubReleases = new JSONArray(IOUtils.toString(is, StandardCharsets.UTF_8))
                    .toList().stream()
                    .map(version -> (String) ((Map<String, Object>) version).get("name"))
                    .collect(Collectors.toList());
        }

        if (!currentGitHubReleases.equals(latestGitHubReleases) || !currentAzureBuilds.equals(latestAzureBuilds)) {
            // one of them changed => trigger an update
            log.info("Updating Everest versions as the latest versions changed: Azure {} -> {}, GitHub {} -> {}",
                    latestAzureBuilds, currentAzureBuilds, latestGitHubReleases, currentGitHubReleases);

            updateEverestVersions();

            latestAzureBuilds = currentAzureBuilds;
            latestGitHubReleases = currentGitHubReleases;
        }
    }

    private static void updateEverestVersions() throws IOException {
        List<Map<String, Object>> info = new ArrayList<>();

        // === GitHub Releases: for Beta and Stable

        {
            JSONArray gitHubReleases;
            try (InputStream is = authenticatedGitHubRequest("https://api.github.com/repos/EverestAPI/Everest/releases")) {
                gitHubReleases = new JSONArray(IOUtils.toString(is, StandardCharsets.UTF_8));
            }

            for (Object b : gitHubReleases) {
                Map<String, Object> entry = new HashMap<>();
                JSONObject build = (JSONObject) b;

                entry.put("date", build.getString("published_at"));

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
                        case "main.zip":
                            entry.put("mainDownload", artifact.getString("browser_download_url"));
                            break;
                        case "olympus-meta.zip":
                            entry.put("olympusMetaDownload", artifact.getString("browser_download_url"));
                            break;
                        case "olympus-build.zip":
                            entry.put("olympusBuildDownload", artifact.getString("browser_download_url"));
                            break;
                    }
                }

                info.add(entry);
            }
        }

        // === Azure: for dev builds

        {
            JSONObject azureBuilds;
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://dev.azure.com/EverestAPI/Everest/_apis/build/builds?definitions=3&branchName=refs/heads/dev&statusFilter=completed&resultsFilter=succeeded&api-version=5.0")) {
                azureBuilds = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
            }

            for (Object b : azureBuilds.getJSONArray("value")) {
                Map<String, Object> entry = new HashMap<>();
                JSONObject build = (JSONObject) b;

                // most of the fields can be determined straight from the build number
                entry.put("branch", "dev");
                entry.put("date", build.getString("finishTime"));
                entry.put("version", build.getInt("id") + 700);
                entry.put("mainDownload", "https://dev.azure.com/EverestAPI/Everest/_apis/build/builds/" + build.getInt("id") + "/artifacts?artifactName=main&api-version=5.0&%24format=zip");
                entry.put("olympusMetaDownload", "https://dev.azure.com/EverestAPI/Everest/_apis/build/builds/" + build.getInt("id") + "/artifacts?artifactName=olympus-meta&api-version=5.0&%24format=zip");
                entry.put("olympusBuildDownload", "https://dev.azure.com/EverestAPI/Everest/_apis/build/builds/" + build.getInt("id") + "/artifacts?artifactName=olympus-build&api-version=5.0&%24format=zip");

                // only look for an author and description for commit-related builds, not manual ones
                if (build.getString("reason").equals("individualCI")) {
                    Matcher pullRequestMatcher = PULL_REQUEST_MERGE.matcher(build.getJSONObject("triggerInfo").getString("ci.message"));
                    if (pullRequestMatcher.matches()) {
                        // take the author and name from the pull request
                        String prNumber = pullRequestMatcher.group(1);

                        JSONObject prInfo;
                        try (InputStream is = authenticatedGitHubRequest("https://api.github.com/repos/EverestAPI/Everest/pulls/" + prNumber)) {
                            prInfo = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                        }

                        entry.put("author", prInfo.getJSONObject("user").getString("login"));
                        entry.put("description", prInfo.getString("title"));
                    } else {
                        // take the author and name from the commit
                        String commitSha = build.getString("sourceVersion");

                        JSONObject commitInfo;
                        try (InputStream is = authenticatedGitHubRequest("https://api.github.com/repos/EverestAPI/Everest/commits/" + commitSha)) {
                            commitInfo = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                        }

                        String commitMessage = commitInfo.getJSONObject("commit").getString("message");
                        if (commitMessage.contains("\n")) {
                            commitMessage = commitMessage.substring(0, commitMessage.indexOf("\n"));
                        }

                        entry.put("author", commitInfo.getJSONObject("author").getString("login"));
                        entry.put("description", commitMessage);
                    }
                }

                info.add(entry);
            }
        }


        // === Azure: for beta builds (TEMPORARY, without commit descriptions)

        {
            JSONObject azureBuilds;
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://dev.azure.com/EverestAPI/Everest/_apis/build/builds?definitions=3&branchName=refs/heads/beta&statusFilter=completed&resultsFilter=succeeded&api-version=5.0")) {
                azureBuilds = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
            }

            for (Object b : azureBuilds.getJSONArray("value")) {
                Map<String, Object> entry = new HashMap<>();
                JSONObject build = (JSONObject) b;

                entry.put("branch", "beta");
                entry.put("date", build.getString("finishTime"));
                entry.put("version", build.getInt("id") + 700);
                entry.put("mainDownload", "https://dev.azure.com/EverestAPI/Everest/_apis/build/builds/" + build.getInt("id") + "/artifacts?artifactName=main&api-version=5.0&%24format=zip");
                entry.put("olympusMetaDownload", "https://dev.azure.com/EverestAPI/Everest/_apis/build/builds/" + build.getInt("id") + "/artifacts?artifactName=olympus-meta&api-version=5.0&%24format=zip");
                entry.put("olympusBuildDownload", "https://dev.azure.com/EverestAPI/Everest/_apis/build/builds/" + build.getInt("id") + "/artifacts?artifactName=olympus-build&api-version=5.0&%24format=zip");

                info.add(entry);
            }
        }

        // sort the versions by descending number
        info.sort(Comparator.comparingInt(build -> -((int) build.get("version"))));

        // push to Cloud Storage
        CloudStorageUtils.sendStringToCloudStorage(new JSONArray(info).toString(), "everest_version_list.json", "application/json");

        // update the frontend cache
        HttpURLConnection conn = ConnectionUtils.openConnectionWithTimeout("https://max480-random-stuff.appspot.com/celeste/everest-versions-reload?key="
                + SecretConstants.RELOAD_SHARED_SECRET);
        if (conn.getResponseCode() != 200) {
            throw new IOException("Everest Versions Reload API sent non 200 code: " + conn.getResponseCode());
        }

        // notify on Discord
        WebhookExecutor.executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK,
                "https://cdn.discordapp.com/attachments/445236692136230943/878508600509726730/unknown.png",
                "Everest Update Checker",
                ":sparkles: Everest versions were updated.");

        UpdateOutgoingWebhooks.changesHappened();
    }

    private static InputStream authenticatedGitHubRequest(String url) throws IOException {
        HttpURLConnection connAuth = ConnectionUtils.openConnectionWithTimeout(url);
        connAuth.setRequestProperty("Authorization", "Basic " + SecretConstants.GITHUB_BASIC_AUTH);
        return connAuth.getInputStream();
    }
}
