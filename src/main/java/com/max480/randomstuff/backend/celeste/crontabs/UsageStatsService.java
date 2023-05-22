package com.max480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import com.max480.everest.updatechecker.YamlUtil;
import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A service that can output stats on HTTP requests, Discord bots usages, and Maddie's GitHub activity.
 * This is called once a day and the result is posted as YAML in a private channel on Discord to monitor server activity,
 * and called once an hour to produce a file that can then be used for display on the frontend.
 */
public class UsageStatsService {
    private static final Logger log = LoggerFactory.getLogger(UsageStatsService.class);

    private static final Pattern frontendLogPattern = Pattern.compile(".*\\[(.* \\+0000)].*");
    private static final DateTimeFormatter frontendDateFormat = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss ZZZ", Locale.ENGLISH);

    private static final Pattern frontendLogPatternStatusCode = Pattern.compile(".* ([0-9]{3}) [0-9].*");

    private static final DateTimeFormatter backendDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * This method is invoked hourly and dumps some weekly statistics that can then be displayed on the website.
     */
    public static void writeWeeklyStatisticsToFile() throws IOException {
        Map<String, Object> stats = UsageStatsService.getStatistics(7);
        try (OutputStream os = Files.newOutputStream(Paths.get("/shared/weekly-stats.yaml"))) {
            YamlUtil.dump(stats, os);
        }
    }

    public static Map<String, Object> getStatistics(int days) throws IOException {
        return ImmutableMap.of(
                "responseCountPerCode", getResponseCountByStatus(days),
                "githubActionsPerRepository", countGitHubActionsPerRepository(days),
                "gitlabActionsCount", countGitLabActions(days),
                "customSlashCommandsUsage", countFrontendLogEntries("/discord/custom-slash-commands", days),
                "gamesBotUsage", countFrontendLogEntries("/discord/games-bot", days),
                "timezoneBotLiteUsage", countFrontendLogEntries("/discord/timezone-bot", days),
                "timezoneBotFullUsage", countBackendLogEntries(l -> l.contains(".BotEventListener") && l.contains("New command: "), days),
                "modStructureVerifierUsage", countBackendLogEntries(l -> l.contains(".ModStructureVerifier") && l.contains("Collab assets folder = "), days),
                "bananaBotUsage", countFrontendLogEntries("/discord/bananabot", days)
        );
    }

    private static int countFrontendLogEntries(String path, int days) {
        try (Stream<Path> frontendLogs = Files.list(Paths.get("/logs"))) {
            return frontendLogs
                    .filter(p -> p.getFileName().toString().endsWith(".request.log"))
                    .mapToInt(p -> {
                        try (Stream<String> lines = Files.lines(p)) {
                            return (int) lines
                                    .filter(l -> l.contains("POST " + path))
                                    .filter(l -> frontendLogIsRecentEnough(l, days))
                                    .count();
                        } catch (IOException e) {
                            log.warn("Could not check frontend log entries!", e);
                            return 0;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            log.warn("Could not check frontend log entries!", e);
            return 0;
        }
    }

    private static int countBackendLogEntries(Predicate<String> filter, int days) {
        try (Stream<Path> backendLogs = Files.list(Paths.get("/logs"))) {
            return backendLogs
                    .filter(p -> p.getFileName().toString().endsWith(".backend.log"))
                    .mapToInt(p -> {
                        try (Stream<String> lines = Files.lines(p)) {
                            return (int) lines
                                    .filter(filter)
                                    .filter(l -> ZonedDateTime.now().minusDays(days).isBefore(
                                            LocalDateTime.parse(l.substring(0, 23), backendDateFormat).atZone(ZoneId.systemDefault())))
                                    .count();
                        } catch (IOException e) {
                            log.warn("Could not check backend log entries!", e);
                            return 0;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            log.warn("Could not check backend log entries!", e);
            return 0;
        }
    }

    private static Map<Integer, Long> getResponseCountByStatus(int days) {
        try (Stream<Path> frontendLogs = Files.list(Paths.get("/logs"))) {
            return frontendLogs
                    .filter(p -> p.getFileName().toString().endsWith(".request.log"))
                    .map(p -> {
                        try (Stream<String> lines = Files.lines(p)) {
                            return lines
                                    .filter(l -> frontendLogIsRecentEnough(l, days))
                                    .map(l -> {
                                        Matcher m = frontendLogPatternStatusCode.matcher(l);
                                        if (!m.matches())
                                            throw new RuntimeException("Log line did not match pattern: " + l);
                                        return Integer.parseInt(m.group(1));
                                    })
                                    .collect(Collectors.toList());
                        } catch (IOException e) {
                            log.warn("Could not check frontend log entries!", e);
                            return Collections.<Integer>emptyList();
                        }
                    })
                    .flatMap(List::stream)
                    .collect(Collectors.groupingBy(Function.identity(), TreeMap::new, Collectors.counting()));
        } catch (IOException e) {
            log.warn("Could not check frontend log entries!", e);
            return Collections.emptyMap();
        }
    }

    private static boolean frontendLogIsRecentEnough(String l, int days) {
        Matcher m = frontendLogPattern.matcher(l);
        if (m.matches()) {
            return ZonedDateTime.now().minusDays(days).isBefore(
                    ZonedDateTime.parse(m.group(1), frontendDateFormat));
        }
        return false;
    }

    private static Map<String, Integer> countGitHubActionsPerRepository(int days) throws IOException {
        Map<String, Integer> result = new HashMap<>();
        int page = 1;

        while (true) {
            int curPage = page;
            JSONArray events = ConnectionUtils.runWithRetry(() -> {
                HttpURLConnection connAuth = ConnectionUtils.openConnectionWithTimeout("https://api.github.com/users/maddie480/events?page=" + curPage);
                connAuth.setRequestProperty("Authorization", "Basic " + SecretConstants.GITHUB_BASIC_AUTH);

                try (InputStream is = ConnectionUtils.connectionToInputStream(connAuth)) {
                    return new JSONArray(IOUtils.toString(is, StandardCharsets.UTF_8));
                }
            });

            for (Object o : events) {
                JSONObject item = (JSONObject) o;

                OffsetDateTime createdAt = OffsetDateTime.parse(item.getString("created_at"), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                if (createdAt.isBefore(OffsetDateTime.now().minusDays(days))) {
                    return result;
                }

                if (item.has("repo")) {
                    String repoName = item.getJSONObject("repo").getString("name");
                    int count = result.getOrDefault(repoName, 0);
                    result.put(repoName, count + 1);
                }
            }

            page++;
        }
    }

    private static int countGitLabActions(int days) throws IOException {
        int count = 0;
        int page = 1;

        while (true) {
            int curPage = page;
            JSONArray events = ConnectionUtils.runWithRetry(() -> {
                HttpURLConnection connAuth = ConnectionUtils.openConnectionWithTimeout("https://gitlab.com/api/v4/events?page=" + curPage);
                connAuth.setRequestProperty("Private-Token", SecretConstants.GITLAB_ACCESS_TOKEN);

                try (InputStream is = ConnectionUtils.connectionToInputStream(connAuth)) {
                    return new JSONArray(IOUtils.toString(is, StandardCharsets.UTF_8));
                }
            });

            for (Object o : events) {
                JSONObject item = (JSONObject) o;

                OffsetDateTime createdAt = OffsetDateTime.parse(item.getString("created_at"), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                if (createdAt.isBefore(OffsetDateTime.now().minusDays(days))) {
                    return count;
                }

                count++;
            }

            page++;
        }
    }
}
