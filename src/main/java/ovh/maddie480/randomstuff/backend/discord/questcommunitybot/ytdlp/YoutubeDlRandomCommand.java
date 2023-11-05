package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.ytdlp;

import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

public class YoutubeDlRandomCommand implements BotCommand {
    private static final Logger log = LoggerFactory.getLogger(YoutubeDlRandomCommand.class);

    @Override
    public String getCommandName() {
        return "youtube_dl_random";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Télécharge une vidéo aléatoire parmi des playlists pré-sélectionnées";
    }

    @Override
    public String getFullHelp() {
        return "Les vidéos sont téléchargées en 720p et les tronçons sponsorisés sont supprimés avec SponsorBlock.";
    }

    @Override
    public boolean isAdminOnly() {
        return true;
    }

    @Override
    public boolean areParametersValid(String[] parameters) {
        return true;
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        YoutubeDlCommand.handleYoutubeDL(event.getMessage(), new String[]{youtubeVideoRNG()}, true, false);
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }

    public static String youtubeVideoRNG() throws IOException {
        Map<String, Double> playlists = new HashMap<>();
        for (Map.Entry<String, String> item : SecretConstants.YOUTUBE_PRESELECTED_PLAYLISTS.entrySet()) {
            playlists.put(item.getKey(), Double.parseDouble(item.getValue()));
        }

        List<String> ponderatedStuff = new ArrayList<>();
        int multiplier = (int) (1 / playlists.values().stream().mapToDouble(a -> a).min().orElse(1));

        for (Map.Entry<String, Double> entry : playlists.entrySet()) {
            for (int i = 0; i < entry.getValue() * multiplier; i++) {
                ponderatedStuff.add(entry.getKey());
            }
        }

        String channel = ponderatedStuff.get((int) (Math.random() * ponderatedStuff.size()));

        log.debug("Picked channel or playlist: {}", channel);

        String nextPageToken = null;
        List<String> videos = new ArrayList<>();

        do {
            String url = "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&playlistId=" + channel + "&maxResults=10&key=" + SecretConstants.YOUTUBE_API_KEY;
            if (nextPageToken != null) {
                url += "&pageToken=" + nextPageToken;
            }

            String actualUrl = url;
            JSONObject items = ConnectionUtils.runWithRetry(() -> {
                InputStream listeVideos = ConnectionUtils.openStreamWithTimeout(actualUrl);
                return new JSONObject(IOUtils.toString(listeVideos, UTF_8));
            });

            items.getJSONArray("items").forEach(item ->
                    videos.add(((JSONObject) item).getJSONObject("snippet").getJSONObject("resourceId").getString("videoId")));

            if (items.has("nextPageToken")) {
                nextPageToken = items.getString("nextPageToken");
            } else {
                nextPageToken = null;
            }
        } while (nextPageToken != null);

        return "https://youtube.com/watch?v=" + videos.get((int) (Math.random() * videos.size()));
    }
}
