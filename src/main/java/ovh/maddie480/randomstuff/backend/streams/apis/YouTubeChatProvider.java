package ovh.maddie480.randomstuff.backend.streams.apis;

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.DataStoreFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.streams.features.CustomEmotes;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A provider that connects to the chat of the (hopefully only) video currently live or upcoming on
 * the @LesNavetsJouables YouTube channel.
 */
public class YouTubeChatProvider implements IChatProvider<String> {
    private static final Logger log = LoggerFactory.getLogger(YouTubeChatProvider.class);

    private static final String CHANNEL_ID = "UCeYyIN2Z1H2R4gc-mQJyrsA";

    private Credential credential;
    private String liveChatId;

    private Map<String, String> allEmotes;

    private Map<String, String> fixedMessages;
    private long lastTimedMessagePostedAt = 0;
    private int lastTimedMessagePosted = 0;
    private int messageCountSinceLastTimedPost = 0;

    private boolean readMessagesLoopActive = true;

    private final Runnable givingUpAction;

    public YouTubeChatProvider(Runnable givingUpAction) {
        this.givingUpAction = givingUpAction;
    }

    @Override
    public void connect(Consumer<ChatMessage<String>> messageListener) throws IOException {
        DataStoreFactory dataStoreFactory = new FileDataStoreFactory(new File("youtube_api_credentials"));
        AuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                new NetHttpTransport(), GsonFactory.getDefaultInstance(),
                SecretConstants.YOUTUBE_LNJ_BOT_CLIENT_ID, SecretConstants.YOUTUBE_LNJ_BOT_CLIENT_SECRET,
                Collections.singleton("https://www.googleapis.com/auth/youtube"))
                .setDataStoreFactory(dataStoreFactory).setAccessType("offline").build();

        credential = flow.loadCredential("default");

        if (credential == null) {
            Path codeFile = Paths.get("youtube_auth_code.txt");
            if (!Files.exists(codeFile)) {
                throw new IOException("Cannot load credential, go to " + flow.newAuthorizationUrl().setRedirectUri("https://maddie480.ovh").build() + " and save code to youtube_auth_code.txt");
            }
            TokenResponse token = flow.newTokenRequest(Files.readString(codeFile, StandardCharsets.UTF_8))
                    .setRedirectUri("https://maddie480.ovh").execute();
            Files.delete(codeFile);
            if (token.getRefreshToken() == null) throw new IOException("We got no refresh token! WTF?");
            credential = flow.createAndStoreCredential(token, "default");
            log.debug("Connected using authorization code! Expires in {} seconds", credential.getExpiresInSeconds());
        } else {
            log.debug("Connected using stored credentials! Expires in {} seconds", credential.getExpiresInSeconds());
        }

        String liveStreamVideoId = getLiveStreamVideoId();
        liveChatId = getLiveChatId(liveStreamVideoId);
        log.debug("The ID of the chat of the current YouTube live stream is {}", liveChatId);

        allEmotes = YouTubeEmoteDatabase.getEmotes();
        log.debug("Got emotes: {}", allEmotes);

        fixedMessages = getMoobotCommands();

