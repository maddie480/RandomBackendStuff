package com.max480.discord.randombots;

import net.dv8tion.jda.api.JDA;
import org.discordbots.api.client.DiscordBotListAPI;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.DecimalFormat;

/**
 * This class is responsible for updating the bot guild count on top.gg,
 * and checking if there are any reviews or votes.
 */
public class TopGGCommunicator {
    private static final Logger logger = LoggerFactory.getLogger(TopGGCommunicator.class);

    private static final DiscordBotListAPI botListAPI = new DiscordBotListAPI.Builder()
            .botId(SecretConstants.TIMEZONE_BOT_ID.toString())
            .token(SecretConstants.TIMEZONE_BOT_TOP_GG_TOKEN)
            .build();

    private static int score = -1;
    private static int ratingCount = -1;

    public static void refresh(final JDA jda) {
        // update the server count on top.gg through the API
        int guildCount = jda.getGuilds().size();
        botListAPI.setStats(guildCount).thenRun(() -> logger.debug("Updated guild count on top.gg with {}.", guildCount));

        // check if we got new upvotes through the API
        botListAPI.getBot(SecretConstants.TIMEZONE_BOT_ID.toString())
                .thenAcceptAsync(bot -> {
                    logger.debug("Got the top.gg score: {}", bot.getPoints());
                    if (score != -1 && bot.getPoints() != score) {
                        logger.info("Score changed! {} => {}", score, bot.getPoints());
                        jda.getGuildById(SecretConstants.REPORT_SERVER_ID)
                                .getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                                .sendMessage("Our score evolved! We now have **" + bot.getPoints() + "** point" + (bot.getPoints() == 1 ? "" : "s")
                                        + " (**" + bot.getMonthlyPoints() + "** point" + (bot.getMonthlyPoints() == 1 ? "" : "s") + " this month).").queue();
                    }
                    score = bot.getPoints();
                });

        // check if we got new ratings (through more... unconventional means)
        new Thread(() -> {
            try {
                // there is a JSON schema belonging to a ... product in the page, and it has the ratings, so go get it.
                JSONObject productMetadata = new JSONObject(Jsoup.connect("https://top.gg/fr/bot/" + SecretConstants.TIMEZONE_BOT_ID).get()
                        .select("script[type=\"application/ld+json\"]").html());

                if (productMetadata.isNull("aggregateRating")) {
                    logger.debug("There is no rating yet.");
                    return;
                }

                // ratingCount is the amount of reviews, ratingValue is the rating out of 100.
                JSONObject ratings = productMetadata.getJSONObject("aggregateRating");
                int newRatingCount = ratings.getInt("ratingCount");
                double score = ratings.getDouble("ratingValue");

                logger.debug("Got the top.gg rating count: {}", newRatingCount);
                if (ratingCount != -1 && newRatingCount != ratingCount) {
                    logger.info("Rating count changed! {} => {}", ratingCount, newRatingCount);
                    jda.getGuildById(SecretConstants.REPORT_SERVER_ID)
                            .getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                            .sendMessage("We got a new rating! We now have **" + newRatingCount + "** rating" + (score == 1 ? "" : "s") + "." +
                                    " The new medium rating is **" + new DecimalFormat("0.00").format(score / 20.) + "/5**.").queue();
                }
                ratingCount = newRatingCount;
            } catch (IOException e) {
                logger.error("Could not check out comment count from top.gg", e);
            }
        }).start();
    }
}
