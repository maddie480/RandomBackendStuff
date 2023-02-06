package com.max480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An internal service that gives the number of requests by HTTP code for the last 24 hours.
 * This also prints out the amount of times the Timezone Bot and Games Bot were used.
 * This is called once a day and the result is posted as YAML in a private channel on Discord to monitor server activity.
 */
public class UsageStatsService {
    private static final Logger log = LoggerFactory.getLogger(UsageStatsService.class);

    private static final Pattern frontendLogPattern = Pattern.compile(".*\\[(.* \\+0000)].*");
    private static final DateTimeFormatter frontendDateFormat = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss ZZZ", Locale.ENGLISH);

    private static final Pattern frontendLogPatternStatusCode = Pattern.compile(".* ([0-9]{3}) [0-9].*");

    private static final DateTimeFormatter backendDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static Map<String, Object> getStatistics() throws IOException {
        return ImmutableMap.of(
                "responseCountPerCode", getResponseCountByStatus(),
                "customSlashCommandsUsage", countFrontendLogEntries("/discord/custom-slash-commands"),
                "gamesBotUsage", countFrontendLogEntries("/discord/games-bot"),
                "timezoneBotLiteUsage", countFrontendLogEntries("/discord/timezone-bot"),
                "timezoneBotFullUsage", countTimezoneBotInvocationCount()
        );
    }

    private static int countFrontendLogEntries(String path) {
        try (Stream<Path> frontendLogs = Files.list(Paths.get("/logs"))) {
            return frontendLogs
                    .filter(p -> p.getFileName().toString().endsWith(".request.log"))
                    .mapToInt(p -> {
                        try (Stream<String> lines = Files.lines(p)) {
                            return (int) lines
                                    .filter(l -> l.contains("POST " + path))
                                    .filter(UsageStatsService::frontendLogIsLessThanOneDayOld)
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

    private static int countTimezoneBotInvocationCount() {
        try (Stream<Path> backendLogs = Files.list(Paths.get("/logs"))) {
            return backendLogs
                    .filter(p -> p.getFileName().toString().endsWith(".backend.log"))
                    .mapToInt(p -> {
                        try (Stream<String> lines = Files.lines(p)) {
                            return (int) lines
                                    .filter(l -> l.contains(".BotEventListener") && l.contains("New command: "))
                                    .filter(l -> ZonedDateTime.now().minusDays(1).isBefore(
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

    private static Map<Integer, Long> getResponseCountByStatus() {
        try (Stream<Path> frontendLogs = Files.list(Paths.get("/logs"))) {
            return frontendLogs
                    .filter(p -> p.getFileName().toString().endsWith(".request.log"))
                    .map(p -> {
                        try (Stream<String> lines = Files.lines(p)) {
                            return lines
                                    .filter(UsageStatsService::frontendLogIsLessThanOneDayOld)
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

    private static boolean frontendLogIsLessThanOneDayOld(String l) {
        Matcher m = frontendLogPattern.matcher(l);
        if (m.matches()) {
            return ZonedDateTime.now().minusDays(1).isBefore(
                    ZonedDateTime.parse(m.group(1), frontendDateFormat));
        }
        return false;
    }
}
