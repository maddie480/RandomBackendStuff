package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;

import static ovh.maddie480.randomstuff.backend.celeste.crontabs.EverestVersionLister.authenticatedGitHubRequest;

/**
 * An hourly crontab that slaps labels on Everest pull requests to make their status clearer,
 * and posts updates about the status of the 5-day last call window as PR comments.
 */
public class EverestPRLabelSlapper {
    private static final Logger log = LoggerFactory.getLogger(EverestPRLabelSlapper.class);

    private static final String LABEL_REVIEW_NEEDED = "review needed";
    private static final String LABEL_CHANGES_REQUESTED = "changes requested";
    private static final String LABEL_LAST_CALL_WINDOW = "last call window";
    private static final String LABEL_READY_TO_MERGE = "ready to merge";
    private static final Set<String> BOT_MANAGED_LABELS = new HashSet<>(Arrays.asList(LABEL_REVIEW_NEEDED, LABEL_CHANGES_REQUESTED, LABEL_LAST_CALL_WINDOW, LABEL_READY_TO_MERGE));

    private static final LocalDate ROLLING_RELEASE_DATE = LocalDate.parse("2025-05-17");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final int APPROVALS_NEEDED = 2;

    private static LocalDate getNextRollingReleaseDate() {
        LocalDate result = ROLLING_RELEASE_DATE.plusDays(0);
        while (result.isBefore(LocalDate.now())) {
            result = result.plusWeeks(2);
        }
        return result;
    }

    private static Map<Integer, ZonedDateTime> endOfLastCallWindowsOld = null;
    private static Map<Integer, ZonedDateTime> endOfLastCallWindowsNew = null;

    public static void main(String[] args) throws IOException {
        log.debug("Next rolling release will happen on {}", getNextRollingReleaseDate());

        Path stateFile = Paths.get("everest_pr_slapper_state.ser");
        try (InputStream is = Files.newInputStream(stateFile);
             ObjectInputStream ois = new ObjectInputStream(is)) {

            endOfLastCallWindowsOld = (Map<Integer, ZonedDateTime>) ois.readObject();
        } catch (ClassNotFoundException | IOException e) {
            log.error("Cannot read old latest review dates, initializing to empty map", e);
            endOfLastCallWindowsOld = new HashMap<>();
        }
        endOfLastCallWindowsNew = new HashMap<>();

        int i = 1;
        while (true) {
            int page = i++;
            log.debug("Fetching PR page {}", page);
            JSONArray list = ConnectionUtils.runWithRetry(() -> {
                try (InputStream is = authenticatedGitHubRequest("https://api.github.com/repos/EverestAPI/Everest/pulls?page=" + page)) {
                    return new JSONArray(new JSONTokener(is));
                }
            });
            if (list.isEmpty()) break;
            for (Object o : list) handlePullRequest((JSONObject) o);
        }
        try (OutputStream os = Files.newOutputStream(stateFile);
             ObjectOutputStream oos = new ObjectOutputStream(os)) {

            oos.writeObject(endOfLastCallWindowsNew);
        }

        endOfLastCallWindowsOld = null;
        endOfLastCallWindowsNew = null;
    }

    private static void handlePullRequest(JSONObject pr) throws IOException {
        int prNumber = pr.getInt("number");
        log.debug("Processsing pull request !{}", prNumber);

        Verdict verdict = computePRState(pr);
        List<String> existingLabels = pr.getJSONArray("labels").toList().stream()
                .map(o -> (Map<String, String>) o)
                .map(label -> label.get("name"))
                .filter(BOT_MANAGED_LABELS::contains)
                .toList();

        log.trace("Existing labels on PR: {}", existingLabels);

        String comment = null;
        if (verdict.label.equals(LABEL_LAST_CALL_WINDOW)) {
            if (!verdict.endOfLastCallWindow.equals(endOfLastCallWindowsOld.get(prNumber))) {
                comment = """
                        The pull request was approved and entered the 5-day last-call window.
                        If no further reviews happen, it will end on **"""
                        + verdict.endOfLastCallWindow
                        .format(DateTimeFormatter
                                .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                                .withLocale(Locale.ENGLISH)) + """
                         UTC**, after which the pull request will be able to be merged.
                        """;
            }
            endOfLastCallWindowsNew.put(prNumber, verdict.endOfLastCallWindow);
        }
        if (verdict.label.equals(LABEL_READY_TO_MERGE) && !existingLabels.contains(verdict.label)) {
            comment = "The last-call window for this pull request ended. It can now be merged if no blockers were brought up.";
        }

        if (comment != null) {
            log.trace("Posting comment: \"{}\"", comment);
            postComment(prNumber, comment);
        }

        if (!existingLabels.contains(verdict.label)) {
            log.trace("Adding label: {}", verdict.label);
            addLabel(prNumber, verdict.label);
        }

        List<String> labelsToRemove = new ArrayList<>(existingLabels);
        labelsToRemove.remove(verdict.label);
        if (!labelsToRemove.isEmpty()) {
            log.trace("Removing labels: {}", labelsToRemove);
            for (String label : labelsToRemove) removeLabel(prNumber, label);
        }
    }

