package com.max480.randomstuff.backend.celeste.crontabs;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Notifies other platforms about updates to the mod updater database or Everest versions.
 * Run at the end of the update loop, every 15 minutes.
 */
public class UpdateOutgoingWebhooks {
    private static final Logger log = LoggerFactory.getLogger(UpdateOutgoingWebhooks.class);

    private static boolean changesHappened = false;

    public static void changesHappened() {
        changesHappened = true;
    }

    public static void notifyUpdate() throws IOException {
        if (!changesHappened) {
            return;
        }

        // update China-accessible mirror of everest_update.yaml, mod_search_database.yaml and everest-versions
        ConnectionUtils.runWithRetry(() -> {
            HttpURLConnection urlConn = ConnectionUtils.openConnectionWithTimeout(SecretConstants.CHINA_MIRROR_UPDATE_WEBHOOK);
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

        changesHappened = false;
    }
}
