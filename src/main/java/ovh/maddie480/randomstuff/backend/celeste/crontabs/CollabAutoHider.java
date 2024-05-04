package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A process that runs hourly to hide automatically all collabs and contests that were not modified within the last 30 days,
 * to make sure no silently abandoned collab/contest is on the list.
 * Only collabs and contests with statuses set to "In Progress" or "Paused" are processed.
 */
public class CollabAutoHider {
    private static final Logger log = LoggerFactory.getLogger(CollabAutoHider.class);

    public static void run() throws IOException {
        for (String s : new File("/shared/celeste/collab-list").list()) {
            Path jsonPath = Paths.get("/shared/celeste/collab-list/" + s);
            JSONObject json;
            try (BufferedReader br = Files.newBufferedReader(jsonPath)) {
                json = new JSONObject(new JSONTokener(br));
            }

            Instant updatedAt = Files.getLastModifiedTime(jsonPath).toInstant();
            String status = json.getString("status");

            log.debug("Collab/Contest {} stored at {} is {}, and was last updated on {}", json.get("name"), jsonPath.getFileName(),
                    status, updatedAt.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

            String key = jsonPath.getFileName().toString().replace(".json", "");

            if (Arrays.asList("in-progress", "paused").contains(status)
                    && updatedAt.isBefore(Instant.now().minus(30, ChronoUnit.DAYS))) {

                log.warn("Collab has expired!");
                json.put("status", "hidden");

                try (BufferedWriter bw = Files.newBufferedWriter(jsonPath)) {
                    json.write(bw);
                }

                sendAlertToWebhook(key, "automatically hidden, since it was " + status
                        + " and not updated in the last 30 days");
                notifyModAuthor(key, json.getString("name"));

            } else if (Arrays.asList("hidden", "cancelled").contains(status)
                    && updatedAt.isBefore(Instant.now().minus(180, ChronoUnit.DAYS))) {

                log.warn("Collab has been {} for 6 months!", status);
                Files.delete(jsonPath);

                sendAlertToWebhook(key, "deleted, since it was " + status
                        + " and not updated for the last 6 months");
            }
        }
    }

    private static void sendAlertToWebhook(String key, String action) throws IOException {
        WebhookExecutor.executeWebhook(
                SecretConstants.UPDATE_CHECKER_LOGS_HOOK,
                "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/compute-engine.png",
                "Collab and Contest List",
                ":warning: The collab or contest located at <https://maddie480.ovh/celeste/collab-contest-editor?key="
                        + key + "> was " + action + ".");
    }

    private static void notifyModAuthor(String key, String name) throws IOException {
        Map<String, Long> keysToDiscordIds;
        try (Stream<String> keyMap = Files.lines(Paths.get("collab-editor-keys.txt"))) {
            keysToDiscordIds = keyMap.collect(Collectors.toMap(
                    line -> line.substring(0, line.indexOf(" ")),
                    line -> Long.parseLong(line.substring(line.indexOf(" => ") + 4, line.indexOf(" (")))
            ));
        }

        if (keysToDiscordIds.containsKey(key)) {
            WebhookExecutor.executeWebhook(SecretConstants.COLLAB_AUTO_HIDDEN_ALERT_HOOK,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/compute-engine.png",
                    "Collab and Contest List",
                    ":warning: <@" + keysToDiscordIds.get(key) + ">, your collab or contest named **" + name + "** was automatically " +
                            "hidden from the Collab and Contest List, because it was not updated for 30 days.\n" +
                            "Use the link Maddie sent you to update your collab/contest's info in order to make it visible again!",
                    keysToDiscordIds.get(key));
        }
    }
}
