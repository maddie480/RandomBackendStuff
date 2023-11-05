package ovh.maddie480.randomstuff.backend.twitch;

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.twitch4j.chat.TwitchChat;
import com.github.twitch4j.chat.TwitchChatBuilder;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.github.twitch4j.helix.TwitchHelix;
import com.github.twitch4j.helix.TwitchHelixBuilder;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringEscapeUtils;
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
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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

    private static final Path lnjPollPath = Paths.get("/shared/lnj-poll.json");

    private static class LNJPoll {
        private final long id;
        private final String name;
        private final Map<String, String> answersWithCase;
        private final Map<String, String> answersByUser;

        public LNJPoll(String name, Set<String> answers) {
            this.id = System.currentTimeMillis();
            this.name = name;
            this.answersWithCase = new HashMap<>();
            for (String answer : answers) {
                this.answersWithCase.put(answer.toLowerCase(), answer);
            }
            this.answersByUser = new HashMap<>();
        }

        public LNJPoll(JSONObject json) {
            id = json.getLong("id");
            name = json.getString("name");
            answersWithCase = toMap(json.getJSONObject("answersWithCase"));
            answersByUser = toMap(json.getJSONObject("answersByUser"));
        }

        private static Map<String, String> toMap(JSONObject json) {
            Map<String, String> result = new HashMap<>();
            for (String key : json.keySet()) {
                result.put(key, json.getString(key));
            }
            return result;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("answersWithCase", answersWithCase);
            o.put("answersByUser", answersByUser);
            return o;
        }

        public boolean voteFor(String userId, String vote) {
            vote = vote.toLowerCase();

            if (answersWithCase.containsKey(vote)) {
                answersByUser.put(userId, vote);
                return true;
            } else {
                return false;
            }
        }
    }

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
        int expiresIn;

        try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
            JSONObject response = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
            accessToken = response.getString("access_token");
            refreshToken = response.getString("refresh_token");
            expiresIn = response.getInt("expires_in");
            logger.debug("Got a new token that expires in {} seconds ({})!", expiresIn, ZonedDateTime.now().plusSeconds(expiresIn));
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

        new Thread("LNJ Bot Scheduled Shutdown Thread") {
            @Override
            public void run() {
                try {
                    Thread.sleep(expiresIn * 1000L);
                    System.exit(0);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }.start();

        logger.debug("Startup finished!");
    }

    private static void handleChatMessage(ChannelMessageEvent event, TwitchChat chat, TwitchHelix helix, String accessToken) {
        LNJPoll poll;
        try (InputStream is = Files.newInputStream(lnjPollPath)) {
            poll = new LNJPoll(new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            logger.error("Could not load LNJ Poll", e);
            return;
        }

        if (event.getMessage().trim().toLowerCase(Locale.ROOT).matches("^! *clip$")) {
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
                String title = command.get(1);
                Set<String> choices = command.stream().skip(2).collect(Collectors.toSet());

                try (OutputStream os = Files.newOutputStream(lnjPollPath)) {
                    IOUtils.write(new LNJPoll(title, choices).toJson().toString(), os, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    logger.error("Could not save new LNJ Poll", e);
                    return;
                }

                logger.debug("New poll created: \"{}\", with choices {}", title, choices);
                respond(event, chat, "Sondage créé !");
            }
        } else if (poll.voteFor(event.getMessageEvent().getUserId(), event.getMessage())) {
            logger.debug("New vote received on poll: {} (ID {}) voted {}", event.getMessageEvent().getUserName(),
                    event.getMessageEvent().getUserId(), event.getMessage());

            try (OutputStream os = Files.newOutputStream(lnjPollPath)) {
                IOUtils.write(poll.toJson().toString(), os, StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.error("Could not save LNJ Poll vote", e);
            }
        }
    }

    private static void respond(ChannelMessageEvent event, TwitchChat chat, String message) {
        event.getMessageEvent().getMessageId().ifPresentOrElse(
                messageId -> chat.sendMessage(CHANNEL_NAME, message, event.getNonce(), messageId),
                () -> chat.sendMessage(CHANNEL_NAME, message)
        );
    }

    public static void healthCheck() throws IOException {
        String title;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/twitch-poll.json")) {
            JSONObject o = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
            title = o.getString("name");
        }

        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/twitch-poll")) {
            if (!IOUtils.toString(is, StandardCharsets.UTF_8).contains(StringEscapeUtils.escapeHtml4(title))) {
                throw new IOException("Poll title wasn't found on the page!");
            }
        }
    }
}
