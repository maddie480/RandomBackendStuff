package com.max480.randomstuff.backend.celeste.crontabs;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.WebhookExecutor;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

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
            try (InputStream is = Files.newInputStream(jsonPath)) {
                json = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
            }

            Instant updatedAt = Files.getLastModifiedTime(jsonPath).toInstant();
            String status = json.getString("status");

            log.debug("Collab/Contest {} stored at {} is {}, and was last updated on {}", json.get("name"), jsonPath.getFileName(),
                    status, updatedAt.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

            if (Arrays.asList("in-progress", "paused").contains(status)
                    && updatedAt.isBefore(Instant.now().minus(30, ChronoUnit.DAYS))) {

                log.warn("Collab has expired!");
                json.put("status", "hidden");

                try (OutputStream os = Files.newOutputStream(jsonPath)) {
                    IOUtils.write(json.toString(), os, StandardCharsets.UTF_8);
                }

                sendAlertToWebhook(jsonPath, "automatically hidden, since it was " + status
                        + " and not updated in the last 30 days");

            } else if (Arrays.asList("hidden", "cancelled").contains(status)
                    && updatedAt.isBefore(Instant.now().minus(180, ChronoUnit.DAYS))) {

                log.warn("Collab has been {} for 6 months!", status);
                Files.delete(jsonPath);

                sendAlertToWebhook(jsonPath, "deleted, since it was " + status
                        + " and not updated for the last 6 months");
            }
        }
    }

    private static void sendAlertToWebhook(Path jsonPath, String action) throws IOException {
        WebhookExecutor.executeWebhook(
                SecretConstants.UPDATE_CHECKER_LOGS_HOOK,
                "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/compute-engine.png",
                "Collab and Contest List",
                ":warning: The collab or contest located at <https://maddie480.ovh/celeste/collab-contest-editor?key="
                        + jsonPath.getFileName().toString().replace(".json", "") + "> was " + action + ".");
    }
}
