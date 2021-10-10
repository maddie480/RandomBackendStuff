package com.max480.discord.randombots;

import net.dv8tion.jda.api.JDA;
import org.discordbots.api.client.DiscordBotListAPI;
import org.discordbots.api.client.entity.Bot;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    private static int timezoneBotScore = -1;
    private static int timezoneBotRatingCount = -1;
    private static int gamesBotScore = -1;
    private static int gamesBotRatingCount = -1;

    public static void refresh(final JDA jda) {
        // update the server count on top.gg through the API
        int guildCount = jda.getGuilds().size();
        botListAPI.setStats(guildCount).thenRun(() -> logger.debug("Updated guild count for Timezone Bot on top.gg with {}.", guildCount));

        // check if we got new upvotes through the API
        botListAPI.getBot(SecretConstants.TIMEZONE_BOT_ID.toString())
                .thenAcceptAsync(bot -> updateBotScore(jda, bot, "Timezone Bot", () -> timezoneBotScore, score -> timezoneBotScore = score));
        botListAPI.getBot(SecretConstants.GAMES_BOT_ID.toString())
                .thenAcceptAsync(bot -> updateBotScore(jda, bot, "Games Bot", () -> gamesBotScore, score -> gamesBotScore = score));

        // check if we got new ratings (through more... unconventional means)
        new Thread(() -> updateBotRatingCount(jda, SecretConstants.TIMEZONE_BOT_ID, "Timezone Bot", () -> timezoneBotRatingCount, score -> timezoneBotRatingCount = score)).start();
        new Thread(() -> updateBotRatingCount(jda, SecretConstants.GAMES_BOT_ID, "Games Bot", () -> gamesBotRatingCount, score -> gamesBotRatingCount = score)).start();
    }

    private static void updateBotScore(JDA jda, Bot bot, String botName, Supplier<Integer> oldCount, Consumer<Integer> newCount) {
        logger.debug("Got the top.gg score for {}: {}", botName, bot.getPoints());
        if (oldCount.get() != -1 && bot.getPoints() != oldCount.get()) {
            logger.info("Score changed for {}! {} => {}", botName, oldCount.get(), bot.getPoints());
            jda.getGuildById(SecretConstants.REPORT_SERVER_ID)
                    .getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                    .sendMessage("The score for **" + botName + "** evolved! We now have **" + bot.getPoints() + "** point" + (bot.getPoints() == 1 ? "" : "s")
                            + " (**" + bot.getMonthlyPoints() + "** point" + (bot.getMonthlyPoints() == 1 ? "" : "s") + " this month).").queue();
        }
        newCount.accept(bot.getPoints());
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
            logger.error("Could not check out comment count from top.gg", e);
        }
    }
}
