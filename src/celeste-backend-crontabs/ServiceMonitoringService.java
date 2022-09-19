package com.max480.discord.randombots;

import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.common.collect.ImmutableMap;
import com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.protobuf.Timestamp;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * An internal service that gives the number of requests by HTTP code for the last 24 hours, based on Google Cloud Monitoring.
 * This also prints out the amount of times the Timezone Bot and Games Bot were used, based on Google Cloud Logging.
 * This is called once a day and the result is posted as YAML in a private channel on Discord to monitor server activity.
 */
public class ServiceMonitoringService {
    private static final Logging logging = LoggingOptions.getDefaultInstance().toBuilder().setProjectId("max480-random-stuff").build().getService();
    private static final ExecutorService executor = Executors.newFixedThreadPool(2);

    public static Map<String, Object> getStatistics() throws IOException {
        Future<Integer> gamesBotUsage = countLogEntriesAsync("protoPayload.resource=\"/discord/games-bot\"");
        Future<Integer> customSlashCommandsUsage = countLogEntriesAsync("protoPayload.resource=\"/discord/custom-slash-commands\"");
        Future<Integer> timezoneBotLiteUsage = countLogEntriesAsync("protoPayload.resource=\"/discord/timezone-bot\"");
        Future<Integer> timezoneBotFullUsage = countLogEntriesAsync("labels.loggerName=\"com.max480.discord.randombots.BotEventListener\" and jsonPayload.message =~ \"^New command: .*\"");
        Future<Integer> restartCount = countLogEntriesAsync("protoPayload.resource=\"/_ah/warmup\"");

        try (MetricServiceClient client = MetricServiceClient.create()) {
            return ImmutableMap.of(
                    "responseCountPerCode", getResponseCount(client),
                    "restartCount", restartCount.get(),
                    "customSlashCommandsUsage", customSlashCommandsUsage.get(),
                    "gamesBotUsage", gamesBotUsage.get(),
                    "timezoneBotLiteUsage", timezoneBotLiteUsage.get(),
                    "timezoneBotFullUsage", timezoneBotFullUsage.get()
            );
        } catch (ExecutionException | InterruptedException e) {
            throw new IOException(e);
        }
    }

    private static Future<Integer> countLogEntriesAsync(String filter) {
        return executor.submit(() -> {
            int count = 0;
            for (LogEntry ignored : logging.listLogEntries(
                    Logging.EntryListOption.sortOrder(Logging.SortingField.TIMESTAMP, Logging.SortingOrder.DESCENDING),
                    Logging.EntryListOption.filter(filter + " and timestamp >= \"" + ZonedDateTime.now().minusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\""),
                    Logging.EntryListOption.pageSize(1000)
            ).iterateAll()) {
                count++;
            }
            return count;
        });
    }

    private static Map<String, Long> getResponseCount(MetricServiceClient client) {
        final MetricServiceClient.ListTimeSeriesPagedResponse timeSeries = client.listTimeSeries(ListTimeSeriesRequest.newBuilder()
                .setName("projects/max480-random-stuff")
                .setFilter("metric.type = \"appengine.googleapis.com/http/server/response_count\"")
                .setInterval(TimeInterval.newBuilder()
                        .setStartTime(Timestamp.newBuilder()
                                .setSeconds(System.currentTimeMillis() / 1000 - 86400)
                                .build())
                        .setEndTime(Timestamp.newBuilder()
                                .setSeconds(System.currentTimeMillis() / 1000)
                                .build())
                        .build())
                .build());

        Map<String, Long> countPerResponseCode = new TreeMap<>();
        for (TimeSeries series : timeSeries.iterateAll()) {
            long count = 0;
            for (Point p : series.getPointsList()) {
                count += p.getValue().getInt64Value();
            }

            String responseCode = series.getMetric().getLabelsOrThrow("response_code");
            count += countPerResponseCode.getOrDefault(responseCode, 0L);
            countPerResponseCode.put(responseCode, count);
        }
        return countPerResponseCode;
    }
}
