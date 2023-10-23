package com.max480.randomstuff.backend.discord.questcommunitybot.crontabs.hourly;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class ComicUpdateChecker {
    private static final Logger log = LoggerFactory.getLogger(ComicUpdateChecker.class);

    private final Map<String, Set<String>> alreadyRetrievedArticles = new HashMap<>();

    public void runCheckForUpdates(MessageChannel target) throws IOException {
        loadFile();
        checkForUpdates(target);
        saveFile();
    }

    private void loadFile() {
        for (String id : SecretConstants.COMIC_URLS.keySet()) {
            try (Stream<String> stream = Files.lines(Paths.get("previous_comic_posts_" + id + ".txt"))) {
                alreadyRetrievedArticles.put(id, new HashSet<>(stream.toList()));
            } catch (IOException e) {
                log.warn("Cannot load already previous articles for {}", id, e);
            }
        }
    }

    private void checkForUpdates(MessageChannel target) throws IOException {
        for (Map.Entry<String, String> comic : SecretConstants.COMIC_URLS.entrySet()) {
            String[] splitValue = comic.getValue().split(";");
            checkForUpdatesFor(target, comic.getKey(), splitValue[0], splitValue[1], splitValue[2]);
        }
    }

    private void checkForUpdatesFor(MessageChannel target, String id, String url, String selector, String urlPrefix) throws IOException {
        String imageUrl = ConnectionUtils.jsoupGetWithRetry(url).select(selector).attr("src");

        if (!alreadyRetrievedArticles.get(id).contains(imageUrl)) {
            target.sendMessage(urlPrefix + imageUrl).queue();
            alreadyRetrievedArticles.get(id).add(imageUrl);
        }
    }

    private void saveFile() throws IOException {
        for (String id : alreadyRetrievedArticles.keySet()) {
            FileUtils.writeStringToFile(
                    new File("previous_comic_posts_" + id + ".txt"),
                    String.join("\n", alreadyRetrievedArticles.get(id)),
                    StandardCharsets.UTF_8);
        }
    }
}
