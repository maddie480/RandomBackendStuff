package ovh.maddie480.randomstuff.backend.streams.apis;

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.twitch4j.chat.TwitchChat;
import com.github.twitch4j.chat.TwitchChatBuilder;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.github.twitch4j.helix.TwitchHelix;
import com.github.twitch4j.helix.TwitchHelixBuilder;
import com.github.twitch4j.helix.domain.UserList;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A provider that connects to the lesnavetsjouables chat on Twitch.
 */
public class TwitchChatProvider implements IChatProvider<TwitchMessageID> {
    private static final Logger logger = LoggerFactory.getLogger(TwitchChatProvider.class);

    private static final String CHANNEL_NAME = "lesnavetsjouables";

    private String channelId;
    private TwitchChat chat;
    private TwitchHelix helix;
    private String accessToken;

    @Override
    public void connect(Consumer<ChatMessage<TwitchMessageID>> messageListener) throws IOException {
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

        chat = TwitchChatBuilder.builder()
                .withChatAccount(new OAuth2Credential("twitch", accessToken))
                .build();

        chat.joinChannel(CHANNEL_NAME);

        // start listening for messages
        helix = TwitchHelixBuilder.builder().build();
        UserList users = helix.getUsers(accessToken, null, Collections.singletonList(CHANNEL_NAME)).execute();
        channelId = users.getUsers().get(0).getId();
        logger.debug("Channel ID retrieved for {}: {}", CHANNEL_NAME, channelId);
        chat.getEventManager().onEvent(ChannelMessageEvent.class, event -> messageListener.accept(new ChatMessage<>(
                event.getMessageEvent().getUserId(), event.getMessageEvent().getUserName(),
                new TwitchMessageID(event.getMessageEvent().getMessageId(), event.getNonce()),
                event.getMessage(), channelId.equals(event.getMessageEvent().getUserId()), this)));
    }

    @Override
    public void sendMessage(String contents) {
        chat.sendMessage(CHANNEL_NAME, contents);
    }

    public void makeClip(ChatMessage<?> triggeredByMessage) {
        CompletableFuture
                .supplyAsync(() -> helix.createClip(accessToken, channelId, false).execute())
                .thenAcceptAsync(clip -> {
                    String clipLink = clip.getData().get(0).getEditUrl();
                    if (clipLink.endsWith("/edit")) clipLink = clipLink.substring(0, clipLink.length() - 5);
                    triggeredByMessage.respond("Le clip a été créé : " + clipLink);
                })
                .exceptionallyAsync(boom -> {
                    logger.error("Could not create clip", boom);
                    triggeredByMessage.respond("Quelque chose n'a pas fonctionné, désolé. :/");
                    return null;
                });
    }

    @Override
    public void respondTo(ChatMessage<TwitchMessageID> message, String response) {
        message.messageId().messageId().ifPresentOrElse(
                messageId -> chat.sendMessage(CHANNEL_NAME, response, message.messageId().nonce(), messageId),
                () -> chat.sendMessage(CHANNEL_NAME, response));
    }
}
