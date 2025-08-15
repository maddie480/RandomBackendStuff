package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static ovh.maddie480.randomstuff.backend.celeste.crontabs.EverestVersionLister.authenticatedGitHubRequest;

public class EverestRepositoriesRitualCheck {
    private static final Logger log = LoggerFactory.getLogger(EverestRepositoriesRitualCheck.class);

    /**
     * Checks that https://github.com/EverestAPI/Everest/milestone/1 has been pushed to the future on the last rolling release.
     * This is linked to in the contribution guide, and indicates when the next rolling release will happen.
     */
    public static void checkMilestoneIsInTheFuture() throws IOException {
        JSONObject resp;
        try (InputStream is = authenticatedGitHubRequest("https://api.github.com/repos/EverestAPI/Everest/milestones/1")) {
            resp = new JSONObject(new JSONTokener(is));
        }

        ZonedDateTime due = ZonedDateTime.parse(resp.getString("due_on"));
        if (due.isBefore(ZonedDateTime.now())) {
            throw new IOException("The Next Release milestone is in the past!");
        }
    }

    /**
     * Latest versions of each branch should be pinned on Azure Pipelines, and other versions should not.
     * This ensures that all branches will continue to exist even if development on Everest / Olympus stops.
     */
    public static void checkLatestVersionsArePinned() throws IOException {
        for (String repo : Arrays.asList(
                "https://dev.azure.com/EverestAPI/Everest/_apis/build/builds?definitions=3",
                "https://dev.azure.com/EverestAPI/Olympus/_apis/build/builds"
        )) {
            String name = repo.substring(33, 40);

            JSONObject resp;
            try (InputStream is = ConnectionUtils.openStreamWithTimeout(repo)) {
                resp = new JSONObject(new JSONTokener(is));
            }

            Set<String> encounteredBranches = new HashSet<>();
            for (int i = 0; i < resp.getJSONArray("value").length(); i++) {
                JSONObject build = resp.getJSONArray("value").getJSONObject(i);
                String branch = build.getString("sourceBranch");
                boolean shouldBePinned = branch.startsWith("refs/heads/") && !encounteredBranches.contains(branch);
                if (isPinned(name, build.getInt("id")) != shouldBePinned) {
                    throw new IOException("Build " + build.getInt("id") + " should " + (shouldBePinned ? "" : "not ") + "be pinned!");
                }
                encounteredBranches.add(branch);
            }
        }
    }

    private static boolean isPinned(String repo, int id) throws IOException {
        JSONObject infoDump;
        {
            Document d = ConnectionUtils.jsoupGetWithRetry("https://dev.azure.com/EverestAPI/" + repo + "/_build/results?buildId=" + id + "&view=results");
            String json = d.select("#dataProviders").html();
            infoDump = new JSONObject(json);
        }

        JSONArray leases = infoDump.getJSONObject("data").getJSONObject("ms.vss-build-web.run-details-data-provider").getJSONArray("retentionLeases");
        for (int i = 0; i < leases.length(); i++) {
            if (!leases.getJSONObject(i).getString("leaseType").equals("Build")) {
                log.trace("Pin found for {} build {}: {}", repo, id, leases.getJSONObject(i));
                return true;
            }
        }

        log.trace("No pin found for {} build {}", repo, id);
        return false;
    }
}
