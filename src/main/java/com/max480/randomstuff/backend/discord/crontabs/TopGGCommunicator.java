package com.max480.randomstuff.backend.discord.crontabs;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * This class is responsible for updating the bot guild count on top.gg,
 * and checking if there are any reviews or votes.
 */
public class TopGGCommunicator {
    private static final Logger logger = LoggerFactory.getLogger(TopGGCommunicator.class);

    private static int gamesBotScore = -1;
    private static int customSlashCommandsScore = -1;
    private static int timezoneBotScore = -1;

    /**
     * Refreshes the server counts once a day.
     * The actual counting is done by {@link ServerCountUploader}, which then calls this method.
     */
    public static void refreshServerCounts(int gamesBot, int customSlashCommands, int timezoneBot) throws IOException {
        // update the server count on top.gg through the API
        updateBotGuildCount(SecretConstants.GAMES_BOT_CLIENT_ID, SecretConstants.GAMES_BOT_TOP_GG_TOKEN,
                "Games Bot", gamesBot);
        updateBotGuildCount(SecretConstants.CUSTOM_SLASH_COMMANDS_CLIENT_ID, SecretConstants.CUSTOM_SLASH_COMMANDS_TOP_GG_TOKEN,
                "Custom Slash Commands", customSlashCommands);
        updateBotGuildCount(SecretConstants.TIMEZONE_BOT_LITE_CLIENT_ID, SecretConstants.TIMEZONE_BOT_LITE_TOP_GG_TOKEN,
                "Timezone Bot", timezoneBot);
    }

    /**
     * Checks for new votes and notifies the bot owner if there are any.
     * Called once an hour.
     *
     * @param messagePoster A method used to post private notification messages
     */
    public static void refreshVotes(Consumer<String> messagePoster) throws IOException {
        // check if we got new upvotes through the API
        getAndUpdateBotScore(SecretConstants.GAMES_BOT_CLIENT_ID, SecretConstants.GAMES_BOT_TOP_GG_TOKEN, messagePoster,
                "Games Bot", () -> gamesBotScore, score -> gamesBotScore = score);
        getAndUpdateBotScore(SecretConstants.CUSTOM_SLASH_COMMANDS_CLIENT_ID, SecretConstants.CUSTOM_SLASH_COMMANDS_TOP_GG_TOKEN, messagePoster,
                "Custom Slash Commands", () -> customSlashCommandsScore, score -> customSlashCommandsScore = score);
        getAndUpdateBotScore(SecretConstants.TIMEZONE_BOT_LITE_CLIENT_ID, SecretConstants.TIMEZONE_BOT_LITE_TOP_GG_TOKEN, messagePoster,
                "Timezone Bot", () -> timezoneBotScore, score -> timezoneBotScore = score);
    }

    private static void updateBotGuildCount(String botId, String botToken, String botName, int guildCount) throws IOException {
        ConnectionUtils.runWithRetry(() -> {
            HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://top.gg/api/bots/" + botId + "/stats");
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", botToken);
            connection.setRequestProperty("Content-Type", "application/json");

            JSONObject body = new JSONObject();
            body.put("server_count", guildCount);
            try (OutputStream os = connection.getOutputStream()) {
                IOUtils.write(body.toString(), os, StandardCharsets.UTF_8);
            }

            if (connection.getResponseCode() != 200) {
                throw new IOException("Non-200 response code: " + connection.getResponseCode());
            }

            logger.debug("Updated guild count for {} on top.gg with {}.", botName, guildCount);

            // fulfill method signature
            return null;
        });
    }

    private static void getAndUpdateBotScore(String botId, String botToken, Consumer<String> messagePoster, String botName, Supplier<Integer> oldCount, Consumer<Integer> newCount) throws IOException {
        ConnectionUtils.runWithRetry(() -> {
            HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://top.gg/api/bots/" + botId);
            connection.setRequestProperty("Authorization", botToken);

            try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
                JSONObject response = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                updateBotScore(messagePoster, response.getInt("points"), response.getInt("monthlyPoints"), botName, oldCount, newCount);
            }

            // fulfill method signature
            return null;
        });
    }

    private static void updateBotScore(Consumer<String> messagePoster, int points, int monthlyPoints, String botName, Supplier<Integer> oldCount, Consumer<Integer> newCount) {
        logger.debug("Got the top.gg score for {}: {}", botName, points);
        if (oldCount.get() != -1 && points != oldCount.get()) {
            logger.info("Score changed for {}! {} => {}", botName, oldCount.get(), points);
            messagePoster.accept("The score for **" + botName + "** evolved! We now have **" + points + "** point" + (points == 1 ? "" : "s")
                    + " (**" + monthlyPoints + "** point" + (monthlyPoints == 1 ? "" : "s") + " this month).");
        }
        newCount.accept(points);
    }
}