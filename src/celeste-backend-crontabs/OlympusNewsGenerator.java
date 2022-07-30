package com.max480.discord.randombots;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This class generates the Olympus news feed based on the EverestAPI Twitter feed
 * provided by the Twitter Update Checker.
 */
public class OlympusNewsGenerator {
    private static final Logger log = LoggerFactory.getLogger(OlympusNewsGenerator.class);

    public static void generateFeed(JSONArray tweetFeed) throws IOException {
        List<JSONObject> output = new ArrayList<>();

        for (int i = 0; i < tweetFeed.length() && output.size() < 10; i++) {
            JSONObject tweet = tweetFeed.getJSONObject(i);

            // do not include RTs
            if (tweet.has("retweeted_status")) {
                continue;
            }

            // we are going to reuse the Twitter update checker to process the tweet into a more convenient form.
            final Map<String, Object> embed = TwitterUpdateChecker.generateEmbedFor(tweet);
            final List<String> links = TwitterUpdateChecker.detectLinksInTweet(tweet, false);

            JSONObject mappedTweet = new JSONObject();

            // use the tweet contents as the preview text for the news, removing all non-ascii characters
            mappedTweet.put("preview", embed.get("description").toString()
                    .replaceAll("[^\\x00-\\xFF]", "").trim());

            if (embed.containsKey("image")) {
                // use the first image of the tweet as an image for the news
                mappedTweet.put("image", ((Map<String, String>) embed.get("image")).get("url"));
            } else {
                // use the embed image of the first link in the tweet that has one as an image for the news
                for (String link : links) {
                    String embeddedImage = getEmbedAttribute(link, "image");
                    if (embeddedImage != null) {
                        mappedTweet.put("image", embeddedImage);
                        break;
                    }
                }
            }

            if (!links.isEmpty()) {
                // use the target of the first t.co link of the tweet as a link for the news
                mappedTweet.put("link", getLinkTarget(links.get(0)));

                // and remove it from the preview text as well
                mappedTweet.put("preview", mappedTweet.getString("preview").replace(links.get(0), "").trim());
            }

            // use the embed title as a title for the news
            for (String link : links) {
                String embedTitle = getEmbedAttribute(link, "title");
                if (embedTitle != null) {
                    mappedTweet.put("title", embedTitle.replace("[Celeste] [Mods]", "").trim());
                    break;
                }
            }

            output.add(mappedTweet);
        }

        // download the manual Olympus news entries...
        JSONArray manualNews;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout(new URL("https://raw.githubusercontent.com/max4805/RandomBackendStuff/main/olympusnews.json"))) {
            manualNews = new JSONArray(IOUtils.toString(is, StandardCharsets.UTF_8));
        }

        // ... and insert them in the list
        int topPosition = 0;
        for (int i = 0; i < manualNews.length(); i++) {
            JSONObject manualNewsEntry = manualNews.getJSONObject(i);

            String position = manualNewsEntry.getString("position");
            manualNewsEntry.remove("position");

            if (position.equals("top")) {
                output.add(topPosition, manualNewsEntry);
                topPosition++;
            } else {
                output.add(manualNewsEntry);
            }
        }

        // push to Cloud Storage
        CloudStorageUtils.sendStringToCloudStorage(new JSONArray(output).toString(), "olympus_news.json", "application/json");

        // update the frontend cache
        HttpURLConnection conn = (HttpURLConnection) new URL("https://max480-random-stuff.appspot.com/celeste/olympus-news-reload?key="
                + SecretConstants.RELOAD_SHARED_SECRET).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        if (conn.getResponseCode() != 200) {
            throw new IOException("Olympus News Reload API sent non 200 code: " + conn.getResponseCode());
        }

        WebhookExecutor.executeWebhook(SecretConstants.PERSONAL_TWITTER_WEBHOOK_URL,
                "https://cdn.discordapp.com/attachments/445236692136230943/878508600509726730/unknown.png",
                "Everest Update Checker",
                ":sparkles: Olympus news were updated.");
    }

    private static String getLinkTarget(String url) {
        log.debug("Sending request to {} to figure out the actual target...", url);

        try {
            return ConnectionUtils.runWithRetry(() -> {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(false);

                if (connection.getResponseCode() == 301 && connection.getHeaderField("Location") != null) {
                    return connection.getHeaderField("Location");
                } else {
                    throw new IOException(url + " is not a redirect!");
                }
            });
        } catch (IOException e) {
            log.warn("Could not determine where {} leads to", url, e);

            // as a fallback, just return the t.co URL
            return url;
        }
    }

    private static String getEmbedAttribute(String url, String attribute) {
        try {
            log.debug("Sending request to {} to check if it has an embed {}...", url, attribute);

            return ConnectionUtils.runWithRetry(() -> {
                String result = Jsoup
                        .connect(url)
                        .userAgent("Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)")
                        .followRedirects(true)
                        .timeout(10000)
                        .get()
                        .select("meta[property=\"og:" + attribute + "\"]")
                        .attr("content");

                if (result.isEmpty()) {
                    return null;
                }

                return result;
            });
        } catch (IOException e) {
            log.warn("Cannot access {} to check for embed image", url, e);
            return null;
        }
    }
}
