package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.function.IOConsumer;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.OutputStreamLogger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TwitterUpdateChecker {
    private static final Logger log = LoggerFactory.getLogger(TwitterUpdateChecker.class);

    // thanks to PrivacyDev for this!
    private static final String RSS_URL = "https://nitter.privacydev.net/celeste_game/rss";

    private static final List<String> previousStatuses = new ArrayList<>();

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

    public static void checkForUpdates() throws IOException {
        log.debug("Checking for updates on Twitter");

        Document answer = ConnectionUtils.jsoupGetWithRetry(RSS_URL);
        Elements tweetList = answer.select("channel item");

        for (int i = tweetList.size() - 1; i >= 0; i--) {
            Element tweet = tweetList.get(i);
            String url = tweet.select("link").text();

            if (!previousStatuses.contains(url)) {
                log.info("New status with url {}", url);

                String username = answer.select("channel title").text();
                username = username.substring(0, username.indexOf(" / "));

                // Get all the info we need about the status
                String link = url.replace("https://nitter.privacydev.net/", "https://twitter.com/").replace("#m", "");
                String profilePictureUrl = answer.select("channel image url").text();
                profilePictureUrl = profilePictureUrl.replace("https://nitter.privacydev.net/pic/", "https://");
                profilePictureUrl = URLDecoder.decode(profilePictureUrl, StandardCharsets.UTF_8);
                Map<String, Object> embed = generateEmbedFor(tweet, link);

                // Try to determine if the urls in the status have embeds.
                final List<String> linksInStatus = detectLinksInStatus(tweet);

                // Discord doesn't support embedding videos in their embeds... come on!
                String videoUrl = null;
                File videoFile = null;
                if (embed.containsKey("video")) {
                    videoUrl = ((Map<String, String>) embed.get("video")).get("url");
                    videoFile = new File("/tmp/status_video" + MastodonUpdateChecker.getFileExtension(videoUrl));

                    try (InputStream is = ConnectionUtils.openStreamWithTimeout(videoUrl)) {
                        FileUtils.copyToFile(is, videoFile);
                    } catch (IOException e) {
                        // don't worry about it!
                        log.warn("Could not download status video!", e);
                        videoFile.delete();
                    }
                }

                // Those 3 aren't effectively final, so make them final, then build the action to post the status
                final String finalLink = link;
                final String finalProfilePictureUrl = profilePictureUrl;
                final String finalUsername = username;
                final String finalVideoUrl = videoUrl;
                IOConsumer<String> postAction = webhook -> MastodonUpdateChecker.postStatusToWebhook(webhook, 0, finalLink, finalProfilePictureUrl, finalUsername, embed, finalVideoUrl, linksInStatus);

                // post it to #celeste_news_network
                // MastodonUpdateChecker.sendToCelesteNewsNetwork(postAction);
                postAction.accept(SecretConstants.PERSONAL_NOTIFICATION_WEBHOOK_URL);

                previousStatuses.add(url);
                while (previousStatuses.size() > 100) {
                    log.debug("Forgetting oldest status {}", previousStatuses.removeFirst());
                }

                // write the list of statuses that were already encountered
                try (BufferedWriter bw = new BufferedWriter(new FileWriter("previous_twitter_statuses.txt"))) {
                    for (String bl : previousStatuses) {
                        bw.write(bl + "\n");
                    }
                }

                if (videoFile != null) {
                    Files.delete(videoFile.toPath());
                }
            }
        }

        log.debug("Done.");
    }


    private static List<String> detectLinksInStatus(Element tweet) {
        return Jsoup.parse(tweet.select("description").text())
                .select("a")
                .stream()
                .map(element -> element.attr("href"))
                .filter(href -> !href.startsWith("https://nitter.privacydev.net/"))
                .filter(MastodonUpdateChecker::hasEmbed)
                .collect(Collectors.toList());
    }

    /**
     * This makes Discord-like looking embeds of statuses in the place of Discord,
     * because Discord apparently suddenly stopped taking care of that.
     *
     * @param tweet The status to turn into an embed
     * @return The embed data
     */
    static Map<String, Object> generateEmbedFor(Element tweet, String url) throws IOException {
        Map<String, Object> embed = new HashMap<>();

        { // author
            Map<String, String> authorInfo = new HashMap<>();
            String username = tweet.select("dc\\:creator").text();

            Document answer = ConnectionUtils.jsoupGetWithRetry(RSS_URL.replace("celeste_game", username.substring(1)));
            String profilePictureUrl = answer.select("channel image url").text();
            profilePictureUrl = profilePictureUrl.replace("https://nitter.privacydev.net/pic/", "https://");
            profilePictureUrl = URLDecoder.decode(profilePictureUrl, StandardCharsets.UTF_8);
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

        // the description (tweet content) is HTML in CDATA so it's interpreted as text rather than as XML, wooo!
        Document description = Jsoup.parse(tweet.select("description").text());

        // media
        for (Element image : description.select("img")) {
            String imageUrl = image.attr("src")
                    .replace("https://nitter.privacydev.net/pic/", "https://pbs.twimg.com/");
            imageUrl = URLDecoder.decode(imageUrl, StandardCharsets.UTF_8);
            String videoUrl = null;

            if (imageUrl.contains("video_thumb")) {
                videoCount++;
                videoUrl = getVideoUrlWithYoutubeDL(url);
            } else {
                photoCount++;
            }

            if (!embeddedMedia) {
                if (videoUrl != null) {
                    embed.put("video", ImmutableMap.of("url", videoUrl));
                } else {
                    embed.put("image", ImmutableMap.of("url", imageUrl));
                }
            }

            embeddedMedia = true;
        }

        String textContent = description.text()
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

    private static String getVideoUrlWithYoutubeDL(String tweetUrl) {
        try {
            // ./youtube-dl (actually yt-dlp) is downloaded at build time from: https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp
            log.debug("Running ./youtube-dl --dump-single-json {} to get video URL for the tweet...", tweetUrl);
            Process youtubeDl = OutputStreamLogger.redirectErrorOutput(log,
                    new ProcessBuilder("/app/static/youtube-dl", "--dump-single-json", tweetUrl).start());

            try (InputStream is = youtubeDl.getInputStream()) {
                JSONObject output = new JSONObject(new JSONTokener(is));

                String bestUrl = null;
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
}
