package com.max480.discord.randombots;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Notifies other platforms about updates to the mod updater database, Olympus news or Everest versions.
 * Run at the end of the update loop, every 15 minutes.
 */
public class UpdateOutgoingWebhooks {
    private static final Logger log = LoggerFactory.getLogger(UpdateOutgoingWebhooks.class);

    public static void notifyUpdate() throws IOException {
        // update China-accessible mirror of everest_update.yaml, mod_search_database.yaml, everest-versions and olympus-news
        ConnectionUtils.runWithRetry(() -> {
            HttpURLConnection urlConn = (HttpURLConnection) new URL(SecretConstants.CHINA_MIRROR_UPDATE_WEBHOOK).openConnection();
            urlConn.setConnectTimeout(10000);
            urlConn.setReadTimeout(30000);
            urlConn.setInstanceFollowRedirects(false);
            urlConn.setRequestProperty("Content-Type", "application/json");
            urlConn.setDoOutput(true);
            urlConn.setRequestMethod("POST");

            try (OutputStream os = urlConn.getOutputStream()) {
                IOUtils.write("{}", os, StandardCharsets.UTF_8);
            }

            if (urlConn.getResponseCode() != 200) {
                throw new IOException("Non-200 return code for Chinese mirror update: " + urlConn.getResponseCode());
            }

            log.debug("Notified China mirror webhook of the end of the update!");

            return null; // method signature
        });
    }
}
