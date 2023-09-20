package com.max480.randomstuff.backend.twitch;

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.twitch4j.chat.TwitchChat;
import com.github.twitch4j.chat.TwitchChatBuilder;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.github.twitch4j.helix.TwitchHelix;
import com.github.twitch4j.helix.TwitchHelixBuilder;
import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * A small Twitch bot that listens to the chat of a specific channel, and that can respond to 2 commands:
 * !clip - creates a clip
 * !poll - runs a poll, users can vote using keywords in the chat
 */
public class LNJTwitchBot {
    private static final Logger logger = LoggerFactory.getLogger(LNJTwitchBot.class);

    private static final String CHANNEL_NAME = "lesnavetsjouables";
    private static final String CHANNEL_ID = "431608356";

    private static long lastClipAt = 0;
    private static boolean rateLimitMessageSent = false;

    private static final Map<String, String> pollNames = new HashMap<>();
    private static final Map<String, Map<String, String>> pollChoicesWithCase = new HashMap<>();
    private static final Map<String, Set<String>> keywordToPoll = new HashMap<>();
    private static final Map<String, Map<String, String>> whoVotedForWhat = new HashMap<>();

    public static void main(String[] args) throws IOException {
        // use the refresh token to get a new access token
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://id.twitch.tv/oauth2/token");
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        Path refreshTokenFile = Paths.get("twitch_refresh_token.txt");
        Path authorizationCode = Paths.get("twitch_authorization_code.txt");

        // to reauthorize, follow this link:
        // https://id.twitch.tv/oauth2/authorize?client_id=zsyvgsio6ww6rqnf6lq3zbu7pvr287&redirect_uri=http://localhost&response_type=code&scope=chat:read+chat:edit+clips:edit
        if (Files.exists(authorizationCode)) {
            logger.debug("Exchanging a code for a token...");

            try (InputStream authorizationCodeInput = Files.newInputStream(authorizationCode);
                 OutputStream os = connection.getOutputStream()) {

                IOUtils.write("grant_type=authorization_code" +
                                "&code=" + IOUtils.toString(authorizationCodeInput, StandardCharsets.UTF_8) +
                                "&client_id=" + SecretConstants.TWITCH_CLIENT_ID +
                                "&client_secret=" + SecretConstants.TWITCH_CLIENT_SECRET +
                                "&redirect_uri=http://localhost",
                        os, StandardCharsets.UTF_8);
            }
        } else {
            logger.debug("Refreshing the token...");

            // exchange the refresh token for new tokens
            try (InputStream refreshTokenInput = Files.newInputStream(refreshTokenFile);
                 OutputStream os = connection.getOutputStream()) {

                IOUtils.write("grant_type=refresh_token" +
                                "&refresh_token=" + IOUtils.toString(refreshTokenInput, StandardCharsets.UTF_8) +
                                "&client_id=" + SecretConstants.TWITCH_CLIENT_ID +
                                "&client_secret=" + SecretConstants.TWITCH_CLIENT_SECRET,
                        os, StandardCharsets.UTF_8);
            }
        }

        if (connection.getResponseCode() != 200) {
            try (InputStream is = connection.getErrorStream()) {
                throw new IOException("Refresh token request failed with code " + connection.getResponseCode() + ": " + IOUtils.toString(is, StandardCharsets.UTF_8));
            }
        }

        String accessToken;
        String refreshToken;

        try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
            JSONObject response = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
            accessToken = response.getString("access_token");
            refreshToken = response.getString("refresh_token");
            logger.debug("Got a new token that expires in {} seconds!", response.getInt("expires_in"));
        }

        if (Files.exists(authorizationCode)) {
            Files.delete(authorizationCode);
        }
        try (OutputStream os = Files.newOutputStream(refreshTokenFile)) {
            IOUtils.write(refreshToken, os, StandardCharsets.UTF_8);
        }

        TwitchChat chat = TwitchChatBuilder.builder()
                .withChatAccount(new OAuth2Credential("twitch", accessToken))
                .build();

        chat.joinChannel(CHANNEL_NAME);
        chat.sendMessage(CHANNEL_NAME, "Je suis prêt !");

        TwitchHelix helix = TwitchHelixBuilder.builder().build();
        chat.getEventManager().onEvent(ChannelMessageEvent.class, event -> handleChatMessage(event, chat, helix, accessToken));

        new Thread(() -> {
            try {
                Thread.sleep(21_600_000L); // 6 hours

                logger.info("We expired, exit!");
                chat.sendMessage(CHANNEL_NAME, "Je me déconnecte. Bonne soirée !");
                Thread.sleep(5000);
                System.exit(0);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }).start();

        logger.debug("Startup finished!");
    }

