package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.search;

import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.Utils;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
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

public class GoogleSearchCommand implements BotCommand {
    private static final Logger log = LoggerFactory.getLogger(GoogleSearchCommand.class);

    private final Map<Long, List<String>> nextSearchLinks = new HashMap<>();
    private final Map<Long, List<String>> nextSearchNames = new HashMap<>();


    @Override
    public String getCommandName() {
        return "google";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"termes de recherche*"};
    }

    @Override
    public String getShortHelp() {
        return "Lance une recherche sur Google";
    }

    @Override
    public String getFullHelp() {
        return """
                Pour obtenir les suivants (jusqu'au 5ème), cliquer sur la réaction :track_next:.

                A noter que le bot utilise un moteur de recherche personnalisé configuré pour laupok.fr, limité à 100 requêtes par jour (c'est le seul moyen d'avoir accès à l'API de Google), ce qui peut expliquer que certains résultats soient peu pertinents.""";
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
        String url = "https://www.googleapis.com/customsearch/v1?key=" + SecretConstants.GOOGLE_CUSTOM_SEARCH_API_KEY + "&gl=fr&hl=fr&num=5&cx=007315914358666863061:e0qfizftdqs" +
                "&q=" + URLEncoder.encode(parameters[0], StandardCharsets.UTF_8);

        log.debug("Requête à Google : {}", url);

        try (InputStream listeResultats = ConnectionUtils.openStreamWithTimeout(url)) {
            JSONObject items = new JSONObject(IOUtils.toString(listeResultats, StandardCharsets.UTF_8));
            log.debug("Réponse reçue : {}", items);

            List<String> resultLinks = new ArrayList<>();
            List<String> resultNames = new ArrayList<>();
            ((JSONArray) items.get("items")).forEach(video -> {
                resultLinks.add((String) ((JSONObject) video).get("link"));
                resultNames.add((String) ((JSONObject) video).get("title"));
            });

            if (resultLinks.isEmpty() || resultNames.isEmpty()) {
                event.getChannel().sendMessage("Je n'ai pas trouvé de résultat, ou il y a eu une erreur.").queue();
            } else {
                postGoogleResultToChannel(event.getChannel(), resultLinks, resultNames);
            }
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) {
        long messageId = event.getMessageIdLong();
        MessageChannel channel = event.getChannel();

        if (Utils.getUnicodeHexFromEmoji(event.getEmoji().getName()).equals("e28fad") && nextSearchLinks.containsKey(messageId)) {
            postGoogleResultToChannel(channel, nextSearchLinks.get(messageId), nextSearchNames.get(messageId));
            nextSearchLinks.remove(messageId);
            nextSearchNames.remove(messageId);
            if (channel instanceof TextChannel textChannel) textChannel.clearReactionsById(messageId).queue();
            log.debug("Résultats Google en cache : {}, avec les noms : {}", nextSearchLinks, nextSearchNames);

            return true;
        }

        return false;
    }

    private void postGoogleResultToChannel(MessageChannel channel, List<String> searchLinks, List<String> searchNames) {
        channel.sendMessage("J'ai trouvé : **" + searchNames.get(0) + "**\n:arrow_right: " + searchLinks.get(0))
                .queue(message -> {
                    searchLinks.remove(0);
                    searchNames.remove(0);
                    if (searchLinks.isEmpty() || searchNames.isEmpty()) {
                        nextSearchLinks.remove(message.getIdLong());
                        nextSearchNames.remove(message.getIdLong());
                    } else {
                        message.addReaction(Utils.getEmojiFromUnicodeHex("e28fad")).queue();
                        nextSearchLinks.put(message.getIdLong(), searchLinks);
                        nextSearchNames.put(message.getIdLong(), searchNames);

                        Runnable clear = () -> {
                            nextSearchLinks.remove(message.getIdLong());
                            nextSearchNames.remove(message.getIdLong());
                            log.debug("Résultats Google en cache : {}, avec les noms : {}", nextSearchLinks, nextSearchNames);
                        };

                        if (channel instanceof TextChannel)
                            message.clearReactions().queueAfter(30, TimeUnit.MINUTES, success -> clear.run());
                        else
                            message.editMessage(message.getContentRaw() + "\n(expiré)").queueAfter(30, TimeUnit.MINUTES, success -> clear.run());
                    }

                    log.debug("Résultats Google en cache : {}, avec les noms : {}", nextSearchLinks, nextSearchNames);
                });
    }
}
