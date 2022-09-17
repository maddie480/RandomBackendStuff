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
import java.util.List;
import java.util.Map;

/**
 * This class generates the Olympus news feed based on the EverestAPI Twitter feed
 * provided by the Twitter Update Checker.
 */
public class OlympusNewsGenerator {
    private static final Logger log = LoggerFactory.getLogger(OlympusNewsGenerator.class);

    public static void generateFeed(JSONArray tweetFeed) throws IOException {
        // download the manual Olympus news entries
        JSONObject manualNews;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout(new URL("https://raw.githubusercontent.com/max4805/RandomBackendStuff/main/olympusnews.json"))) {
            manualNews = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
        }
        final JSONArray manualAddBefore = manualNews.getJSONArray("add_before");
        final JSONArray manualAddAfter = manualNews.getJSONArray("add_after");
        final JSONObject manualReplace = manualNews.getJSONObject("replace");
        final JSONArray manualDelete = manualNews.getJSONArray("delete");

        JSONArray output = new JSONArray();

        // add tweets that have to be inserted before
        for (Object o : manualAddBefore) {
            output.put(o);
        }

        for (int i = 0; i < tweetFeed.length() && output.length() < 10 + manualAddBefore.length(); i++) {
            JSONObject tweet = tweetFeed.getJSONObject(i);
            String tweetId = tweet.getString("id_str");

            // do not include RTs
            if (tweet.has("retweeted_status")) {
                continue;
            }

            // skip tweets that were marked as "delete" in manual news
            boolean isDeleted = false;
            for (int q = 0; q < manualDelete.length(); q++) {
                if (manualDelete.getString(q).equals(tweetId)) {
                    manualDelete.remove(q);
                    isDeleted = true;
                }
            }
            if (isDeleted) continue;

            // replace tweets that were marked as "replace"
            if (manualReplace.has(tweetId)) {
                output.put(manualReplace.getJSONObject(tweetId));
                manualReplace.remove(tweetId);
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

            output.put(mappedTweet);
        }

        // add tweets that have to be inserted after
        for (Object o : manualAddAfter) {
            output.put(o);
        }

        // push to Cloud Storage
        CloudStorageUtils.sendStringToCloudStorage(output.toString(), "olympus_news.json", "application/json");

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
                ":sparkles: Olympus news were updated.\n" +
                        "Unused replace entries: [" + String.join(", ", manualReplace.toMap().keySet()) + "]\n" +
                        "Unused delete entries: " + manualDelete.toString().replace("\"", "").replace(",", ", "));
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
