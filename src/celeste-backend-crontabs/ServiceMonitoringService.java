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
 * An internal service that gives the backend ("bot") and frontend ("website") uptimes, and the number of requests
 * by HTTP code for the last 24 hours, based on Google Cloud Monitoring.
 * This is called once a day and the result is posted as YAML in a private channel on Discord to monitor server activity.
 */
public class ServiceMonitoringService {
    private static final Logging logging = LoggingOptions.getDefaultInstance().toBuilder().setProjectId("max480-random-stuff").build().getService();
    private static final ExecutorService executor = Executors.newFixedThreadPool(2);

    public static Map<String, Object> getStatistics() throws IOException {
        Future<Integer> gamesBotUsage = countLogEntriesAsync("protoPayload.resource=\"/discord/games-bot\"");
        Future<Integer> timezoneBotUsage = countLogEntriesAsync("labels.loggerName=\"com.max480.discord.randombots.TimezoneBot\" and jsonPayload.message =~ \"^New command: .*\"");

        try (MetricServiceClient client = MetricServiceClient.create()) {
            return ImmutableMap.of(
                    "uptimePercent", getUptimes(client),
                    "responseCountPerCode", getResponseCount(client),
                    "gamesBotUsage", gamesBotUsage.get(),
                    "timezoneBotUsage", timezoneBotUsage.get()
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

    private static Map<String, Double> getUptimes(MetricServiceClient client) {
        final MetricServiceClient.ListTimeSeriesPagedResponse timeSeries = client.listTimeSeries(ListTimeSeriesRequest.newBuilder()
                .setName("projects/max480-random-stuff")
                .setFilter("metric.type = \"monitoring.googleapis.com/uptime_check/check_passed\"")
                .setInterval(TimeInterval.newBuilder()
                        .setStartTime(Timestamp.newBuilder()
                                .setSeconds(System.currentTimeMillis() / 1000 - 86400)
                                .build())
                        .setEndTime(Timestamp.newBuilder()
                                .setSeconds(System.currentTimeMillis() / 1000)
                                .build())
                        .build())
                .build());

        long[] up = new long[]{0, 0, 0};
        long[] total = new long[]{0, 0, 0};

        for (TimeSeries series : timeSeries.iterateAll()) {
            int index;
            if (series.getMetric().getLabelsOrThrow("check_id").equals("bot-healthcheck-F_-kI5b144Q")
                    || series.getMetric().getLabelsOrThrow("check_id").equals("bot-healthcheck")
                    || series.getMetric().getLabelsOrThrow("check_id").equals("bot-healthcheck-ZG_z89RwZLc")) {
                index = 0;
            } else if (series.getMetric().getLabelsOrThrow("check_id").equals("website-healthcheck")
                    || series.getMetric().getLabelsOrThrow("check_id").equals("website-healthcheck-pHdPnOpXsNs")) {
                index = 1;
            } else if (series.getMetric().getLabelsOrThrow("check_id").equals("celestemodupdater-0x0a-de")) {
                index = 2;
            } else {
                throw new RuntimeException("Encountered bad check_id!" + series.getMetric().getLabelsOrThrow("check_id"));
            }

            for (Point p : series.getPointsList()) {
                total[index]++;
                if (p.getValue().getBoolValue()) {
                    up[index]++;
                }
            }
        }
        return ImmutableMap.of(
                "bot", (double) up[0] / total[0] * 100.0,
                "website", (double) up[1] / total[1] * 100.0,
                "mirror", (double) up[2] / total[2] * 100.0
        );
    }
}
