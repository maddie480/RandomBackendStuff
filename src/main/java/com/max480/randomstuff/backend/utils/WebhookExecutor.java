package com.max480.randomstuff.backend.utils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class WebhookExecutor {
    public static class UnknownWebhookException extends RuntimeException {
    }

    private static final Logger log = LoggerFactory.getLogger(WebhookExecutor.class);

    private static ZonedDateTime retryAfter = null;

    /**
     * Calls a Discord webhook without enabling mentions.
     */
    public static void executeWebhook(String webhookUrl, String avatar, String nickname, String body) throws IOException {
        executeWebhook(webhookUrl, avatar, nickname, body, Collections.emptyMap(), false, null, Collections.emptyList(), null);
    }

    /**
     * Calls a Discord webhook without enabling mentions, with embeds.
     */
    public static void executeWebhook(String webhookUrl, String avatar, String nickname, String body, List<Map<String, Object>> embeds) throws IOException {
        executeWebhook(webhookUrl, avatar, nickname, body, Collections.emptyMap(), false, null, Collections.emptyList(), embeds);
    }

    /**
     * Calls a Discord webhook without enabling mentions, with embeds and attachments.
     */
    public static void executeWebhook(String webhookUrl, String avatar, String nickname, String body, List<File> attachments, List<Map<String, Object>> embeds) throws IOException {
        executeWebhook(webhookUrl, avatar, nickname, body, Collections.emptyMap(), false, null, attachments, embeds);
    }

    /**
     * Calls a Discord webhook without enabling mentions, with specific HTTP headers.
     */
    public static void executeWebhook(String webhookUrl, String avatar, String nickname, String body, Map<String, String> httpHeaders)
            throws IOException {
        executeWebhook(webhookUrl, avatar, nickname, body, httpHeaders, false, null, Collections.emptyList(), null);
    }

    /**
     * Calls a Discord webhook, allowing it to ping someone in particular.
     */
    public static void executeWebhook(String webhookUrl, String avatar, String nickname, String body, long allowedUserMentionId)
            throws IOException {
        executeWebhook(webhookUrl, avatar, nickname, body, Collections.emptyMap(), false, allowedUserMentionId, Collections.emptyList(), null);
    }

    /**
     * Calls a Discord webhook, optionally enabling user mentions and with attachments.
     */
    public static void executeWebhook(String webhookUrl, String avatar, String nickname, String body, boolean allowUserMentions, List<File> attachments)
            throws IOException {
        executeWebhook(webhookUrl, avatar, nickname, body, Collections.emptyMap(), allowUserMentions, null, attachments, null);
    }

    private static void executeWebhook(String webhookUrl, String avatar, String nickname, String body,
                                       Map<String, String> httpHeaders, boolean allowUserMentions, Long allowedUserMentionId,
                                       List<File> attachments, List<Map<String, Object>> embeds) throws IOException {

        ConnectionUtils.runWithRetry(() -> {
            try {
                executeWebhookInternal(webhookUrl, avatar, nickname, body, httpHeaders, allowUserMentions, allowedUserMentionId, attachments, embeds);
            } catch (InterruptedException e) {
                // this should never happen, so whatever. :p
                throw new IOException(e);
            }

            return null; // we have to satisfy the signature
        });
    }

    private static void executeWebhookInternal(String webhookUrl, String avatar, String nickname, String body,
                                               Map<String, String> httpHeaders, boolean allowUserMentions, Long allowedUserMentionId,
                                               List<File> attachments, List<Map<String, Object>> embeds) throws IOException, InterruptedException {

        // primitive handling for rate limits.
        if (retryAfter != null) {
            long waitFor = ZonedDateTime.now().until(retryAfter, ChronoUnit.MILLIS);
            if (waitFor > 0) {
                log.warn("Waiting {} ms before request because of rate limits.", waitFor);
                Thread.sleep(waitFor);
            }
        }
        retryAfter = null;

        // start by setting avatar, username and content.
        JSONObject request = new JSONObject();
        request.put("avatar_url", avatar);
        request.put("username", nickname);
        request.put("content", body);

        // allow or block mentions (pinging users, or pinging a user in particular)
        JSONObject allowedMentions = new JSONObject();
        JSONArray allowedMentionsParse = new JSONArray();
        if (allowUserMentions) {
            allowedMentionsParse.put("users");
        }
        allowedMentions.put("parse", allowedMentionsParse);
        if (allowedUserMentionId != null) {
            JSONArray users = new JSONArray();
            users.put(allowedUserMentionId.toString());
            allowedMentions.put("users", users);
        }
        request.put("allowed_mentions", allowedMentions);

        if (embeds != null) {
            request.put("embeds", embeds);
        }

        HttpURLConnection connection;
        if (attachments.isEmpty()) {
            // webhook with no attachment: pure JSON
            log.debug("Sending request to [{}]: {}", webhookUrl, request);

            connection = ConnectionUtils.openConnectionWithTimeout(webhookUrl + "?wait=true");

            connection.setDoInput(true);
            connection.setDoOutput(true);

            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");

            for (Map.Entry<String, String> header : httpHeaders.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            connection.connect();

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream()));
            writer.write(request.toString());
            writer.close();
        } else {
            // multipart request to send the JSON, and attachments
            log.debug("Sending request to [{}]: {} with attachments [\"{}\"]", webhookUrl, request,
                    attachments.stream().map(File::getAbsolutePath).collect(Collectors.joining("\", \"")));

            HashMap<String, String> headers = new HashMap<>();
            headers.putAll(httpHeaders);
            HttpPostMultipart multipart = new HttpPostMultipart(webhookUrl + "?wait=true", "UTF-8", headers);

            multipart.addFormField("payload_json", request.toString());
            int index = 0;
            for (File f : attachments) {
                multipart.addFilePart("file_" + (index++), f);
            }
            connection = multipart.finish();
        }

        if (connection.getResponseCode() == 204 || connection.getResponseCode() == 200) {
            // the message came through
            log.debug("Message sent!");

        } else if (connection.getResponseCode() == 429) {
            // we hit an unexpected rate limit => we should wait for the time indicated in Retry-After, then retry.
            // (Discord docs claim those are seconds, but those actually seem to be milliseconds. /shrug)
            retryAfter = ZonedDateTime.now().plus(Integer.parseInt(connection.getHeaderField("Retry-After")), ChronoUnit.MILLIS);
            log.warn("We hit a rate limit we did not anticipate! We will wait until {} before next request.", retryAfter);
            executeWebhookInternal(webhookUrl, avatar, nickname, body, httpHeaders, allowUserMentions, allowedUserMentionId, attachments, embeds);
            return;

        } else if (connection.getResponseCode() == 404) {
            // webhook is gone!
            throw new UnknownWebhookException();

        } else {
            // we hit some other error => we should crash
            throw new IOException("Non-200/204 return code: " + connection.getResponseCode());
        }

        // make sure to remember if we hit rate limit.
        if ("0".equals(connection.getHeaderField("X-RateLimit-Remaining"))) {
            try {
                retryAfter = ZonedDateTime.now().plusSeconds(Integer.parseInt(connection.getHeaderField("X-RateLimit-Reset-After")) + 1);
                log.warn("We are going to hit rate limit! We will wait until {} before next request.", retryAfter);
            } catch (Exception e) {
                retryAfter = ZonedDateTime.now().plusSeconds(15);
                log.warn("We are going to hit rate limit! We will wait until {} before next request. (parsing X-RateLimit-Reset-After failed)", retryAfter);
            }
        }
    }
}
