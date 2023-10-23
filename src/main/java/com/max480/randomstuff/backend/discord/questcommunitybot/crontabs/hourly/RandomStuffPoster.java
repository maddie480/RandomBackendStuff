package com.max480.randomstuff.backend.discord.questcommunitybot.crontabs.hourly;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.discord.questcommunitybot.ytdlp.YoutubeDlRandomCommand;
import com.max480.randomstuff.backend.discord.slashcommandbot.SlackToDiscord;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.apache.commons.io.function.IOSupplier;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Dumps completely random stuff into #webhook_hell. Literally random.
 */
public class RandomStuffPoster {
    private static final Logger logger = LoggerFactory.getLogger(RandomStuffPoster.class);

    private static final List<IOSupplier<MessageCreateData>> RANDOM_CONTENT_GENERATORS = Arrays.asList(
            () -> {
                List<String> all = Arrays.asList("/eddy", "/chucknorris", "/toplyrics", "/patoche", "/jcvd", "/languedebois",
                        "/noel", "/fakename", "/tendancesyoutube", "/putaclic", "/randomparrot", "/infopipo");

                String picked = all.get((int) (Math.random() * all.size()));
                logger.debug("This is Slash Command Bot randomly picked command {}", picked);

                return SlackToDiscord.sendSlashCommand(0L, 0L, picked, "", "").getLeft();
            },
            () -> {
                logger.debug("This is a random JDC entry");
                return SlackToDiscord.sendSlashCommand(0L, 0L, "/joiesducode", "", "").getLeft();
            },
            () -> {
                logger.debug("This is a random Monkey User entry");
                return SlackToDiscord.sendSlashCommand(0L, 0L, "/monkeyuser", "", "").getLeft();
            },
            () -> {
                logger.debug("This is a random xkcd entry");
                return SlackToDiscord.sendSlashCommand(0L, 0L, "/xkcd", "", "").getLeft();
            },
            () -> {
                logger.debug("This is a random TCRF entry");
                String link = grabRedirect("https://tcrf.net/Special:RandomRootpage");

                Document soup = ConnectionUtils.jsoupGetWithRetry(link);

                String text = soup.select(".mw-parser-output > p").stream()
                        .map(Element::text).collect(Collectors.joining("\n"));

                if (text.length() > 2000) text = text.substring(0, 1997) + "...";

                return MessageCreateData.fromEmbeds(new EmbedBuilder()
                        .setColor(Color.GRAY)
                        .setAuthor("The Cutting Room Floor")
                        .setTitle(soup.select("#firstHeading").text(), link)
                        .setDescription(text)
                        .build());
            },
            () -> {
                logger.debug("This is a random TV Tropes trope");
                return MessageCreateData.fromContent("https://tvtropes.org" +
                        ConnectionUtils.jsoupGetWithRetry("https://max480-random-stuff.appspot.com/tvtbridge?key=" + SecretConstants.RELOAD_SHARED_SECRET)
                                .select(".button-random-trope").attr("href"));
            },
            () -> {
                String origin = Math.random() > 0.5 ? "http://fondationscp.wikidot.com/system:random-scp" : "http://fondationscp.wikidot.com/system:random-tale";
                logger.debug("This is a random {} from the SCP website", origin.substring(origin.lastIndexOf("-") + 1));

                logger.debug("Following redirect from {}...", origin);
                String link = ConnectionUtils.jsoupGetWithRetry(origin)
                        .select("#page-content iframe").attr("src");
                link = link.substring(link.lastIndexOf("#") + 1);
                logger.debug("Redirect target is: {}", link);

                Document soup = ConnectionUtils.jsoupGetWithRetry(link);

                String text = soup.select("#page-content > p").stream()
                        .map(Element::text).collect(Collectors.joining("\n"));

                if (text.length() > 2000) text = text.substring(0, 1997) + "...";

                return MessageCreateData.fromEmbeds(new EmbedBuilder()
                        .setColor(Color.BLACK)
                        .setAuthor("Fondation SCP")
                        .setTitle(soup.select("#page-title").text(), link)
                        .setDescription(text)
                        .build());
            },
            () -> {
                logger.debug("This is a random entry from The Daily WTF");
                return MessageCreateData.fromContent("https://thedailywtf.com" + grabRedirect("https://thedailywtf.com/articles/random"));
            },
            () -> ConnectionUtils.runWithRetry(() -> {
                logger.debug("This is a random image from generator 1");
                HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(SecretConstants.RANDOM_SOURCES.get(0));
                connection.setInstanceFollowRedirects(true);

                InputStream is = ConnectionUtils.connectionToInputStream(connection);
                return new MessageCreateBuilder()
                        .addFiles(FileUpload.fromData(is, "image.jpg"))
                        .build();
            }), () -> {
                logger.debug("This is a random image from generator 2");
                Elements images = ConnectionUtils.jsoupGetWithRetry(SecretConstants.RANDOM_SOURCES.get(1)).select(SecretConstants.RANDOM_SOURCES.get(3));
                images.addAll(ConnectionUtils.jsoupGetWithRetry(SecretConstants.RANDOM_SOURCES.get(2)).select(SecretConstants.RANDOM_SOURCES.get(3)));
                Element image;

                do {
                    image = images.get((int) (Math.random() * images.size()));
                } while (!image.attr("src").contains(".png"));

                final String url = SecretConstants.RANDOM_SOURCES.get(4) + image.attr("src");

                return ConnectionUtils.runWithRetry(() -> {
                    InputStream is = ConnectionUtils.openStreamWithTimeout(url);
                    return new MessageCreateBuilder()
                            .addFiles(FileUpload.fromData(is, "image.png"))
                            .build();
                });
            },
            () -> {
                logger.debug("This is a random YouTube video");
                return MessageCreateData.fromContent(YoutubeDlRandomCommand.youtubeVideoRNG());
            }
    );

    private static String grabRedirect(String origin) throws IOException {
        logger.debug("Following redirect from {}...", origin);
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(origin);
        connection.setInstanceFollowRedirects(false);

        if (connection.getResponseCode() != 302) {
            throw new IOException("There was no redirect!");
        }

        String result = connection.getHeaderField("Location");
        logger.debug("Redirect target is: {}", result);
        return result;
    }

    public static void run(MessageChannel target) throws IOException {
        int rng = (int) (Math.random() * RANDOM_CONTENT_GENERATORS.size());
        logger.debug("Getting random stuff from random stuff generator #" + rng + "...");
        MessageCreateData data = RANDOM_CONTENT_GENERATORS.get(rng).get();
        target.sendMessage(data).queue();
    }
}
