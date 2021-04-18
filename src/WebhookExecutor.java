package com.max480.discord.randombots;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public class WebhookExecutor {
    private static final Logger log = LoggerFactory.getLogger(WebhookExecutor.class);

    private static ZonedDateTime retryAfter = null;

    public static void executeWebhook(String webhookUrl, String avatar, String nickname, String body,
                                      boolean allowUserMentions, Long allowedUserMentionId,
                                      List<File> attachments) throws IOException, InterruptedException {

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

        HttpURLConnection connection;
        if (attachments.isEmpty()) {
            // webhook with no attachment: pure JSON
            log.debug("Sending request to [{}]: {}", webhookUrl, request);

            connection = (HttpURLConnection) new URL(webhookUrl).openConnection();

            connection.setDoInput(true);
            connection.setDoOutput(true);

            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:62.0) Gecko/20100101 Firefox/62.0");

            connection.connect();

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream()));
            writer.write(request.toString());
            writer.close();
        } else {
            // multipart request to send the JSON, and attachments
            log.debug("Sending request to [{}]: {} with attachments [\"{}\"]", webhookUrl, request.toString(),
                    attachments.stream().map(File::getAbsolutePath).collect(Collectors.joining("\", \"")));

            HashMap<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:62.0) Gecko/20100101 Firefox/62.0");
            HttpPostMultipart multipart = new HttpPostMultipart(webhookUrl, "UTF-8", headers);

            multipart.addFormField("payload_json", request.toString());
            int index = 0;
            for (File f : attachments) {
                multipart.addFilePart("file_" + (index++), f);
            }
            connection = multipart.finish();
        }

        // make sure the message came through
        if (connection.getResponseCode() != 204 && connection.getResponseCode() != 200) {
            throw new IOException("Non-200/204 return code: " + connection.getResponseCode());
        }

        log.debug("Message sent!");

        // make sure to remember if we hit rate limit.
        if ("0".equals(connection.getHeaderField("X-RateLimit-Remaining"))) {
            try {
                retryAfter = ZonedDateTime.now().plusSeconds(Integer.parseInt(connection.getHeaderField("X-RateLimit-Reset-After")) + 1);
                log.warn("We hit rate limit! We will wait until {} before next request.", retryAfter);
            } catch (Exception e) {
                retryAfter = ZonedDateTime.now().plusSeconds(15);
                log.warn("We hit rate limit! We will wait until {} before next request. (parsing X-RateLimit-Reset-After failed)", retryAfter);
            }
        }
    }
}
