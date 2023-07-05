package com.max480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import com.max480.randomstuff.backend.utils.WebhookExecutor;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

public class TwitterUpdateChecker {
    private static final Logger log = LoggerFactory.getLogger(TwitterUpdateChecker.class);

    private static final Pattern twitterLinkAtEnd = Pattern.compile("^.*(https://t\\.co/[A-Za-z0-9]+)$", Pattern.DOTALL);

    private static final Set<String> previousTweets = new HashSet<>();

    public static void runCheckForUpdates() throws Exception {
        try {
            checkForUpdates();
        } catch (Exception e) {
            log.error("Error while checking new tweets", e);
            throw e;
        }
    }

    /**
     * Loads all previous tweet IDs from disk.
     * Ran on bot startup.
     */
    public static void loadFile() {
        try (BufferedReader br = new BufferedReader(new FileReader("previous_twitter_messages_celeste_game.txt"))) {
            String s;
            while ((s = br.readLine()) != null) {
                previousTweets.add(s);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Checks for updates on the celeste_game feed.
     */
    private static void checkForUpdates() throws IOException {
        log.debug("Checking for updates on Twitter feeds");

        JSONArray answer;
        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/celeste-game-twitter-raw.json"))) {
            answer = new JSONArray(IOUtils.toString(is, UTF_8));
        }

        for (int i = answer.length() - 1; i >= 0; i--) {
            JSONObject tweet = answer.getJSONObject(i);
            String id = tweet.getString("id_str");

            if (!previousTweets.contains(id)) {
                boolean recentSelfRetweet = false;
                if (tweet.has("retweeted_status") && tweet.getJSONObject("user").getLong("id") == tweet.getJSONObject("retweeted_status").getJSONObject("user").getLong("id")) {
                    // account retweeted itself! do not repost the tweet if the original tweet was less than a week before.

                    long retweetDate = OffsetDateTime.parse(
                                    tweet.getString("created_at"),
                                    DateTimeFormatter.ofPattern("E MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH))
                            .toEpochSecond();

                    long originalTweetDate = OffsetDateTime.parse(
                                    tweet.getJSONObject("retweeted_status").getString("created_at"),
                                    DateTimeFormatter.ofPattern("E MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH))
                            .toEpochSecond();

                    // 604800 seconds = 7 days
                    recentSelfRetweet = (retweetDate - originalTweetDate) < 604800;
                }

                if (!recentSelfRetweet && (!tweet.has("in_reply_to_user_id_str") || "".equals(tweet.getString("in_reply_to_user_id_str")))) {
                    log.info("New tweet with id " + id);

                    // Get all the info we need about the tweet
                    String link = "https://twitter.com/" + tweet.getJSONObject("user").getString("screen_name") + "/status/" + id;
                    long date = OffsetDateTime.parse(tweet.getString("created_at"), DateTimeFormatter.ofPattern("E MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH)).toEpochSecond();
                    String profilePictureUrl = tweet.getJSONObject("user").getString("profile_image_url_https").replace("_normal", "");
                    String username = tweet.getJSONObject("user").getString("name");
                    Map<String, Object> embed = generateEmbedFor(tweet);

                    // Discord doesn't support embedding videos in their embeds... come on!
                    String videoUrl = null;
                    File videoFile = null;
                    if (embed.containsKey("video")) {
                        videoUrl = ((Map<String, String>) embed.get("video")).get("url");
                        videoFile = new File("/tmp/tweet_video" + getFileExtension(videoUrl));

                        try (InputStream is = ConnectionUtils.openStreamWithTimeout(videoUrl)) {
                            FileUtils.copyToFile(is, videoFile);
                        } catch (IOException e) {
                            // don't worry about it!
                            log.warn("Could not download tweet video!", e);
                            videoFile.delete();
                        }
                    }
                    String finalVideoUrl = videoUrl;

                    // Try to determine if the urls in the tweet have embeds.
                    final List<String> linksInTweet = detectLinksInTweet(tweet);

                    // post it to all webhooks following #celeste_news_network
                    sendToCelesteNewsNetwork(webhookUrl -> postTweetToWebhook(webhookUrl, date, link, profilePictureUrl, username, embed, finalVideoUrl, linksInTweet));

                    if (videoFile != null) {
                        videoFile.delete();
                    }
                } else {
                    log.info("New tweet with id " + id + ", but this is a self-retweet of a tweet from less than a week before, or it is a reply tweet.");
                }
            }

            previousTweets.add(id);
        }

        // write the list of tweets that were already encountered
        try (BufferedWriter bw = new BufferedWriter(new FileWriter("previous_twitter_messages_celeste_game.txt"))) {
            for (String bl : previousTweets) {
                bw.write(bl + "\n");
            }
        }

        log.debug("Done.");
    }

    interface SendToWebhookHandler {
        void sendToWebhook(String webhookUrl) throws IOException;
    }

    /**
     * Calls the given method for all webhooks subscribed to #celeste_news_network,
     * and unsubscribes webhooks automatically if an UnknownWebhookException happens.
     */
    static void sendToCelesteNewsNetwork(SendToWebhookHandler handler) throws IOException {
        Path saveFile = Paths.get("/shared/celeste/celeste-news-network-subscribers.json");

        // load webhook URLs from Cloud Storage
        List<String> webhookUrls;
        try (InputStream is = Files.newInputStream(saveFile)) {
            webhookUrls = new JSONArray(IOUtils.toString(is, UTF_8)).toList()
                    .stream()
                    .map(Object::toString)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        // invoke webhooks
        List<String> goneWebhooks = new ArrayList<>();
        for (String webhook : webhookUrls) {
            try {
                handler.sendToWebhook(webhook);
            } catch (WebhookExecutor.UnknownWebhookException e) {
                // if this happens, this means the webhook was deleted.
                goneWebhooks.add(webhook);
            }
        }

        if (!goneWebhooks.isEmpty()) {
            // some webhooks were deleted! notify the owner about it.
            for (String goneWebhook : goneWebhooks) {
                WebhookExecutor.executeWebhook(SecretConstants.PERSONAL_NOTIFICATION_WEBHOOK_URL,
                        "https://cdn.discordapp.com/attachments/445236692136230943/945779742462865478/2021_Twitter_logo_-_blue.png",
                        "Twitter Bot",
                        ":warning: Auto-unsubscribed webhook because it does not exist: " + goneWebhook);

                webhookUrls.remove(goneWebhook);
            }

            // save the deletion to Cloud Storage.
            Files.writeString(saveFile, new JSONArray(webhookUrls).toString(), UTF_8);
        }
    }

    private static List<String> detectLinksInTweet(JSONObject tweet) {
        String id = tweet.getString("id_str");

        List<String> urls = new ArrayList<>();
        try {
            // if we're showing a retweet, show the urls of the retweet.
            JSONObject consideredTweet = tweet;
            if (consideredTweet.has("retweeted_status")) {
                consideredTweet = consideredTweet.getJSONObject("retweeted_status");
            }

            urls = consideredTweet.getJSONObject("entities").getJSONArray("urls").toList()
                    .stream()
                    .filter(s -> !((Map<String, String>) s).get("expanded_url").contains(id))
                    .filter(s -> hasEmbed(((Map<String, String>) s).get("url")))
                    .map(s -> ((Map<String, String>) s).get("url"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error while trying to get urls from tweet", e);
            // it isn't a big deal though. we should still post the tweet.
        }
        return urls;
    }

    /**
     * Posts the tweet, the video and the links in 1 to 3 messages to the given webhook.
     */
    private static void postTweetToWebhook(String webhook, long date, String tweetLink, String profilePictureUrl, String username,
                                           Map<String, Object> embed, String videoUrl, List<String> linksInTweet) throws IOException {

        // post the tweet link and its embed
        WebhookExecutor.executeWebhook(webhook, profilePictureUrl, username, "<" + tweetLink + ">" + "\n_Posted on <t:" + date + ":F>_",
                Collections.singletonList(embed));

        if (videoUrl != null) {
            boolean videoSent = false;
            File video = new File("/tmp/tweet_video" + getFileExtension(videoUrl));
            if (video.exists() && video.length() <= 25 * 1024 * 1024) {
                // post the video as a file, to avoid having to post a long link
                try {
                    WebhookExecutor.executeWebhook(webhook, profilePictureUrl, username, ":arrow_up: Video:", false, Collections.singletonList(video));
                    videoSent = true;
                } catch (IOException e) {
                    log.error("Could not send Twitter video as an attachment to the webhook!", e);
                }
            }

            if (!videoSent) {
                // video is too big, does not exist or failed to send for some reason, so post the link instead
                WebhookExecutor.executeWebhook(webhook, profilePictureUrl, username, ":arrow_up: Video: " + videoUrl);
            }
        }

        if (!linksInTweet.isEmpty()) {
            // post all embeddable links after that
            WebhookExecutor.executeWebhook(webhook, profilePictureUrl, username, ":arrow_up: Links: " + String.join(", ", linksInTweet));
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
     * This makes Discord-like looking embeds of tweets in the place of Discord,
     * because Discord apparently suddenly stopped taking care of that.
     *
     * @param tweet The tweet to turn into an embed
     * @return The embed data
     */
    static Map<String, Object> generateEmbedFor(JSONObject tweet) {
        Map<String, Object> embed = new HashMap<>();

        // if that's an RT, we want to take the retweeted status instead.
        if (tweet.has("retweeted_status")) {
            tweet = tweet.getJSONObject("retweeted_status");
        }

        { // author
            Map<String, String> authorInfo = new HashMap<>();
            authorInfo.put("name", tweet.getJSONObject("user").getString("name") + " (@" + tweet.getJSONObject("user").getString("screen_name") + ")");
            authorInfo.put("icon_url", tweet.getJSONObject("user").getString("profile_image_url_https").replace("_normal", ""));
            authorInfo.put("url", "https://twitter.com/" + tweet.getJSONObject("user").getString("screen_name"));
            embed.put("author", authorInfo);
        }

        int videoCount = 0;
        int photoCount = 0;
        boolean embeddedMedia = false;

        // media
        if (tweet.has("extended_entities") && tweet.getJSONObject("extended_entities").has("media")) {
            for (Object o : tweet.getJSONObject("extended_entities").getJSONArray("media")) {
                JSONObject media = (JSONObject) o;
                switch (media.getString("type")) {
                    case "photo" -> {
                        photoCount++;
                        if (!embeddedMedia) {
                            embed.put("image", ImmutableMap.of("url", media.getString("media_url_https")));
                            embeddedMedia = true;
                        }
                    }
                    case "video", "animated_gif" -> {
                        videoCount++;
                        if (!embeddedMedia) {
                            String videoUrl = getVideoUrlWithYoutubeDL("https://twitter.com/"
                                    + tweet.getJSONObject("user").getString("screen_name")
                                    + "/status/"
                                    + tweet.getString("id_str"));

                            if (videoUrl != null) {
                                embed.put("video", ImmutableMap.of("url", videoUrl));
                                embeddedMedia = true;
                            }
                        }
                    }
                }
            }
        }

        // Tweet contents are encoded with HTML entities (&amp; &lt; and &gt; in particular it seems),
        // apparently to protect people that stick tweet contents on their website without escaping HTML against XSS attacks.
        // Since we are communicating with JSON APIs only we need to get rid of those HTML entities :a:
        String textContent = StringEscapeUtils.unescapeHtml4(tweet.getString("full_text"));

        // and we want to remove the link to the tweet itself at the end of the text if there is any.
        Matcher linkAtEndMatch = twitterLinkAtEnd.matcher(textContent);
        if (linkAtEndMatch.matches()) {
            String link = linkAtEndMatch.group(1);

            try {
                HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(link);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)");

                if (connection.getResponseCode() == 301
                        && connection.getHeaderField("Location") != null
                        && connection.getHeaderField("Location").startsWith("https://twitter.com/")
                        && connection.getHeaderField("Location").endsWith("/status/" + tweet.getString("id_str"))) {

                    textContent = textContent.substring(0, textContent.length() - link.length()).trim();
                }
            } catch (IOException e) {
                log.warn("Could not determine where {} leads to", link, e);
            }
        }

        embed.put("description", textContent);

        embed.put("color", 1940464);

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
            footerInfo.put("icon_url", "https://cdn.discordapp.com/attachments/445236692136230943/945779742462865478/2021_Twitter_logo_-_blue.png");
            embed.put("footer", footerInfo);
            embed.put("timestamp", OffsetDateTime.parse(tweet.getString("created_at"), DateTimeFormatter.ofPattern("E MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH)).format(DateTimeFormatter.ISO_DATE_TIME));
        }

        return embed;
    }

    private static String getVideoUrlWithYoutubeDL(String tweetUrl) {
        try {
            // ./youtube-dl (actually yt-dlp) is downloaded daily from: https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp
            log.debug("Running ./youtube-dl --dump-single-json {} to get video URL for the tweet...", tweetUrl);
            Process youtubeDl = new ProcessBuilder("/app/static/youtube-dl", "--dump-single-json", tweetUrl).start();
            try (InputStream is = youtubeDl.getInputStream()) {
                JSONObject output = new JSONObject(IOUtils.toString(is, UTF_8));

                String bestUrl = output.getString("url");
                long filesize = -1;

                for (Object format : output.getJSONArray("formats")) {
                    String url = ((JSONObject) format).getString("url");

                    // skip m3u8 links that are not actually videos
                    if (!url.contains(".mp4")) continue;

                    try {
                        // query the header of each video to get the file size
                        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(url);
                        connection.setRequestMethod("HEAD");

                        if (connection.getHeaderField("Content-Length") != null) {
                            long contentLength = Long.parseLong(connection.getHeaderField("Content-Length"));
                            log.debug("File size for video at {}: {}", url, contentLength);

                            // the "best" video is the biggest one that still fits within the 25 MB size limit.
                            if (contentLength > filesize && contentLength <= 25 * 1024 * 1024) {
                                bestUrl = url;
                                filesize = contentLength;
                            }
                        }
                    } catch (IOException | NumberFormatException e) {
                        // skip the format
                        log.warn("Could not get video size for URL {}", url, e);
                    }
                }

                log.debug("Best video format is {} with size {}", bestUrl, filesize);
                return bestUrl;
            }
        } catch (IOException | JSONException e) {
            // this is an error, but this should NOT prevent from posting the tweet.
            log.error("youtube-dl gave an unexpected response!", e);
            return null;
        }
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