    private static void handleChatMessage(ChannelMessageEvent event, TwitchChat chat, TwitchHelix helix, String accessToken) {
        if (event.getMessage().trim().equals("!clip")) {
            logger.debug("Received a !clip command from " + event.getMessageEvent().getUserName());

            if (System.currentTimeMillis() - lastClipAt > 30000) {
                logger.debug("Time to create a clip!");

                CompletableFuture
                        .supplyAsync(() -> helix.createClip(accessToken, CHANNEL_ID, false).execute())
                        .thenAcceptAsync(clip -> {
                            String clipLink = clip.getData().get(0).getEditUrl();
                            if (clipLink.endsWith("/edit")) clipLink = clipLink.substring(0, clipLink.length() - 5);
                            respond(event, chat, "Le clip a été créé : " + clipLink);
                        })
                        .exceptionallyAsync(boom -> {
                            logger.error("Could not create clip", boom);
                            respond(event, chat, "Quelque chose n'a pas fonctionné, désolé. :/");
                            return null;
                        });

                lastClipAt = System.currentTimeMillis();
                rateLimitMessageSent = false;
            } else if (!rateLimitMessageSent) {
                logger.debug("The user was rate limited with a message");
                respond(event, chat, "Du calme ! Le dernier clip a été pris il y a moins de 30 secondes.");
                rateLimitMessageSent = true;
            } else {
                logger.debug("The user was rate limited without a message");
            }
        } else if ((event.getMessage().trim().startsWith("!poll ") || event.getMessage().trim().equals("!poll"))
                && event.getMessageEvent().getUserId().equals(CHANNEL_ID)) {

            List<String> command = new CommandParser(event.getMessage().trim()).parse();

            if (command.size() < 3) {
                // we need at least a question and an answer!
                respond(event, chat, "Tu dois au moins préciser une question et une réponse ! Par exemple : !poll \"à quoi on joue ce soir ?\" \"pizza dude\" \"geopolitical simulator\" freelancer");
            } else {
                String uuid = UUID.randomUUID().toString();
                String title = command.get(1);

                Map<String, String> choices = new HashMap<>();
                for (int i = 2; i < command.size(); i++) {
                    choices.put(command.get(i).toLowerCase(), command.get(i));
                }

                pollNames.put(uuid, title);
                pollChoicesWithCase.put(uuid, choices);
                for (int i = 2; i < command.size(); i++) {
                    String choice = command.get(i).toLowerCase();
                    Set<String> pollUuids = keywordToPoll.getOrDefault(choice, new HashSet<>());
                    pollUuids.add(uuid);
                    keywordToPoll.put(choice, pollUuids);
                }
                whoVotedForWhat.put(uuid, new HashMap<>());

                logger.debug("Registered the new poll {} \"{}\". pollNames={}, pollChoicesWithCase={}, keywordToPoll={}", uuid, title,
                        pollNames, pollChoicesWithCase, keywordToPoll);
                writePollToDisk(uuid);

                respond(event, chat, "Sondage créé ! Tu peux suivre les résultats sur : https://maddie480.ovh/twitch-polls/" + uuid);
            }
        } else if (keywordToPoll.containsKey(event.getMessage().trim().toLowerCase())) {
            String vote = event.getMessage().trim().toLowerCase();
            String userId = event.getMessageEvent().getUserId();

            for (String uuid : keywordToPoll.get(vote)) {
                whoVotedForWhat.get(uuid).put(userId, vote);
                logger.debug("Registered the new vote {} from {} for the poll {}. whoVotedForWhat={}", vote, userId, uuid, whoVotedForWhat);
                writePollToDisk(uuid);
            }
        }
    }

    private static void respond(ChannelMessageEvent event, TwitchChat chat, String message) {
        event.getMessageEvent().getMessageId().ifPresentOrElse(
                messageId -> chat.sendMessage(CHANNEL_NAME, message, event.getNonce(), messageId),
                () -> chat.sendMessage(CHANNEL_NAME, message)
        );
    }

    private static void writePollToDisk(String uuid) {
        JSONObject poll = new JSONObject();
        JSONObject answers = new JSONObject();

        poll.put("title", pollNames.get(uuid));
        poll.put("answers", answers);

        for (Map.Entry<String, String> answer : pollChoicesWithCase.get(uuid).entrySet()) {
            answers.put(answer.getValue(), whoVotedForWhat.get(uuid).values().stream()
                    .filter(v -> v.equals(answer.getKey())).count());
        }

        logger.debug("Writing poll {} to disk:\n{}", uuid, poll.toString(2));

        try (OutputStream os = Files.newOutputStream(Paths.get("/shared/temp/lnj-polls/" + uuid + ".json"))) {
            IOUtils.write(poll.toString(), os, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Could not save poll {}", uuid, e);
        }
    }
}
