package com.max480.discord.randombots;

import com.max480.quest.modmanagerbot.BotClient;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

public class TwitterUpdateChecker {
    private static final Logger log = LoggerFactory.getLogger(TwitterUpdateChecker.class);

    // those will be sent to #celeste_news_network.
    private static final List<String> THREADS_TO_WEBHOOK = Arrays.asList("celeste_game", "EverestAPI");

    // those will be sent to the Quest server. the list of subscribers is managed externally by a bot.
    private static final List<String> THREADS_TO_QUEST = Collections.singletonList("JeuDeLaupok");
    public static Set<String> patchNoteSubscribers = new HashSet<>();

    private static final Map<String, Set<String>> previousTweets = new HashMap<>();

    public static String serviceMessage = null;

    public static void runCheckForUpdates() throws Exception {
        try {
            checkForUpdates();
            serviceMessage = null;
        } catch (Exception e) {
            log.error("Error while checking new tweets", e);
            serviceMessage = "⚠ Could not reach Twitter";
            throw e;
        }
    }

    /**
     * Loads all previous tweet IDs from disk.
     * Ran on bot startup.
     */
    public static void loadFile() {
        String[] files = new File(".").list(
                (dir, name) -> name.startsWith("previous_twitter_messages_") && name.endsWith(".txt"));

        for (String file : files) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String twitterFeedName = file.substring("previous_twitter_messages_".length(), file.length() - ".txt".length());
                Set<String> previous = new HashSet<>();

                String s;
                while ((s = br.readLine()) != null) {
                    previous.add(s);
                }

                previousTweets.put(twitterFeedName, previous);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Checks for new tweets and notifies about any updates.
     * Ran every 15 minutes.
     *
     * @throws IOException In case of issues when fetching tweets or notifying about them
     */
    private static void checkForUpdates() throws IOException {
        String token = authenticateTwitter();

        // all subscribed feeds are listed in a text file.
        try (BufferedReader br = new BufferedReader(new FileReader("followed_twitter_feeds.txt"))) {
            String s;
            while ((s = br.readLine()) != null) {
                checkForUpdates(token, s);
            }
        }
    }

    /**
     * Authenticates with Twitter using client credentials.
     *
     * @return The access token
     * @throws IOException In case an error occurs when connecting
     */
    private static String authenticateTwitter() throws IOException {
        HttpURLConnection connAuth = (HttpURLConnection) new URL("https://api.twitter.com/oauth2/token").openConnection();

        connAuth.setConnectTimeout(10000);
        connAuth.setReadTimeout(30000);
        connAuth.setRequestProperty("Authorization", "Basic " + SecretConstants.TWITTER_BASIC_AUTH);
        connAuth.setRequestMethod("POST");
        connAuth.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        connAuth.setDoInput(true);
        connAuth.setDoOutput(true);

        connAuth.getOutputStream().write("grant_type=client_credentials".getBytes());
        connAuth.getOutputStream().close();

        JSONObject answer = new JSONObject(IOUtils.toString(connAuth.getInputStream(), UTF_8));
        connAuth.getInputStream().close();

        if (connAuth.getResponseCode() != 200 || !(answer.getString("token_type")).equals("bearer")) {
            throw new IOException("Could not authenticate to Twitter");
        }

        return answer.getString("access_token");
    }

    /**
     * Checks for updates on a specific Twitter feed.
     *
     * @param token The access token
     * @param feed  The feed to check
     * @throws IOException In case of issues when fetching tweets or notifying about them
     */
    private static void checkForUpdates(String token, String feed) throws IOException {
        log.debug("Checking for updates on feed " + feed);

        boolean firstRun = !previousTweets.containsKey(feed);
        Set<String> tweetsAlreadyNotified = previousTweets.getOrDefault(feed, new HashSet<>());

        HttpURLConnection connAuth = (HttpURLConnection) new URL("https://api.twitter.com/1.1/statuses/user_timeline.json?screen_name="
                + URLEncoder.encode(feed, "UTF-8") + "&count=50&include_rts=1&exclude_replies=1").openConnection();

        connAuth.setConnectTimeout(10000);
        connAuth.setReadTimeout(30000);
        connAuth.setRequestProperty("Authorization", "Bearer " + token);
        connAuth.setRequestMethod("GET");
        connAuth.setDoInput(true);

        JSONArray answer = new JSONArray(IOUtils.toString(connAuth.getInputStream(), UTF_8));

        for (int i = answer.length() - 1; i >= 0; i--) {
            JSONObject tweet = answer.getJSONObject(i);
            String id = tweet.getString("id_str");
            long date = OffsetDateTime.parse(tweet.getString("created_at"), DateTimeFormatter.ofPattern("E MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH)).toEpochSecond();

            if (!tweetsAlreadyNotified.contains(id)) {
                if (!firstRun) {
                    log.info("New tweet with id " + id);
                    String link = "https://twitter.com/" + feed + "/status/" + id;

                    List<String> urls = new ArrayList<>();
                    try {
                        urls = tweet.getJSONObject("entities").getJSONArray("urls").toList()
                                .stream()
                                .filter(s -> !((Map<String, String>) s).get("expanded_url").contains(id))
                                .filter(s -> hasEmbed(((Map<String, String>) s).get("expanded_url")))
                                .map(s -> ((Map<String, String>) s).get("url"))
                                .collect(Collectors.toList());
                    } catch (Exception e) {
                        log.error("Error while trying to get urls from tweet", e);
                        // it isn't a big deal though. we should still post the tweet.
                    }
                    final List<String> urlsFinalList = urls;

                    // post it to the Twitter update channel
                    BotClient.getInstance().getTextChannelById(SecretConstants.TWITTER_UPDATE_CHANNEL)
                            .sendMessage("Nouveau tweet de @" + feed + "\n" +
                                    ":arrow_right: " + link + (urls.isEmpty() ? "" : "\nLiens : " + String.join(", ", urls)))
                            .queue();

                    if (THREADS_TO_WEBHOOK.contains(feed)) {
                        // load webhook URLs from Cloud Storage
                        List<String> webhookUrls;
                        try (InputStream is = CloudStorageUtils.getCloudStorageInputStream("celeste_news_network_subscribers.json")) {
                            webhookUrls = new JSONArray(IOUtils.toString(is, UTF_8)).toList()
                                    .stream()
                                    .map(Object::toString)
                                    .collect(Collectors.toCollection(ArrayList::new));
                        }

                        // invoke webhooks
                        List<String> goneWebhooks = new ArrayList<>();
                        for (String webhook : webhookUrls) {
                            try {
                                // call the webhook with retries
                                ConnectionUtils.runWithRetry(() -> {
                                    try {
                                        WebhookExecutor.executeWebhook(webhook,
                                                tweet.getJSONObject("user").getString("profile_image_url_https").replace("_normal", ""),
                                                tweet.getJSONObject("user").getString("name"), link + "\n_Posted on <t:" + date + ":F>_"
                                                        + (urlsFinalList.isEmpty() ? "" : "\nLinks: " + String.join(", ", urlsFinalList)));

                                        return null;
                                    } catch (InterruptedException e) {
                                        // this will probably never happen. ^^'
                                        throw new IOException(e);
                                    }
                                });

                            } catch (WebhookExecutor.UnknownWebhookException e) {
                                // if this happens, this means the webhook was deleted.
                                goneWebhooks.add(webhook);
                            }
                        }

                        if (!goneWebhooks.isEmpty()) {
                            // some webhooks were deleted! notify the owner about it.
                            for (String goneWebhook : goneWebhooks) {
                                BotClient.getInstance().getTextChannelById(SecretConstants.TWITTER_UPDATE_CHANNEL)
                                        .sendMessage(":warning: Auto-unsubscribed webhook because it does not exist: " + goneWebhook)
                                        .queue();

                                webhookUrls.remove(goneWebhook);
                            }

                            // save the deletion to Cloud Storage.
                            CloudStorageUtils.sendStringToCloudStorage(new JSONArray(webhookUrls).toString(), "celeste_news_network_subscribers.json", "application/json");
                        }
                    }

                    if (THREADS_TO_QUEST.contains(feed)) {
                        // post it to the Quest server, pinging every subscriber in the process
                        BotClient.getInstance().getTextChannelById(SecretConstants.QUEST_UPDATE_CHANNEL)
                                .sendMessage("<@" + String.join("> <@", patchNoteSubscribers) + ">\n" +
                                        "Nouveau tweet de @" + feed + "\n" +
                                        ":arrow_right: " + link + (urls.isEmpty() ? "" : "\nLiens : " + String.join(", ", urls)))
                                .queue();
                    }
                } else {
                    log.info("New tweet with id " + id + ", but this is the first run.");
                }
            }
            tweetsAlreadyNotified.add(id);
        }

        previousTweets.put(feed, tweetsAlreadyNotified);

        // write the list of tweets that were already encountered
        try (BufferedWriter bw = new BufferedWriter(new FileWriter("previous_twitter_messages_" + feed + ".txt"))) {
            for (String bl : tweetsAlreadyNotified) {
                bw.write(bl + "\n");
            }
        }

        log.debug("Done.");
    }

    private static boolean hasEmbed(String url) {
        try {
            log.debug("Sending request to {} to check if it has an embed...", url);

            return Jsoup
                    .connect(url)
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
