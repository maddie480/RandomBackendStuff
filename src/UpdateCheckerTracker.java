package com.max480.discord.randombots;

import com.google.cloud.storage.*;
import com.max480.quest.modmanagerbot.BotClient;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.ZonedDateTime;
import java.util.Collections;

/**
 * A service that follows the Update Checker logs and re-posts them to a Discord channel.
 * It also calls frontend APIs to make it aware of database changes, and reload it as necessary.
 */
public class UpdateCheckerTracker implements TailerListener {
    private static final Logger log = LoggerFactory.getLogger(UpdateCheckerTracker.class);

    private static boolean lastLineIsNetworkError = false;
    protected static ZonedDateTime lastLogLineDate = ZonedDateTime.now();

    private static boolean luaCutscenesUpdated = false;

    private static final Storage storage = StorageOptions.newBuilder().setProjectId("max480-random-stuff").build().getService();

    /**
     * Method to call to start the watcher thread.
     */
    public static void startThread() {
        TailerListener listener = new UpdateCheckerTracker();
        Tailer tailer = new Tailer(new File("/tmp/update_checker.log"), listener, 59950, true);
        new Thread(tailer).start();
    }

    @Override
    public void init(Tailer tailer) {
        log.info("Starting tracking /tmp/update_checker.log");
    }

    @Override
    public void fileNotFound() {
        log.warn("/tmp/update_checker.log was not found");
    }

    @Override
    public void fileRotated() {
        log.warn("/tmp/update_checker.log was rotated");
    }

    @Override
    public void handle(String line) {
        log.debug("New line in /tmp/update_checker.log: {}", line);
        lastLogLineDate = ZonedDateTime.now();

        if (line != null &&
                !line.contains("=== Started searching for updates") &&
                !line.contains("=== Ended searching for updates.") &&
                !line.contains("Waiting for 15 minute(s) before next update.")) {

            if (line.contains("I/O exception while doing networking operation (try ")) {
                lastLineIsNetworkError = true;
            } else if (!lastLineIsNetworkError || line.contains(" [Thread-1] ")) {
                // this isn't a muted line! truncate it if it is too long for Discord
                if (line.length() > 1998) {
                    line = line.substring(0, 1998);
                }

                lastLineIsNetworkError = false;

                // and post it!
                BotClient.getInstance().getTextChannelById(SecretConstants.UPDATE_CHECKER_CHANNEL)
                        .sendMessage("`" + line + "`").queue();

                // flag Lua Cutscenes updates
                if (line.contains("name='LuaCutscenes'")) {
                    luaCutscenesUpdated = true;
                }

                // when a mod change or anything Banana Mirror happens, call a webhook.
                if (line.contains("Saved new information to database:")
                        || line.contains("was deleted from the database")
                        || (line.contains("Adding to the excluded files list.") && !line.contains("database already contains more recent file"))
                        || line.contains("Adding to the no yaml files list.")
                        || line.contains("to Banana Mirror")
                        || line.contains("from Banana Mirror")) {

                    for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
                        String truncatedLine = line;
                        if (truncatedLine.contains(" - ")) {
                            truncatedLine = truncatedLine.substring(truncatedLine.indexOf(" - ") + 3);
                        }
                        if (!truncatedLine.startsWith("=> ")) {
                            truncatedLine = "=> " + truncatedLine;
                        }
                        truncatedLine = truncatedLine.replace("=> ", ":arrow_right: ");

                        try {
                            WebhookExecutor.executeWebhook(webhook,
                                    "https://cdn.discordapp.com/avatars/793432836912578570/0a3f716e15c8c3adca6c461c2d64553e.png?size=128",
                                    "Everest Update Checker",
                                    truncatedLine, false, null, Collections.emptyList());
                        } catch (InterruptedException | IOException e) {
                            log.error("Error while sending alert", e);
                        }
                    }
                }
            }
        }

        if (line != null && line.contains("=== Ended searching for updates.")) {
            // refresh is done! we should tell the frontend about it.
            log.info("Calling frontend to refresh mod search and Everest update");

            try {
                // send database files to Cloud Storage
                sendToCloudStorage("uploads/everestupdate.yaml", "everest_update.yaml", "text/yaml", false);
                sendToCloudStorage("uploads/modsearchdatabase.yaml", "mod_search_database.yaml", "text/yaml", false);
                sendToCloudStorage("modfilesdatabase/file_ids.yaml", "file_ids.yaml", "text/yaml", false);

                // refresh mod search and everest_update.yaml on the frontend
                HttpURLConnection conn = (HttpURLConnection) new URL(SecretConstants.EVEREST_UPDATE_RELOAD_API).openConnection();
                if (conn.getResponseCode() != 200) {
                    throw new IOException("Everest Update Reload API sent non 200 code: " + conn.getResponseCode());
                }
                conn = (HttpURLConnection) new URL(SecretConstants.MOD_SEARCH_RELOAD_API).openConnection();
                if (conn.getResponseCode() != 200) {
                    throw new IOException("Mod Search Reload API sent non 200 code: " + conn.getResponseCode());
                }

                if (luaCutscenesUpdated) {
                    // also tell the frontend that Lua Cutscenes got updated, so that it can mirror the docs again.
                    conn = (HttpURLConnection) new URL(SecretConstants.LUA_CUTSCENES_DOC_UPLOAD_API).openConnection();
                    if (conn.getResponseCode() != 200) {
                        throw new IOException("Lua Cutscenes Documentation Upload API sent non 200 code: " + conn.getResponseCode());
                    } else {
                        luaCutscenesUpdated = false;
                    }
                }
            } catch (IOException e) {
                log.error("Error during a call to frontend to refresh databases", e);
                BotClient.getInstance().getTextChannelById(SecretConstants.UPDATE_CHECKER_CHANNEL)
                        .sendMessage("Frontend call failed: " + e.toString()).queue();
            }
        }
    }

    @Override
    public void handle(Exception ex) {
        log.error("Error while tracking /tmp/update_checker.log", ex);

        BotClient.getInstance().getTextChannelById(SecretConstants.UPDATE_CHECKER_CHANNEL)
                .sendMessage("Error while tracking /tmp/update_checker.log: " + ex.toString())
                .queue();
    }

    public static void sendToCloudStorage(String file, String name, String contentType, boolean isPublic) throws IOException {
        BlobId blobId = BlobId.of("max480-random-stuff.appspot.com", name);
        BlobInfo.Builder blobInfoBuilder = BlobInfo.newBuilder(blobId).setContentType(contentType);
        if (!isPublic) {
            // do not cache private stuff.
            blobInfoBuilder.setCacheControl("no-store");
        }
        storage.create(blobInfoBuilder.build(), FileUtils.readFileToByteArray(new File(file)));

        if (isPublic) {
            storage.createAcl(blobId, Acl.of(Acl.User.ofAllUsers(), Acl.Role.READER));
        }
    }
}
