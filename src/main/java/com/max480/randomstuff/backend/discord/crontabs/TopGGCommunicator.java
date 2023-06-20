package com.max480.randomstuff.backend.discord.crontabs;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import com.max480.randomstuff.backend.utils.WebhookExecutor;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * This class is responsible for updating the bot guild count on top.gg,
 * and checking if there are any reviews or votes.
 */
public class TopGGCommunicator {
    private static final Logger logger = LoggerFactory.getLogger(TopGGCommunicator.class);

    private static int gamesBotScore = -1;
    private static int gamesBotRatingCount = -1;

    private static int customSlashCommandsScore = -1;
    private static int customSlashCommandsRatingCount = -1;

    private static int timezoneBotScore = -1;
    private static int timezoneBotRatingCount = -1;

    private static int triesLeft = 5;
    private static int triesResetIn = 0;

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
     * Checks for new votes and comments and notifies the bot owner if there are any.
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

        if (triesLeft <= 0) {
            triesResetIn--;
            logger.debug("Stopped attempts at updating the votes for {} hour(s)", triesResetIn);

            if (triesResetIn <= 0) {
                logger.debug("Doing one more attempt!");
                triesLeft = 1;
            } else {
                return;
            }
        }

        try {
            // check if we got new ratings (through more... unconventional means)
            updateBotRatingCount(messagePoster, SecretConstants.GAMES_BOT_CLIENT_ID,
                    "Games Bot", () -> gamesBotRatingCount, score -> gamesBotRatingCount = score);
            updateBotRatingCount(messagePoster, SecretConstants.CUSTOM_SLASH_COMMANDS_CLIENT_ID,
                    "Custom Slash Commands", () -> customSlashCommandsRatingCount, score -> customSlashCommandsRatingCount = score);
            updateBotRatingCount(messagePoster, SecretConstants.TIMEZONE_BOT_LITE_CLIENT_ID,
                    "Timezone Bot", () -> timezoneBotRatingCount, score -> timezoneBotRatingCount = score);

            triesLeft = 5;
        } catch (IOException e) {
            logger.error("Could not fetch votes", e);
            triesLeft--;

            WebhookExecutor.executeWebhook(
                    SecretConstants.UPDATE_CHECKER_LOGS_HOOK,
                    "https://cdn.discordapp.com/attachments/445236692136230943/921309225697804299/compute_engine.png",
                    "Top.gg Communicator",
                    ":warning: Error while fetching votes (" + triesLeft + (triesLeft == 1 ? " try" : " tries") + " left).\n" + e);

            if (triesLeft <= 0) {
                logger.debug("Stopping attempts at updating the votes for 24 hours");
                triesResetIn = 24;
            }
        }
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

    private static void updateBotRatingCount(Consumer<String> messagePoster, String botId, String botName, Supplier<Integer> oldCount, Consumer<Integer> newCount) throws IOException {
        ConnectionUtils.runWithRetry(() -> {
            // there is a JSON schema belonging to a ... product in the page, and it has the ratings, so go get it.
            JSONObject productMetadata = new JSONObject(ConnectionUtils.jsoupGetWithRetry("https://top.gg/fr/bot/" + botId)
                    .select("script[type=\"application/ld+json\"]").html());

            if (productMetadata.isNull("aggregateRating")) {
                logger.debug("There is no rating for " + botName + " yet.");
                newCount.accept(0);
                return null;
            }

            // ratingCount is the amount of reviews, ratingValue is the rating out of 100.
            JSONObject ratings = productMetadata.getJSONObject("aggregateRating");
            int newRatingCount = ratings.getInt("ratingCount");
            double score = ratings.getDouble("ratingValue");

            logger.debug("Got the top.gg rating count for {}: {}", botName, newRatingCount);
            if (oldCount.get() != -1 && newRatingCount != oldCount.get()) {
                logger.info("Rating count for {} changed! {} => {}", botName, oldCount.get(), newRatingCount);
                messagePoster.accept("We got a new rating for **" + botName + "**! We now have **" + newRatingCount + "** rating" + (score == 1 ? "" : "s") + "." +
                        " The new medium rating is **" + new DecimalFormat("0.00").format(score / 20.) + "/5**.");
            }
            newCount.accept(newRatingCount);

            // fulfill method signature
            return null;
        });
    }
}