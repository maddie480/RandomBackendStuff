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

    private static int timezoneBotScore = -1;
    private static int timezoneBotRatingCount = -1;
    private static int gamesBotScore = -1;
    private static int gamesBotRatingCount = -1;

    public static void refresh(final JDA jda) {
        // update the server count on top.gg through the API
        int guildCount = jda.getGuilds().size();
        updateBotGuildCount(SecretConstants.TIMEZONE_BOT_ID, SecretConstants.TIMEZONE_BOT_TOP_GG_TOKEN, "Timezone Bot", guildCount);

        // check if we got new upvotes through the API
        getAndUpdateBotScore(SecretConstants.TIMEZONE_BOT_ID, SecretConstants.TIMEZONE_BOT_TOP_GG_TOKEN, jda, "Timezone Bot", () -> timezoneBotScore, score -> timezoneBotScore = score);
        getAndUpdateBotScore(SecretConstants.GAMES_BOT_ID, SecretConstants.GAMES_BOT_TOP_GG_TOKEN, jda, "Games Bot", () -> gamesBotScore, score -> gamesBotScore = score);

        // check if we got new ratings (through more... unconventional means)
        updateBotRatingCount(jda, SecretConstants.TIMEZONE_BOT_ID, "Timezone Bot", () -> timezoneBotRatingCount, score -> timezoneBotRatingCount = score);
        updateBotRatingCount(jda, SecretConstants.GAMES_BOT_ID, "Games Bot", () -> gamesBotRatingCount, score -> gamesBotRatingCount = score);
    }

    public static void updateBotGuildCount(long botId, String botToken, String botName, int guildCount) {
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

    private static void getAndUpdateBotScore(long botId, String botToken, JDA jda, String botName, Supplier<Integer> oldCount, Consumer<Integer> newCount) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL("https://top.gg/api/bots/" + botId).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Authorization", botToken);

            try (InputStream is = connection.getInputStream()) {
                JSONObject response = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                updateBotScore(jda, response.getInt("points"), response.getInt("monthlyPoints"), botName, oldCount, newCount);
            }
        } catch (IOException e) {
            logger.error("Could not get top.gg score for {}", botName, e);
        }
    }

    private static void updateBotScore(JDA jda, int points, int monthlyPoints, String botName, Supplier<Integer> oldCount, Consumer<Integer> newCount) {
        logger.debug("Got the top.gg score for {}: {}", botName, points);
        if (oldCount.get() != -1 && points != oldCount.get()) {
            logger.info("Score changed for {}! {} => {}", botName, oldCount.get(), points);
            jda.getGuildById(SecretConstants.REPORT_SERVER_ID)
                    .getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                    .sendMessage("The score for **" + botName + "** evolved! We now have **" + points + "** point" + (points == 1 ? "" : "s")
                            + " (**" + monthlyPoints + "** point" + (monthlyPoints == 1 ? "" : "s") + " this month).").queue();
        }
        newCount.accept(points);
    }

    private static void updateBotRatingCount(JDA jda, Long botId, String botName, Supplier<Integer> oldCount, Consumer<Integer> newCount) {
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

            logger.debug("Got the top.gg rating count: {}", newRatingCount);
            if (oldCount.get() != -1 && newRatingCount != oldCount.get()) {
                logger.info("Rating count for {} changed! {} => {}", botName, oldCount.get(), newRatingCount);
                jda.getGuildById(SecretConstants.REPORT_SERVER_ID)
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
