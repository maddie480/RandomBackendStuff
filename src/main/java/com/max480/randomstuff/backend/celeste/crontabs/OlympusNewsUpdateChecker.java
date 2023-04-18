package com.max480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import com.max480.everest.updatechecker.YamlUtil;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import com.max480.randomstuff.backend.utils.WebhookExecutor;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class posts notifications to #celeste_news_network Discord webhooks whenever a new
 * Olympus news entry comes out.
 */
public class OlympusNewsUpdateChecker {
    private static final Logger log = LoggerFactory.getLogger(OlympusNewsUpdateChecker.class);

    private static Set<String> alreadyNotified = new HashSet<>();

    public static void loadPreviouslyPostedNews() {
        try (Stream<String> lines = Files.lines(Paths.get("previous_olympus_news.txt"))) {
            alreadyNotified = lines.collect(Collectors.toCollection(HashSet::new));
        } catch (IOException e) {
            log.error("Could not load previously posted news!", e);
        }
    }

    public static void checkForUpdates() throws IOException {
        // this is pretty much a port of what Olympus itself does to parse the news.
        log.debug("Checking for Olympus News updates...");

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

        for (String entryName : entries) {
            String data = ConnectionUtils.toStringWithTimeout("https://everestapi.github.io/olympusnews/" + entryName, StandardCharsets.UTF_8);

            // split between data, preview and full text
            String[] split = data.split("\n---\n", 3);
            data = split[0];
            String preview = split[1].trim();
            String text = split.length < 3 ? null : split[2].trim();

            // parse the data part
            Map<String, Object> dataParsed;
            try (ByteArrayInputStream is = new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8))) {
                dataParsed = YamlUtil.load(is);
            }

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

            // if we didn't encounter that file name before, it's time to post about it!
            if (!alreadyNotified.contains(entryName)) {
                postToDiscord(dataParsed);
                alreadyNotified.add(entryName);
            }
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

        TwitterUpdateChecker.sendToCelesteNewsNetwork(webhookUrl -> WebhookExecutor.executeWebhook(webhookUrl,
                "https://avatars.githubusercontent.com/u/36135162",
                "Olympus News",
                "",
                Collections.singletonList(embed)));
    }
}
