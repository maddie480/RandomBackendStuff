package com.max480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.CloudStorageUtils;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import com.max480.randomstuff.backend.utils.WebhookExecutor;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class converts the Olympus news that are published on GitHub to JSON, and posts notifications
 * to Discord webhooks whenever a new one comes out.
 */
public class OlympusNewsGenerator {
    private static final Logger log = LoggerFactory.getLogger(OlympusNewsGenerator.class);

    private static Set<String> alreadyNotified = new HashSet<>();
    private static String latestNewsHash = "[first check]";

    public static void main(String[] args) throws IOException {
        refreshOlympusNews();
    }

    public static void loadPreviouslyPostedNews() {
        try (Stream<String> lines = Files.lines(Paths.get("previous_olympus_news.txt"))) {
            alreadyNotified = lines.collect(Collectors.toCollection(HashSet::new));
        } catch (IOException e) {
            log.error("Could not load previously posted news!", e);
        }
    }

    public static void refreshOlympusNews() throws IOException {
        // this is pretty much a port of what Olympus itself does.
        log.debug("Refreshing Olympus news...");

        // list the Olympus news posts
        List<String> entries = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(ConnectionUtils.openStreamWithTimeout("https://everestapi.github.io/olympusnews/index.txt")))) {
            String s;
            while ((s = br.readLine()) != null) {
                if (s.endsWith(".md")) {
                    entries.add(s);
                }
            }
        }

        // sort them by descending order
        entries.sort(Comparator.<String>naturalOrder().reversed());

        JSONArray result = new JSONArray();

        for (String entryName : entries) {
            String data = ConnectionUtils.toStringWithTimeout("https://everestapi.github.io/olympusnews/" + entryName, StandardCharsets.UTF_8);

            // split between data, preview and full text
            String[] split = data.split("\n---\n", 3);
            data = split[0];
            String preview = split[1].trim();
            String text = split.length < 3 ? null : split[2].trim();

            // parse the data part
            Map<String, Object> dataParsed = new Yaml().load(data);

            // skip ignored news
            if ((boolean) dataParsed.get("ignore")) {
                log.debug("Skipped {} because ignored = true", entryName);
                continue;
            }
            dataParsed.remove("ignore");

            // relative image links should be turned into absolute ones
            if (dataParsed.containsKey("image") && dataParsed.get("image").toString().startsWith("./")) {
                dataParsed.put("image", "https://everestapi.github.io/olympusnews/" + dataParsed.get("image").toString().substring(2));
            }

            // bring in the preview and text we retrieved earlier
            dataParsed.put("preview", preview);
            dataParsed.put("text", text);

            // clean up the result by removing null values and empty strings
            for (Map.Entry<String, Object> entry : new HashSet<>(dataParsed.entrySet())) {
                if (entry.getValue() == null || (entry.getValue() instanceof String && entry.getValue().toString().isEmpty())) {
                    dataParsed.remove(entry.getKey());
                }
            }

            log.debug("Parsed {} -> {}", entryName, dataParsed);
            result.put(dataParsed);

            if (!alreadyNotified.contains(entryName)) {
                postToDiscord(dataParsed);
                alreadyNotified.add(entryName);
            }
        }

        // update it if anything changed in it!
        if (!DigestUtils.sha512Hex(result.toString()).equals(latestNewsHash)) {
            log.info("Olympus news changed! {} -> {}", latestNewsHash, DigestUtils.sha512Hex(result.toString()));

            // push to Cloud Storage
            CloudStorageUtils.sendStringToCloudStorage(result.toString(), "olympus_news.json", "application/json");

            // update the frontend cache
            HttpURLConnection conn = ConnectionUtils.openConnectionWithTimeout("https://max480-random-stuff.appspot.com/celeste/olympus-news-reload?key="
                    + SecretConstants.RELOAD_SHARED_SECRET);
            if (conn.getResponseCode() != 200) {
                throw new IOException("Olympus News Reload API sent non 200 code: " + conn.getResponseCode());
            }

            WebhookExecutor.executeWebhook(SecretConstants.PERSONAL_TWITTER_WEBHOOK_URL,
                    "https://cdn.discordapp.com/attachments/445236692136230943/878508600509726730/unknown.png",
                    "Everest Update Checker",
                    ":sparkles: Olympus news were updated.");

            UpdateOutgoingWebhooks.changesHappened();

            latestNewsHash = DigestUtils.sha512Hex(result.toString());
        }

        FileUtils.writeStringToFile(new File("previous_olympus_news.txt"), String.join("\n", alreadyNotified), StandardCharsets.UTF_8);
    }

    private static void postToDiscord(Map<String, Object> newsEntry) throws IOException {
        Map<String, Object> embed = new HashMap<>();

        // title
        if (newsEntry.containsKey("title")) {
            embed.put("title", newsEntry.get("title"));
        }

        // image
        if (newsEntry.containsKey("image")) {
            embed.put("image", ImmutableMap.of("url", newsEntry.get("image")));
        }

        // text content: use text if present, otherwise use preview
        if (newsEntry.containsKey("preview")) {
            embed.put("description", newsEntry.get("preview"));
        }
        if (newsEntry.containsKey("text")) {
            embed.put("description", newsEntry.get("text"));
        }

        if (newsEntry.containsKey("link")) {
            String fullDescription = embed.getOrDefault("description", "") + "\n[Open in browser](" + newsEntry.get("link") + ")";
            embed.put("description", fullDescription.trim());
        }

        embed.put("color", 3878218);

        WebhookExecutor.executeWebhook(SecretConstants.PERSONAL_TWITTER_WEBHOOK_URL,
                "https://avatars.githubusercontent.com/u/36135162",
                "Olympus News",
                "",
                Collections.singletonList(embed));

        TwitterUpdateChecker.sendToCelesteNewsNetwork(webhookUrl -> WebhookExecutor.executeWebhook(webhookUrl,
                "https://avatars.githubusercontent.com/u/36135162",
                "Olympus News",
                "",
                Collections.singletonList(embed)));
    }
}
