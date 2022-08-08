package com.max480.discord.randombots;

import net.dv8tion.jda.api.JDA;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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

    /**
     * Refreshes the server counts once a day.
     * The actual counting is done by {@link ServerCountUploader}, which then calls this method.
     */
    public static void refreshServerCounts(int gamesBot, int customSlashCommands) {
        // update the server count on top.gg through the API
        updateBotGuildCount(SecretConstants.GAMES_BOT_CLIENT_ID, SecretConstants.GAMES_BOT_TOP_GG_TOKEN,
                "Games Bot", gamesBot);
        updateBotGuildCount(SecretConstants.CUSTOM_SLASH_COMMANDS_CLIENT_ID, SecretConstants.CUSTOM_SLASH_COMMANDS_TOP_GG_TOKEN,
                "Custom Slash Commands", customSlashCommands);
    }

    /**
     * Checks for new votes and comments and notifies the bot owner if there are any.
     * Called once an hour.
     *
     * @param internalBotClient A private bot instance used to post the notifications
     */
    public static void refreshVotes(JDA internalBotClient) {
        // check if we got new upvotes through the API
        getAndUpdateBotScore(SecretConstants.GAMES_BOT_CLIENT_ID, SecretConstants.GAMES_BOT_TOP_GG_TOKEN, internalBotClient,
                "Games Bot", () -> gamesBotScore, score -> gamesBotScore = score);
        getAndUpdateBotScore(SecretConstants.CUSTOM_SLASH_COMMANDS_CLIENT_ID, SecretConstants.CUSTOM_SLASH_COMMANDS_TOP_GG_TOKEN, internalBotClient,
                "Custom Slash Commands", () -> customSlashCommandsScore, score -> customSlashCommandsScore = score);

        // check if we got new ratings (through more... unconventional means)
        updateBotRatingCount(internalBotClient, SecretConstants.GAMES_BOT_CLIENT_ID,
                "Games Bot", () -> gamesBotRatingCount, score -> gamesBotRatingCount = score);
        updateBotRatingCount(internalBotClient, SecretConstants.CUSTOM_SLASH_COMMANDS_CLIENT_ID,
                "Custom Slash Commands", () -> customSlashCommandsRatingCount, score -> customSlashCommandsRatingCount = score);
    }

    private static void updateBotGuildCount(String botId, String botToken, String botName, int guildCount) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL("https://top.gg/api/bots/" + botId + "/stats").openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
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

        } catch (IOException e) {
            logger.error("Could not update top.gg guild count for {}", botName, e);
        }
    }

    private static void getAndUpdateBotScore(String botId, String botToken, JDA internalBotClient, String botName, Supplier<Integer> oldCount, Consumer<Integer> newCount) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL("https://top.gg/api/bots/" + botId).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Authorization", botToken);

            try (InputStream is = connection.getInputStream()) {
                JSONObject response = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                updateBotScore(internalBotClient, response.getInt("points"), response.getInt("monthlyPoints"), botName, oldCount, newCount);
            }
        } catch (IOException e) {
            logger.error("Could not get top.gg score for {}", botName, e);
        }
    }

    private static void updateBotScore(JDA internalBotClient, int points, int monthlyPoints, String botName, Supplier<Integer> oldCount, Consumer<Integer> newCount) {
        logger.debug("Got the top.gg score for {}: {}", botName, points);
        if (oldCount.get() != -1 && points != oldCount.get()) {
            logger.info("Score changed for {}! {} => {}", botName, oldCount.get(), points);
            internalBotClient.getGuildById(SecretConstants.REPORT_SERVER_ID)
                    .getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                    .sendMessage("The score for **" + botName + "** evolved! We now have **" + points + "** point" + (points == 1 ? "" : "s")
                            + " (**" + monthlyPoints + "** point" + (monthlyPoints == 1 ? "" : "s") + " this month).").queue();
        }
        newCount.accept(points);
    }

    private static void updateBotRatingCount(JDA internalBotClient, String botId, String botName, Supplier<Integer> oldCount, Consumer<Integer> newCount) {
        try {
            // there is a JSON schema belonging to a ... product in the page, and it has the ratings, so go get it.
            JSONObject productMetadata = new JSONObject(Jsoup.connect("https://top.gg/fr/bot/" + botId).get()
                    .select("script[type=\"application/ld+json\"]").html());

            if (productMetadata.isNull("aggregateRating")) {
                logger.debug("There is no rating for " + botName + " yet.");
                newCount.accept(0);
                return;
            }

            // ratingCount is the amount of reviews, ratingValue is the rating out of 100.
            JSONObject ratings = productMetadata.getJSONObject("aggregateRating");
            int newRatingCount = ratings.getInt("ratingCount");
            double score = ratings.getDouble("ratingValue");

            logger.debug("Got the top.gg rating count for {}: {}", botName, newRatingCount);
            if (oldCount.get() != -1 && newRatingCount != oldCount.get()) {
                logger.info("Rating count for {} changed! {} => {}", botName, oldCount.get(), newRatingCount);
                internalBotClient.getGuildById(SecretConstants.REPORT_SERVER_ID)
                        .getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                        .sendMessage("We got a new rating for **" + botName + "**! We now have **" + newRatingCount + "** rating" + (score == 1 ? "" : "s") + "." +
                                " The new medium rating is **" + new DecimalFormat("0.00").format(score / 20.) + "/5**.").queue();
            }
            newCount.accept(newRatingCount);
        } catch (IOException e) {
            logger.error("Could not get top.gg comment count for {}", botName, e);
        }
    }
}