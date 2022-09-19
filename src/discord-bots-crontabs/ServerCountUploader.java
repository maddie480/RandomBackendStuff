package com.max480.discord.randombots;

import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.Payload;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.common.collect.ImmutableMap;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uploads the server count for Timezone Bot, Mod Structure Verifier and Games Bot
 * to Google Cloud Storage, for display on the website.
 * Run every day.
 */
public class ServerCountUploader {
    private static final Storage storage = StorageOptions.newBuilder().setProjectId("max480-random-stuff").build().getService();

    private static final Logger logger = LoggerFactory.getLogger(ServerCountUploader.class);
    private static final Logging logging = LoggingOptions.getDefaultInstance().toBuilder().setProjectId("max480-random-stuff").build().getService();

    private static final Pattern guildIdGrabber = Pattern.compile(".*\"guild_id\": \"([0-9]+)\".*", Pattern.DOTALL);

    public static void run() throws IOException {
        int gamesBotServerCount = getServerUsageOfSlashCommandBot("/discord/games-bot");
        int customSlashCommandsServerCount = getServerUsageOfSlashCommandBot("/discord/custom-slash-commands");
        int timezoneBotServerCount = getServerUsageOfSlashCommandBot("/discord/timezone-bot");

        // write a file with all counts in it and send it to Cloud Storage.
        String yamlData = new Yaml().dump(ImmutableMap.of(
                "TimezoneBotLite", timezoneBotServerCount,
                "TimezoneBotFull", TimezoneBot.getServerCount(),
                "ModStructureVerifier", ModStructureVerifier.getServerCount(),
                "GamesBot", gamesBotServerCount,
                "CustomSlashCommands", customSlashCommandsServerCount
        ));
        CloudStorageUtils.sendStringToCloudStorage(yamlData, "bot_server_counts.yaml", "text/yaml");

        logger.info("Stats saved on Cloud Storage: {}", yamlData);

        TopGGCommunicator.refreshServerCounts(gamesBotServerCount, customSlashCommandsServerCount);
    }

    @NotNull
    private static int getServerUsageOfSlashCommandBot(String botPath) {
        Set<String> guilds = new HashSet<>();
        int logEntries = 0;

        for (LogEntry logEntry : logging.listLogEntries(
                Logging.EntryListOption.sortOrder(Logging.SortingField.TIMESTAMP, Logging.SortingOrder.DESCENDING),
                Logging.EntryListOption.filter("protoPayload.resource=\"" + botPath + "\" and timestamp >= \"" + ZonedDateTime.now().minusDays(30).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\""),
                Logging.EntryListOption.pageSize(100)
        ).iterateAll()) {

            String data = logEntry.<Payload.ProtoPayload>getPayload().getData().getValue().toStringUtf8();

            // what even is protobuf anyway, I'm just going to use a regex instead.
            final Matcher matcher = guildIdGrabber.matcher(data);
            if (matcher.matches()) {
                guilds.add(matcher.group(1));
            }

            logEntries++;
            if (logEntries % 100 == 0) {
                logger.debug("{} log entries for {} processed, found {} server ids so far", logEntries, botPath, guilds.size());
            }
        }

        return guilds.size();
    }
}
