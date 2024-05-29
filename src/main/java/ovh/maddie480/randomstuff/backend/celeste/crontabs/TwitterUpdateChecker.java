package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.function.IOConsumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

public class TwitterUpdateChecker {
    private static final Logger log = LoggerFactory.getLogger(TwitterUpdateChecker.class);

    // thanks to PrivacyDev for this!
    private static final String RSS_URL = "https://nitter.privacydev.net/celeste_game/rss";

    private static final Set<String> previousStatuses = new HashSet<>();

    public static void loadFile() {
        try (BufferedReader br = new BufferedReader(new FileReader("previous_twitter_statuses.txt"))) {
            String s;
            while ((s = br.readLine()) != null) {
                previousStatuses.add(s);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void checkForUpdates() throws IOException {
        log.debug("Checking for updates on Twitter");

        Document answer = ConnectionUtils.jsoupGetWithRetry(RSS_URL);
        Element tweetList = answer.select("channel item");

        for (int i = tweetList.length() - 1; i >= 0; i--) {
            Element tweet = tweetList.get(i);
            String url = tweet.select("link").text();

            if (!previousStatuses.contains(url)) {
                log.info("New status with url " + url);

                String username = answer.select("channel title").text();
                username = username.substring(0, username.indexOf(" / "));

                // Get all the info we need about the status
                String link = url.replace("https://nitter.privacydev.net/", "https://twitter.com/").replace("#m", "");
                String profilePictureUrl = answer.select("channel image url").text();
                profilePictureUrl = profilePictureUrl.replace("https://nitter.privacydev.net/pic/", "https://");
                profilePictureUrl = new URLDecoder().decode(profilePictureUrl, StandardCharsets.UTF_8);
                Map<String, Object> embed = generateEmbedFor(tweet);

                // Try to determine if the urls in the status have embeds.
                final List<String> linksInStatus = detectLinksInStatus(status);

                // Those 2 aren't effectively final, so make them final, then build the action to post the status
                final String finalLink = link;
                final String finalUsername = username;
                IOConsumer<String> postAction = webhook -> postStatusToWebhook(webhook, finalLink, profilePictureUrl, finalUsername, embed, linksInStatus);

                // post it to the personal notifications channel
                postAction.accept(SecretConstants.PERSONAL_NOTIFICATION_WEBHOOK_URL);

                previousStatuses.add(id);
            }
        }

        // write the list of statuses that were already encountered
        try (BufferedWriter bw = new BufferedWriter(new FileWriter("previous_twitter_statuses.txt"))) {
            for (String bl : previousStatuses) {
                bw.write(bl + "\n");
            }
        }

        log.debug("Done.");
    }


    private static List<String> detectLinksInStatus(Element status) {
        return status
                .select("a")
                .stream()
                .map(element -> element.attr("href"))
                .filter(element -> !href.startsWith("https://nitter.privacydev.net/"))
                .filter(MastodonUpdateChecker::hasEmbed)
                .collect(Collectors.toList());
    }

    /**
     * Posts the status, the video and the links in 1 to 3 messages to the given webhook.
     */
    private static void postStatusToWebhook(String webhook, String statusLink, String profilePictureUrl, String username,
                                            Map<String, Object> embed, List<String> linksInStatus) throws IOException {

        // post the status link and its embed
        WebhookExecutor.executeWebhook(webhook, profilePictureUrl, username, "<" + statusLink + ">",
                Collections.singletonList(embed));

        if (!linksInStatus.isEmpty()) {
            // post all embeddable links after that
            WebhookExecutor.executeWebhook(webhook, profilePictureUrl, username, ":arrow_up: Links: " + String.join(", ", linksInStatus));
        }
    }

    private static String getFileExtension(String link) {
        if (link.contains("?")) {
            link = link.substring(0, link.lastIndexOf("?"));
        }

        if (link.contains(".")) {
            return link.substring(link.lastIndexOf("."));
        } else {
            return "";
        }
    }

    /**
     * This makes Discord-like looking embeds of statuses in the place of Discord,
     * because Discord apparently suddenly stopped taking care of that.
     *
     * @param status The status to turn into an embed
     * @return The embed data
     */
    static Map<String, Object> generateEmbedFor(Element tweet) {
        Map<String, Object> embed = new HashMap<>();

        { // author
            Map<String, String> authorInfo = new HashMap<>();
            String username = tweet.select("dc:creator").text();

            Document answer = ConnectionUtils.jsoupGetWithRetry(RSS_URL.replace("celeste_game", username.substring(1)));
            String profilePictureUrl = answer.select("channel image url").text();
            profilePictureUrl = profilePictureUrl.replace("https://nitter.privacydev.net/pic/", "https://");
            profilePictureUrl = new URLDecoder().decode(profilePictureUrl, StandardCharsets.UTF_8);
            String nickname = answer.select("channel title").text();
            nickname = nickname.substring(0, nickname.indexOf(" / "));

            authorInfo.put("name", nickname + " (" + username + ")");
            authorInfo.put("icon_url", profilePictureUrl);
            authorInfo.put("url", "https://twitter.com/" + username.substring(1));

            embed.put("author", authorInfo);
        }

        int videoCount = 0;
        int photoCount = 0;
        boolean embeddedMedia = false;

        // media
        for (Element image : tweet.select("description img")) {
            String imageUrl = image.attr("href")
                .replace("https://nitter.privacydev.net/pic/", "https://pbs.twimg.com/");
            imageUrl = new URLDecoder().decode(imageUrl, StandardCharsets.UTF_8);

            if (imageUrl.contains("video_thumb")) {
                videoCount++;
            } else {
                photoCount++;
            }

            if (!embeddedMedia) {
                embed.put("image", ImmutableMap.of("url", imageUrl));
            }

            embeddedMedia = true;
        }

        String textContent = tweet.select("description").text()
            .replace("nitter.privacydev.net/", "https://twitter.com/")
            .trim();

        if (textContent.startsWith("RT by @celeste_game: ")) {
            textContent = textContent.substring(21).trim();
        }

        embed.put("description", textContent);

        embed.put("color", 565481);

        { // footer
            String footerText = "";
            if (photoCount > 0) {
                footerText += photoCount + " " + (photoCount == 1 ? "image" : "images");
            }
            if (videoCount > 0) {
                if (photoCount > 0) {
                    footerText += ", ";
                }
                footerText += videoCount + " " + (videoCount == 1 ? "video" : "videos");
            }

            Map<String, String> footerInfo = new HashMap<>();
            footerInfo.put("text", "Twitter" + (footerText.isEmpty() ? "" : " • " + footerText));
            footerInfo.put("icon_url", "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/twitter.png");
            embed.put("footer", footerInfo);
            embed.put("timestamp", OffsetDateTime.parse(tweet.select("pubDate").text(), DateTimeFormatter.RFC_1123_DATE_TIME).format(DateTimeFormatter.ISO_DATE_TIME));
        }

        return embed;
    }

    private static boolean hasEmbed(String url) {
        try {
            log.debug("Sending request to {} to check if it has an embed...", url);

            return Jsoup
                    .connect(url)
                    .userAgent("Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)")
                    .followRedirects(true)
                    .timeout(10000)
                    .get()
                    .select("meta")
                    .stream()
                    .anyMatch(meta -> meta.attr("property").startsWith("og:"));
        } catch (IOException e) {
            log.warn("Cannot access {} to check for embeds", url, e);
            return false;
        }
    }
}