        runReadMessagesLoop(messageListener);
    }

    @Override
    public void disconnect() {
        readMessagesLoopActive = false;
    }

    private String getAccessToken() throws IOException {
        if (credential.getExpiresInSeconds() == null || credential.getExpiresInSeconds() < 10) {
            log.debug("Credential expires in {} seconds, refreshing...", credential.getExpiresInSeconds());
            if (!credential.refreshToken()) {
                throw new IOException("Refreshing token failed!");
            }
            log.debug("Token refreshed! Now expires in {} seconds", credential.getExpiresInSeconds());
        }

        return credential.getAccessToken();
    }

    private String getLiveStreamVideoId() throws IOException {
        for (String type : Arrays.asList("live", "upcoming")) {
            HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://www.googleapis.com/youtube/v3/search?channelId=" + CHANNEL_ID + "&part=snippet&type=video&eventType=" + type);
            connection.setRequestProperty("Authorization", "Bearer " + getAccessToken());

            try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
                JSONObject response = new JSONObject(new JSONTokener(is));
                if (response.getJSONArray("items").isEmpty()) continue;
                return response.getJSONArray("items").getJSONObject(0).getJSONObject("id").getString("videoId");
            }
        }

        throw new IOException("Could not find an upcoming or live stream!");
    }

    private String getLiveChatId(String videoId) throws IOException {
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://www.googleapis.com/youtube/v3/videos?id=" + videoId + "&part=liveStreamingDetails");
        connection.setRequestProperty("Authorization", "Bearer " + getAccessToken());

        try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
            JSONObject response = new JSONObject(new JSONTokener(is));
            return response.getJSONArray("items").getJSONObject(0).getJSONObject("liveStreamingDetails").getString("activeLiveChatId");
        }
    }

    private Map<String, String> getMoobotCommands() throws IOException {
        Map<String, String> commands = new HashMap<>();

        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://api.moo.bot/1/channel/public/commands/list?channel=431608356")) {
            JSONObject response = new JSONObject(new JSONTokener(is));

            for (int i = 0; i < response.getJSONArray("list").length(); i++) {
                JSONObject command = response.getJSONArray("list").getJSONObject(i);
                if (!"custom".equals(command.getString("type"))) continue;
                commands.put(command.getString("identifier"), command.getString("response"));
            }

            log.debug("Retrieved Moobot commands: {}", commands);
            return commands;
        }
    }

    private void runReadMessagesLoop(Consumer<ChatMessage<String>> messageListener) {
        new Thread("YouTube Chat Reader") {
            @Override
            public void run() {
                int failsInARow = 0;
                String pageToken = null;

                while (readMessagesLoopActive) {
                    try {
                        MessageCheckResult result = readMessages(messageListener, pageToken);
                        pageToken = result.pageToken();
                        Thread.sleep(Math.max(result.suggestedSleepTime(), 10000));
                        sendPeriodicMessageIfNecessary();
                        failsInARow = 0;
                    } catch (Exception e) {
                        log.error("Error while checking chat, consecutive failure #{}", failsInARow, e);

                        failsInARow++;
                        if (failsInARow > 10) {
                            log.error("Aborting YouTube chat!");
                            givingUpAction.run();
                            break;
                        }

                        try {
                            Thread.sleep(10000);
                        } catch (InterruptedException ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                }

                log.info("YouTube read message loop stopping!");
            }
        }.start();
    }

    private record MessageCheckResult(int suggestedSleepTime, String pageToken) {
    }

    private MessageCheckResult readMessages(Consumer<ChatMessage<String>> messageListener, String pageToken) throws IOException {
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://www.googleapis.com/youtube/v3/liveChat/messages?"
                + "liveChatId=" + liveChatId + "&part=id,snippet,authorDetails&maxResults=200"
                + (pageToken == null ? "" : "&pageToken=" + pageToken));
        connection.setRequestProperty("Authorization", "Bearer " + getAccessToken());

        try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
            JSONObject response = new JSONObject(new JSONTokener(is));

            for (int i = 0; i < response.getJSONArray("items").length() && pageToken != null; i++) {
                JSONObject message = response.getJSONArray("items").getJSONObject(i);

                if (!"textMessageEvent".equals(message.getJSONObject("snippet").getString("type"))) {
                    log.debug("Skipping message of type " + message.getJSONObject("snippet").getString("type"));
                    continue;
                }

                String messageText = message.getJSONObject("snippet").getJSONObject("textMessageDetails").getString("messageText");

                ChatMessage<String> chatMessage = new ChatMessage<>(
                        message.getJSONObject("authorDetails").getString("channelId"),
                        trimAtIfNecessary(message.getJSONObject("authorDetails").getString("displayName")),
                        message.getString("id"),
                        messageText,
                        CHANNEL_ID.equals(message.getJSONObject("authorDetails").getString("channelId")),
                        Collections.emptyList(),
                        CustomEmotes.findEmotes(messageText, allEmotes),
                        this
                );

                messageListener.accept(chatMessage);
                messageCountSinceLastTimedPost++;
            }

            return new MessageCheckResult(response.getInt("pollingIntervalMillis"), response.getString("nextPageToken"));
        }
    }

    private String trimAtIfNecessary(String displayName) {
        return displayName.startsWith("@") ? displayName.substring(1) : displayName;
    }

    private void sendPeriodicMessageIfNecessary() {
        if (System.currentTimeMillis() - lastTimedMessagePostedAt > 1_200_000L // 20 minutes
                && messageCountSinceLastTimedPost >= 10) {

            lastTimedMessagePosted++;
            lastTimedMessagePosted %= 1;

            String message = switch (lastTimedMessagePosted) {
                case 0 ->
                        "Tapez !clip pour créer automatiquement un clip des 30 dernières sec de stream ! Merci Maddie pour la création de cette commande !";
                default -> "cpt";
            };

            sendMessage(message);

            lastTimedMessagePostedAt = System.currentTimeMillis();
            messageCountSinceLastTimedPost = 0;
        }
    }

    @Override
    public void sendMessage(String contents) {
        try {
            if (contents.length() > 200) contents = contents.substring(0, 197) + "...";

            JSONObject text = new JSONObject();
            text.put("messageText", contents);

            JSONObject snippet = new JSONObject();
            snippet.put("type", "textMessageEvent");
            snippet.put("liveChatId", liveChatId);
            snippet.put("textMessageDetails", text);

            JSONObject request = new JSONObject();
            request.put("snippet", snippet);

            HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://www.googleapis.com/youtube/v3/liveChat/messages?part=snippet");
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + getAccessToken());

            try (OutputStream os = connection.getOutputStream();
                 OutputStreamWriter bw = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {

                request.write(bw);
            }

            if (connection.getResponseCode() != 200) {
                throw new IOException("Google responded with code: " + connection.getResponseCode());
            }
            connection.disconnect();
        } catch (IOException e) {
            log.error("Could not send message", e);
        }
    }

    @Override
    public void respondTo(ChatMessage<String> message, String response) {
        // YouTube doesn't have replies, it does have chat mentions however
        sendMessage("@" + message.messageSenderName() + " " + response);
    }

    public void respondToFixedCommand(ChatMessage<?> message, String command) {
        if (!fixedMessages.containsKey(command)) {
            return;
        }

        String response = fixedMessages.get(command);

        if (response.contains("<username>") || response.contains("<twitch.mentioned>")) {
            // do not respond because the text already contains a mention
            sendMessage(response
                    .replace("<username>", "@" + message.messageSenderName())
                    .replace("<twitch.mentioned>", "@" + message.messageSenderName()));

        } else {
            // respond to the user with a mention
            message.respond(response);
        }
    }
}
