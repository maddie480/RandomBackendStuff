package com.max480.randomstuff.backend.discord.crontabs;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class is run daily in order to clean up custom commands for servers that removed the integration.
 */
public class CustomSlashCommandsCleanup {
    private static final Logger log = LoggerFactory.getLogger(CustomSlashCommandsCleanup.class);

    private static final Pattern blobNamePattern = Pattern.compile("^/shared/discord-bots/custom-slash-commands/(\\d+)/(.+)\\.json$");

    private static final String USER_AGENT = "DiscordBot (https://maddie480.ovh, 1.0)";

    public static void housekeep() throws IOException {
        Map<Long, Set<String>> commandsPerGuild;

        try (Stream<Path> fileList = Files.walk(Paths.get("/shared/discord-bots/custom-slash-commands"))) {
            commandsPerGuild = fileList
                    .map(path -> blobNamePattern.matcher(path.toAbsolutePath().toString()))
                    .filter(Matcher::matches)
                    .collect(Collectors.toMap(
                            // key = guild ID
                            matcher -> Long.parseLong(matcher.group(1)),

                            // value = list of command IDs, which we initialize with a singleton set
                            matcher -> Collections.singleton(matcher.group(2)),

                            // merge of 2 values with the same keys = just merge the 2 sets
                            (list1, list2) -> {
                                Set<String> merged = new HashSet<>(list1);
                                merged.addAll(list2);
                                return merged;
                            }
                    ));

        }

        String token = authenticate();

        for (Map.Entry<Long, Set<String>> commandPerGuild : commandsPerGuild.entrySet()) {
            // 2. List all commands we actually have registered on each server.
            long serverId = commandPerGuild.getKey();
            JSONArray commands = getSlashCommandList(serverId, token);

            if (commands == null) {
                // If we lost access to the server, clean it up entirely.
                log.warn("Left server {} => removing all slash commands!", serverId);
                FileUtils.deleteDirectory(new File("/shared/discord-bots/custom-slash-commands/" + serverId));
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
                        Files.delete(Paths.get("/shared/discord-bots/custom-slash-commands/" + serverId + "/" + commandName + ".json"));

                        // delete folder if it is now empty
                        File commandFolder = new File("/shared/discord-bots/custom-slash-commands/" + serverId);
                        if (commandFolder.list().length == 0) {
                            FileUtils.deleteDirectory(commandFolder);
                        }
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
            try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
                return new JSONArray(IOUtils.toString(is, StandardCharsets.UTF_8));
            }
        } else {
            try (InputStream is = connection.getErrorStream()) {
                String s = IOUtils.toString(is, StandardCharsets.UTF_8);
                log.warn("Error {}: {}", connection.getResponseCode(), s);
                JSONObject error = new JSONObject(s);

                if (error.has("retry_after")) {
                    try {
                        log.warn("Waiting for {}s because of rate limit!", error.getFloat("retry_after"));
                        Thread.sleep((int) (error.getFloat("retry_after") * 1000));
                        return getSlashCommandList(serverId, token);
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                }

                if (error.has("code") && error.getInt("code") == 50001) {
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

        try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
            String response = IOUtils.toString(is, StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(response);
            return o.getString("access_token");
        }
    }
}
