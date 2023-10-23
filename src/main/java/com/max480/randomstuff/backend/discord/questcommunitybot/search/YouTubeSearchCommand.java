package com.max480.randomstuff.backend.discord.questcommunitybot.search;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import com.max480.randomstuff.backend.discord.questcommunitybot.Utils;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class YouTubeSearchCommand implements BotCommand {
    private static final Logger log = LoggerFactory.getLogger(YouTubeSearchCommand.class);

    private final Map<Long, List<String>> nextVideoIds = new HashMap<>();
    private final Map<Long, List<String>> nextVideoNames = new HashMap<>();


    @Override
    public String getCommandName() {
        return "youtube";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"termes de recherche*"};
    }

    @Override
    public String getShortHelp() {
        return "Lance une recherche sur YouTube";
    }

    @Override
    public String getFullHelp() {
        return "Pour obtenir les suivants (jusqu'au 5ème), cliquer sur la réaction :track_next:.";
    }

    @Override
    public boolean isAdminOnly() {
        return false;
    }

    @Override
    public boolean areParametersValid(String[] parameters) {
        return true;
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        String url = "https://www.googleapis.com/youtube/v3/search?part=snippet&q="
                + URLEncoder.encode(parameters[0], StandardCharsets.UTF_8) + "&maxResults=5&key=" + SecretConstants.YOUTUBE_API_KEY + "&type=video";

        log.debug("Requête à YouTube : {}", url);

        try (InputStream listeVideos = ConnectionUtils.openStreamWithTimeout(url)) {
            JSONObject items = new JSONObject(IOUtils.toString(listeVideos, StandardCharsets.UTF_8));
            log.debug("Réponse reçue : {}", items);

            List<String> videoIds = new ArrayList<>();
            List<String> videoNames = new ArrayList<>();
            ((JSONArray) items.get("items")).forEach(video -> {
                videoIds.add((String) ((JSONObject) ((JSONObject) video).get("id")).get("videoId"));

                try {
                    videoNames.add((String) ((JSONObject) ((JSONObject) video).get("snippet")).get("title"));
                } catch (Exception e) {
                    log.warn("Récupération du titre en erreur", e);
                    videoNames.add("[erreur]");
                }
            });

            if (videoIds.isEmpty() || videoNames.isEmpty()) {
                event.getChannel().sendMessage("Je n'ai pas trouvé de vidéo, ou il y a eu une erreur.").queue();
            } else {
                postYoutubeResultToChannel(event.getChannel(), videoIds, videoNames);
            }
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) {
        long messageId = event.getMessageIdLong();
        MessageChannel channel = event.getChannel();

        if (Utils.getUnicodeHexFromEmoji(event.getEmoji().getName()).equals("e28fad") && nextVideoIds.containsKey(messageId)) {
            postYoutubeResultToChannel(channel, nextVideoIds.get(messageId), nextVideoNames.get(messageId));
            nextVideoIds.remove(messageId);
            nextVideoNames.remove(messageId);
            if (channel instanceof TextChannel textChannel) textChannel.clearReactionsById(messageId).queue();
            log.debug("Cached video ids: {}, and video names: {}", nextVideoIds, nextVideoNames);
            return true;
        }

        return false;
    }

    private void postYoutubeResultToChannel(MessageChannel channel, List<String> videoIds, List<String> videoNames) {
        channel.sendMessage("J'ai trouvé : **" + videoNames.get(0) + "**\n:arrow_right: https://www.youtube.com/watch?v=" + videoIds.get(0))
                .queue(message -> {
                    videoIds.remove(0);
                    videoNames.remove(0);

                    if (videoIds.isEmpty() || videoNames.isEmpty()) {
                        nextVideoIds.remove(message.getIdLong());
                        nextVideoNames.remove(message.getIdLong());
                    } else {
                        message.addReaction(Utils.getEmojiFromUnicodeHex("e28fad")).queue();
                        nextVideoIds.put(message.getIdLong(), videoIds);
                        nextVideoNames.put(message.getIdLong(), videoNames);
                    }

                    Runnable clear = () -> {
                        nextVideoIds.remove(message.getIdLong());
                        nextVideoNames.remove(message.getIdLong());
                        log.debug("Cached video ids: {}, and video names: {}", nextVideoIds, nextVideoNames);
                    };

                    if (channel instanceof TextChannel) {
                        message.clearReactions().queueAfter(30, TimeUnit.MINUTES, success -> clear.run());
                    } else {
                        message.editMessage(message.getContentRaw() + "\n(expiré)").queueAfter(30, TimeUnit.MINUTES, success -> clear.run());
                    }

                    log.debug("Cached video ids: {}, and video names: {}", nextVideoIds, nextVideoNames);
                });
    }
}
