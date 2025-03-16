package ovh.maddie480.randomstuff.backend.discord.crontabs;

import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.DiscardableJDA;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class TwitchUpdateChecker {
    private static final Logger log = LoggerFactory.getLogger(TwitchUpdateChecker.class);

    private Map<String, String> streamingTitles = new HashMap<>();
    private Map<String, String> streamingGames = new HashMap<>();

    public void checkForUpdates() throws IOException {
        try (DiscardableJDA client = new DiscardableJDA(SecretConstants.QUEST_COMMUNITY_BOT_TOKEN)) {
            checkForUpdates(client.getTextChannelById(551822297573490749L));
        }
    }

    private void checkForUpdates(TextChannel target) throws IOException {
        Path stateFile = Paths.get("twitch_update_checker_state.ser");

        // load state
        if (Files.exists(stateFile)) {
            try (ObjectInputStream is = new ObjectInputStream(Files.newInputStream(stateFile))) {
                streamingTitles = (Map<String, String>) is.readObject();
                streamingGames = (Map<String, String>) is.readObject();
            } catch (ClassNotFoundException e) {
                throw new IOException(e);
            }
        }

        String accessToken;
        {
            JSONObject body = new JSONObject();
            body.put("client_id", SecretConstants.TWITCH_CLIENT_ID);
            body.put("client_secret", SecretConstants.TWITCH_CLIENT_SECRET);
            body.put("grant_type", "client_credentials");

            HttpURLConnection con = ConnectionUtils.openConnectionWithTimeout("https://id.twitch.tv/oauth2/token");
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = con.getOutputStream();
                 OutputStreamWriter bw = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {

                body.write(bw);
            }

            try (InputStream is = ConnectionUtils.connectionToInputStream(con)) {
                JSONObject result = new JSONObject(new JSONTokener(is));
                accessToken = result.getString("access_token");
            }
        }

        try (BufferedReader br = new BufferedReader(new FileReader("followed_twitch_channels.txt"))) {
            String channelId;
            while ((channelId = br.readLine()) != null) {
                HttpURLConnection con = ConnectionUtils.openConnectionWithTimeout("https://api.twitch.tv/helix/streams?first=1&user_login=" + channelId);
                con.setRequestProperty("Authorization", "Bearer " + accessToken);
                con.setRequestProperty("Client-Id", SecretConstants.TWITCH_CLIENT_ID);

                try (InputStream is = ConnectionUtils.connectionToInputStream(con)) {
                    JSONObject result = new JSONObject(new JSONTokener(is));

                    if (result.getJSONArray("data").isEmpty()) {
                        log.debug("{} est hors-ligne", channelId);

                        // streamer is not live
                        if (streamingTitles.containsKey(channelId)) {
                            sendMessage(target, "**" + channelId + "** n'est plus en direct.");
                            streamingTitles.remove(channelId);
                            streamingGames.remove(channelId);
                        }
                    } else {
                        String gameName = result.getJSONArray("data").getJSONObject(0).getString("game_name");
                        String streamTitle = result.getJSONArray("data").getJSONObject(0).getString("title");

                        log.debug("{} est en ligne sur {}, titre du stream = {}", channelId, gameName, streamTitle);

                        // streamer is live
                        if (streamingTitles.containsKey(channelId)) {
                            if (!streamingTitles.get(channelId).equals(streamTitle)
                                    || !streamingGames.get(channelId).equals(gameName)) {

                                sendMessage(target, "**" + channelId + "** a changé le sujet de son stream : le stream s'appelle maintenant \"**" + streamTitle + "**\" " +
                                        "sur le jeu **" + gameName + "**.\n:arrow_right: <https://twitch.tv/" + channelId + ">");
                            }
                        } else {
                            sendMessage(target, "**" + channelId + "** est en direct sur le jeu **" + gameName + "** ! Le nom du stream est \"**" + streamTitle + "**\"." +
                                    "\n:arrow_right: <https://twitch.tv/" + channelId + ">");
                        }

                        streamingTitles.put(channelId, streamTitle);
                        streamingGames.put(channelId, gameName);
                    }
                }
            }
        }

        // save state
        try (ObjectOutputStream os = new ObjectOutputStream(Files.newOutputStream(stateFile))) {
            os.writeObject(streamingTitles);
            os.writeObject(streamingGames);
        }
    }

    private static void sendMessage(TextChannel target, String message) {
        target.sendMessage(message).complete();
    }
}