    private static void addLabel(int prNumber, String label) throws IOException {
        JSONObject body = new JSONObject();
        JSONArray labels = new JSONArray();
        labels.put(label);
        body.put("labels", labels);

        authenticatedGitHubPost("https://api.github.com/repos/EverestAPI/Everest/issues/" + prNumber + "/labels", body, 200);
    }

    private static void removeLabel(int prNumber, String label) throws IOException {
        ConnectionUtils.runWithRetry(() -> {
            HttpURLConnection con = ConnectionUtils.openConnectionWithTimeout("https://api.github.com/repos/EverestAPI/Everest/issues/" + prNumber + "/labels/" + label.replace(" ", "%20"));
            con.setRequestMethod("DELETE");
            con.setRequestProperty("Authorization", "Basic " + SecretConstants.GITHUB_BASIC_AUTH);
            if (con.getResponseCode() != 200) {
                throw new IOException("Remove label API responded with code " + con.getResponseCode());
            }
            return null; // method signature is stoopid
        });
    }

    private static void postComment(int prNumber, String text) throws IOException {
        JSONObject body = new JSONObject();
        body.put("body", text);

        authenticatedGitHubPost("https://api.github.com/repos/EverestAPI/Everest/issues/" + prNumber + "/comments", body, 201);
    }

    private static void authenticatedGitHubPost(String url, JSONObject body, int expectedResponseCode) throws IOException {
        ConnectionUtils.runWithRetry(() -> {
            HttpURLConnection con = ConnectionUtils.openConnectionWithTimeout(url);
            con.setRequestMethod("POST");
            con.setRequestProperty("Authorization", "Basic " + SecretConstants.GITHUB_BASIC_AUTH);
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);

            try (OutputStream os = con.getOutputStream();
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {

                body.write(writer);
            }
            if (con.getResponseCode() != expectedResponseCode) {
                throw new IOException("GitHub API responded with code " + con.getResponseCode());
            }
            return null; // method signature is stoopid
        });
    }

    private record Verdict(String label, ZonedDateTime endOfLastCallWindow) {
    }

    private static Verdict computePRState(JSONObject pr) throws IOException {
        JSONArray reviews = ConnectionUtils.runWithRetry(() -> {
            try (InputStream is = authenticatedGitHubRequest("https://api.github.com/repos/EverestAPI/Everest/pulls/" + pr.getInt("number") + "/reviews")) {
                return new JSONArray(new JSONTokener(is));
            }
        });

        Map<String, Verdict> userVerdicts = new HashMap<>();
        for (Object o : reviews) {
            JSONObject review = (JSONObject) o;

            String authorName = review.getJSONObject("user").getString("login");
            String authorRole = review.getString("author_association");
            String reviewResult = review.getString("state");
            ZonedDateTime reviewedAt = ZonedDateTime.parse(review.getString("submitted_at"));

            if (reviewResult.equals("COMMENTED")) {
                log.debug("Review by {} was ignored because the result is {} @ {}", authorName, reviewResult, reviewedAt);
                continue;
            }
            if (!Arrays.asList("COLLABORATOR", "MEMBER", "OWNER").contains(authorRole)) {
                log.trace("Review by {} was ignored because their role is {} @ {}", authorName, authorRole, reviewedAt);
                continue;
            }

            log.trace("Review by {} was updated to {} @ {}", authorName, reviewResult, reviewedAt);
            userVerdicts.put(authorName, new Verdict(reviewResult, reviewedAt));

        }

        List<ZonedDateTime> approvalDates = userVerdicts.values().stream()
                .filter(verdict -> verdict.label.equals("APPROVED"))
                .map(verdict -> verdict.endOfLastCallWindow)
                .toList();

        int approvalCount = approvalDates.size();
        ZonedDateTime lastApproval = approvalDates.stream().max(ZonedDateTime::compareTo).orElse(null);
        boolean changesRequested = userVerdicts.values().stream().anyMatch(verdict -> verdict.label.equals("CHANGES_REQUESTED"));
        log.debug("Changes requested = {} / Approvals happened at {} => count is {}, last approval happened at {}",
                changesRequested, approvalDates, approvalCount, lastApproval);

        if (approvalCount >= APPROVALS_NEEDED && !changesRequested) {
            log.trace("Verdict: PR is approved");

            ZonedDateTime endOfLastCallWindow = lastApproval.withZoneSameInstant(ZoneId.of("UTC")).plusDays(5);
            log.trace("Last call window ends at {}", endOfLastCallWindow);

            if (endOfLastCallWindow.plusDays(5).isAfter(getNextRollingReleaseDate().atStartOfDay(UTC))) {
                endOfLastCallWindow = getNextRollingReleaseDate().atStartOfDay(UTC).plusDays(1);
                log.trace("Last call window pushed forward at {} because of rolling release", endOfLastCallWindow);
            }

            String verdict = endOfLastCallWindow.isBefore(ZonedDateTime.now()) ? LABEL_READY_TO_MERGE : LABEL_LAST_CALL_WINDOW;
            log.trace("The PR state is \"{}\"", verdict);
            return new Verdict(verdict, endOfLastCallWindow);
        } else {
            String verdict = changesRequested ? LABEL_CHANGES_REQUESTED : LABEL_REVIEW_NEEDED;
            log.trace("Verdict: PR is NOT approved, PR state is \"{}\"", verdict);
            return new Verdict(verdict, null);
        }
    }
}
