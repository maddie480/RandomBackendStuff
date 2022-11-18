package com.max480.randomstuff.backend.discord.crontabs;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class is run daily in order to clean up custom commands for servers that removed the integration.
 */
public class CustomSlashCommandsCleanup {
    private static final Logger log = LoggerFactory.getLogger(CustomSlashCommandsCleanup.class);

    private static final Storage storage = StorageOptions.newBuilder().setProjectId("max480-random-stuff").build().getService();
    private static final Pattern blobNamePattern = Pattern.compile("^custom_slash_commands/(\\d+)/(.+)\\.json$");

    private static final String USER_AGENT = "DiscordBot (https://max480-random-stuff.appspot.com, 1.0)";

    public static void housekeep() throws IOException {
        Map<Long, Set<String>> commandsPerGuild = new HashMap<>();

        // 1. make a list of commands we think the servers have
        Page<Blob> blobs = storage.list("max480-random-stuff.appspot.com", Storage.BlobListOption.prefix("custom_slash_commands/"));

        for (Blob blob : blobs.iterateAll()) {
            Matcher matcher = blobNamePattern.matcher(blob.getName());
            if (matcher.matches()) {
                long guildId = Long.parseLong(matcher.group(1));
                String commandName = matcher.group(2);

                Set<String> list = commandsPerGuild.getOrDefault(guildId, new HashSet<>());
                list.add(commandName);
                commandsPerGuild.put(guildId, list);
            }
        }

        String token = authenticate();

        for (Map.Entry<Long, Set<String>> commandPerGuild : commandsPerGuild.entrySet()) {
            // 2. List all commands we actually have registered on each server.
            long serverId = commandPerGuild.getKey();
            JSONArray commands = getSlashCommandList(serverId, token);

            if (commands == null) {
                // If we lost access to the server, clean it up entirely.
                log.warn("Left server {} => removing all slash commands!", serverId);

                for (String commandName : commandPerGuild.getValue()) {
                    log.debug("Deleting custom_slash_commands/{}/{}.json", serverId, commandName);
                    storage.delete("max480-random-stuff.appspot.com", "custom_slash_commands/" + serverId + "/" + commandName + ".json");
                }
            } else {
                // If we still have access to the server, check that all commands we have still exist.
                // This might not be the case if the bot was kicked then re-invited.
                for (String commandName : commandPerGuild.getValue()) {
                    boolean found = false;
                    for (Object command : commands) {
                        if (((JSONObject) command).getString("name").equals(commandName)) {
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        log.warn("Command {} not found for {} => removing it!", commandName, serverId);
                        log.debug("Deleting custom_slash_commands/{}/{}.json", serverId, commandName);
                        storage.delete("max480-random-stuff.appspot.com", "custom_slash_commands/" + serverId + "/" + commandName + ".json");
                    }
                }
            }
        }
    }

    private static JSONArray getSlashCommandList(long serverId, String token) throws IOException {
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://discord.com/api/v10/applications/" + SecretConstants.CUSTOM_SLASH_COMMANDS_CLIENT_ID + "/guilds/" + serverId + "/commands");

        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("User-Agent", USER_AGENT);

        if (connection.getResponseCode() == 200) {
            try (InputStream is = connection.getInputStream()) {
                return new JSONArray(IOUtils.toString(is, StandardCharsets.UTF_8));
            }
        } else {
            try (InputStream is = connection.getErrorStream()) {
                JSONObject error = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                if (error.getInt("code") == 50001) {
                    // Missing Access: this means we got kicked
                    return null;
                }

                throw new IOException("Server responded with HTTP code " + connection.getResponseCode() + " when listing commands for server " + serverId + " with body: " + error);
            }
        }
    }

    private static String authenticate() throws IOException {
        String basicAuth = Base64.getEncoder().encodeToString(
                (SecretConstants.CUSTOM_SLASH_COMMANDS_CLIENT_ID + ":" + SecretConstants.CUSTOM_SLASH_COMMANDS_CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));

        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://discord.com/api/oauth2/token");
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Basic " + basicAuth);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            IOUtils.write("grant_type=client_credentials&scope=applications.commands.update", os, StandardCharsets.UTF_8);
        }

        try (InputStream is = connection.getInputStream()) {
            String response = IOUtils.toString(is, StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(response);
            return o.getString("access_token");
        }
    }
}
