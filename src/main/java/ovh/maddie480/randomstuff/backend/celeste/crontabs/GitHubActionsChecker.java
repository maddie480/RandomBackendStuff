package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * Checks that scheduled GitHub actions have run in select repositories.
 * This check is in place because scheduled actions get disabled after 2 months of inactivity
 * on the corresponding repository...
 */
public class GitHubActionsChecker {
    private static final Logger log = LoggerFactory.getLogger(GitHubActionsChecker.class);

    public static void main(String[] args) throws IOException {
        if (!Arrays.asList(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY).contains(ZonedDateTime.now().getDayOfWeek())) {
            log.debug("Today is not a Wiki Quality Check day, skipping");
            return;
        }

        OffsetDateTime twoDaysAgo = OffsetDateTime.now().minusDays(2);
        for (String repo : Arrays.asList(
                // all repositories with Actions and Dependabot updates
                "maddie480/BazarLNJ",
                "maddie480/CelestePico8MapEditor",
                "maddie480/DashCountMod",
                "maddie480/EverestInDocker",
                "maddie480/EverestUpdateCheckerServer",
                "maddie480/ExtendedVariantMode",
                "maddie480/JungleHelper",
                "maddie480/MaddieHelpingHand",
                "maddie480/MergeRequestReportGenerator",
                "maddie480/RandomBackendStuff",
                "maddie480/RandomStuffWebsite",
                "maddie480/SaveFilePortraits",
                "maddie480/SmallCelesteModCollection",
                "maddie480/SpamKick",
                // the Wiki Quality Checks scheduled actions
                "EverestAPI/Resources",
                "EverestAPI/ModResources")) {

            OffsetDateTime lastRun = ConnectionUtils.runWithRetry(() -> {
                JSONObject response;
                try (InputStream is = EverestVersionLister.authenticatedGitHubRequest("https://api.github.com/repos/" + repo + "/actions/runs")) {
                    response = new JSONObject(new JSONTokener(is));
                }
                if (response.getJSONArray("workflow_runs").isEmpty()) return null;
                return OffsetDateTime.parse(
                        response.getJSONArray("workflow_runs").getJSONObject(0).getString("created_at"),
                        DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            });

            log.debug("Latest run of GitHub Actions on {} happened at {}", repo, lastRun);

            if (lastRun == null || lastRun.isBefore(twoDaysAgo)) {
                throw new IOException("GitHub Actions did not run on " + repo + " for at least 1 day!");
            }
        }
    }
}
