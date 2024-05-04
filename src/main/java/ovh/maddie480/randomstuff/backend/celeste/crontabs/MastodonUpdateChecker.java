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

public class MastodonUpdateChecker {
    private static final Logger log = LoggerFactory.getLogger(MastodonUpdateChecker.class);

    private static final List<String> ACCOUNTS_TO_FORWARD_TO_CELESTE_NEWS_NETWORK = Collections.singletonList(
            "https://mastodon.exok.com/api/v1/accounts/109400546900802656/statuses"
    );

    private static final Map<String, Set<String>> previousStatuses = new HashMap<>();

    /**
     * Loads all previous Mastodon IDs from disk.
     * Ran on bot startup.
     */
    public static void loadFile() {
        String[] files = new File(".").list(
                (dir, name) -> name.startsWith("previous_mastodon_statuses_") && name.endsWith(".txt"));

        for (String file : files) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String mastodonStatusUrl = file.substring("previous_mastodon_statuses_".length(), file.length() - ".txt".length());
                mastodonStatusUrl = URLDecoder.decode(mastodonStatusUrl, UTF_8);
                Set<String> previous = new HashSet<>();

                String s;
                while ((s = br.readLine()) != null) {
                    previous.add(s);
                }

                previousStatuses.put(mastodonStatusUrl, previous);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Checks for new statuses and notifies about any updates.
     * Run every 15 minutes.
     *
     * @throws IOException In case of issues when fetching statuses or notifying about them
     */
    public static void checkForUpdates() throws IOException {
        try (Stream<String> linkList = Files.lines(Paths.get("followed_mastodon_accounts.txt"))) {
            for (String link : linkList.toList()) {
                checkForUpdates(link);
            }
        }
    }

    /**
     * Checks for updates on a specific Mastodon feed.
     *
     * @param feed The feed to check
     * @throws IOException In case of issues when fetching statuses or notifying about them
     */
    private static void checkForUpdates(String feed) throws IOException {
        log.debug("Checking for updates on feed " + feed);

        boolean firstRun = !previousStatuses.containsKey(feed);
        Set<String> statusesAlreadyNotified = previousStatuses.getOrDefault(feed, new HashSet<>());

        JSONArray answer = ConnectionUtils.runWithRetry(() -> {
            try (InputStream is = ConnectionUtils.openStreamWithTimeout(feed + "?exclude_replies=true")) {
                return new JSONArray(new JSONTokener(is));
            }
        });

        for (int i = answer.length() - 1; i >= 0; i--) {
            JSONObject status = answer.getJSONObject(i);
            String id = status.getString("id");

            if (!statusesAlreadyNotified.contains(id)) {
                boolean recentSelfReblog = false;
                if (!status.isNull("reblog") && status.getJSONObject("account").getString("id").equals(status.getJSONObject("reblog").getJSONObject("account").getString("id"))) {
                    // account reblogged itself! do not repost the status if the original status was less than a week before.

                    long reblogDate = OffsetDateTime.parse(
                                    status.getString("created_at"),
                                    DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                            .toEpochSecond();

                    long originalStatusDate = OffsetDateTime.parse(
                                    status.getJSONObject("reblog").getString("created_at"),
                                    DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                            .toEpochSecond();

                    // 604800 seconds = 7 days
                    recentSelfReblog = (reblogDate - originalStatusDate) < 604800;
                }

                if (!firstRun && !recentSelfReblog) {
                    log.info("New status with id " + id);

                    String username = status.getJSONObject("account").getString("display_name");
                    if (username.endsWith(" :verified:")) {
                        username = username.substring(0, username.length() - 11);
                        status.getJSONObject("account").put("display_name", username);
                    }

                    // Get all the info we need about the status
                    String link = status.getString("url");
                    if (!status.isNull("reblog")) {
                        link = status.getJSONObject("reblog").getString("url");
                    }
                    long date = OffsetDateTime.parse(status.getString("created_at"), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toEpochSecond();
                    String profilePictureUrl = status.getJSONObject("account").getString("avatar_static");
                    Map<String, Object> embed = generateEmbedFor(status);

                    // Discord doesn't support embedding videos in their embeds... come on!
                    String videoUrl = null;
                    File videoFile = null;
                    if (embed.containsKey("video")) {
                        videoUrl = ((Map<String, String>) embed.get("video")).get("url");
                        videoFile = new File("/tmp/status_video" + getFileExtension(videoUrl));

                        try (InputStream is = ConnectionUtils.openStreamWithTimeout(videoUrl)) {
                            FileUtils.copyToFile(is, videoFile);
                        } catch (IOException e) {
                            // don't worry about it!
                            log.warn("Could not download status video!", e);
                            videoFile.delete();
                        }
                    }

                    // Try to determine if the urls in the status have embeds.
                    final List<String> linksInStatus = detectLinksInStatus(status);

                    // Those 3 aren't effectively final, so make them final, then build the action to post the status
                    final String finalLink = link;
                    final String finalUsername = username;
                    final String finalVideoUrl = videoUrl;
                    IOConsumer<String> postAction = webhook -> postStatusToWebhook(webhook, date, finalLink, profilePictureUrl, finalUsername, embed, finalVideoUrl, linksInStatus);

                    if (ACCOUNTS_TO_FORWARD_TO_CELESTE_NEWS_NETWORK.contains(feed)) {
                        // post it to #celeste_news_network
                        sendToCelesteNewsNetwork(postAction);
                    } else {
                        // post it to the personal notifications channel
                        postAction.accept(SecretConstants.PERSONAL_NOTIFICATION_WEBHOOK_URL);
                    }

                    if (videoFile != null) {
                        videoFile.delete();
                    }

                    statusesAlreadyNotified.add(id);
                } else {
                    log.info("New status with id " + id + ", but this is a self-reblog of a status from less than a week before, or this is the first run.");
                    statusesAlreadyNotified.add(id);
                }
            }
        }

        previousStatuses.put(feed, statusesAlreadyNotified);

        // write the list of statuses that were already encountered
        try (BufferedWriter bw = new BufferedWriter(new FileWriter("previous_mastodon_statuses_" + URLEncoder.encode(feed, UTF_8) + ".txt"))) {
            for (String bl : statusesAlreadyNotified) {
                bw.write(bl + "\n");
            }
        }

        log.debug("Done.");
    }

    /**
     * Calls the given method for all webhooks subscribed to #celeste_news_network,
     * and unsubscribes webhooks automatically if an UnknownWebhookException happens.
     */
    static void sendToCelesteNewsNetwork(IOConsumer<String> handler) throws IOException {
        Path saveFile = Paths.get("/shared/celeste/celeste-news-network-subscribers.json");

        // load webhook URLs from Cloud Storage
        List<String> webhookUrls;
        try (InputStream is = Files.newInputStream(saveFile)) {
            webhookUrls = new JSONArray(new JSONTokener(is)).toList()
                    .stream()
                    .map(Object::toString)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        // invoke webhooks
        List<String> goneWebhooks = new ArrayList<>();
        for (String webhook : webhookUrls) {
            try {
                handler.accept(webhook);
            } catch (WebhookExecutor.UnknownWebhookException e) {
                // if this happens, this means the webhook was deleted.
                goneWebhooks.add(webhook);
            }
        }

        if (!goneWebhooks.isEmpty()) {
            // some webhooks were deleted! notify the owner about it.
            for (String goneWebhook : goneWebhooks) {
                WebhookExecutor.executeWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK,
                        "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/mastodon.png",
                        "Mastodon Bot",
                        ":warning: Auto-unsubscribed webhook because it does not exist: " + goneWebhook);

                webhookUrls.remove(goneWebhook);
            }

            // save the deletion to Cloud Storage.
            Files.writeString(saveFile, new JSONArray(webhookUrls).toString(), UTF_8);
        }
    }


    private static List<String> detectLinksInStatus(JSONObject status) {
        // if we're showing a reblog, show the urls of the reblog.
        JSONObject consideredStatus = status;
        if (!consideredStatus.isNull("reblog")) {
            consideredStatus = consideredStatus.getJSONObject("reblog");
        }

        String content = consideredStatus.getString("content");
        return Jsoup.parse(content)
                .select("a:not(.mention):not(.hashtag)")
                .stream()
                .map(element -> element.attr("href"))
                .filter(MastodonUpdateChecker::hasEmbed)
                .collect(Collectors.toList());
    }

    /**
     * Posts the status, the video and the links in 1 to 3 messages to the given webhook.
     */
    private static void postStatusToWebhook(String webhook, long date, String statusLink, String profilePictureUrl, String username,
                                            Map<String, Object> embed, String videoUrl, List<String> linksInStatus) throws IOException {

        // post the status link and its embed
        WebhookExecutor.executeWebhook(webhook, profilePictureUrl, username, "<" + statusLink + ">" + "\n_Posted on <t:" + date + ":F>_",
                Collections.singletonList(embed));

        if (videoUrl != null) {
            boolean videoSent = false;
            File video = new File("/tmp/status_video" + getFileExtension(videoUrl));
            if (video.exists() && video.length() <= 25 * 1024 * 1024) {
                // post the video as a file, to avoid having to post a long link
                try {
                    WebhookExecutor.executeWebhook(webhook, profilePictureUrl, username, ":arrow_up: Video:", false, Collections.singletonList(video));
                    videoSent = true;
                } catch (IOException e) {
                    log.error("Could not send Mastodon video as an attachment to the webhook!", e);
                }
            }

            if (!videoSent) {
                // video is too big, does not exist or failed to send for some reason, so post the link instead
                WebhookExecutor.executeWebhook(webhook, profilePictureUrl, username, ":arrow_up: Video: " + videoUrl);
            }
        }

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
    static Map<String, Object> generateEmbedFor(JSONObject status) {
        Map<String, Object> embed = new HashMap<>();

        // if that's an RT, we want to take the reblogged status instead.
        if (!status.isNull("reblog")) {
            status = status.getJSONObject("reblog");
        }

        { // author
            Map<String, String> authorInfo = new HashMap<>();
            authorInfo.put("name", status.getJSONObject("account").getString("display_name") + " (@" + status.getJSONObject("account").getString("acct") + ")");
            authorInfo.put("icon_url", status.getJSONObject("account").getString("avatar"));
            authorInfo.put("url", status.getJSONObject("account").getString("url"));
            embed.put("author", authorInfo);
        }

        int videoCount = 0;
        int photoCount = 0;
        boolean embeddedMedia = false;

        // media
        if (status.has("media_attachments")) {
            for (Object o : status.getJSONArray("media_attachments")) {
                JSONObject media = (JSONObject) o;
                switch (media.getString("type")) {
                    case "image" -> {
                        photoCount++;
                        if (!embeddedMedia) {
                            embed.put("image", ImmutableMap.of("url", media.getString("url")));
                            embeddedMedia = true;
                        }
                    }
                    case "video", "gifv" -> {
                        videoCount++;
                        if (!embeddedMedia) {
                            embed.put("video", ImmutableMap.of("url", media.getString("url")));
                            embeddedMedia = true;
                        }
                    }
                }
            }
        }

        String textContent = Jsoup.parse(status.getString("content")).text();

        embed.put("description", textContent);

        embed.put("color", 1645346);

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
            footerInfo.put("text", "Mastodon" + (footerText.isEmpty() ? "" : " • " + footerText));
            footerInfo.put("icon_url", "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/mastodon.png");
            embed.put("footer", footerInfo);
            embed.put("timestamp", OffsetDateTime.parse(status.getString("created_at"), DateTimeFormatter.ISO_OFFSET_DATE_TIME).format(DateTimeFormatter.ISO_DATE_TIME));
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
