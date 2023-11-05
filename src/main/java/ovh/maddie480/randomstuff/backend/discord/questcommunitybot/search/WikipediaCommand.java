package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.search;

import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.Utils;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class WikipediaCommand implements BotCommand {
    private static final Logger log = LoggerFactory.getLogger(WikipediaCommand.class);

    private final Map<Long, List<String>> nextSearchLinks = new HashMap<>();
    private final Map<Long, List<String>> nextSearchNames = new HashMap<>();

    @Override
    public String getCommandName() {
        return "wikipedia";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"termes de recherche*"};
    }

    @Override
    public String getShortHelp() {
        return "Lance une recherche sur Wikipédia";
    }

    @Override
    public String getFullHelp() {
        return """
                Utilise le Wikipédia français (fr.wikipedia.org).
                Si aucun résultat exact n'est trouvé, les 5 premiers résultats sont récupérés et le premier est affiché. Une réaction :track_next: permettra d'afficher les résultats suivants.""";
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
        String baseUrl = "https://fr.wikipedia.org/w/index.php?search=" + URLEncoder.encode(parameters[0], StandardCharsets.UTF_8) + "&title=Spécial:Recherche&go=Continuer";
        HttpURLConnection urlConn = ConnectionUtils.openConnectionWithTimeout(baseUrl);
        urlConn.setInstanceFollowRedirects(false);

        urlConn.setDoInput(true);
        urlConn.connect();

        if (urlConn.getResponseCode() == 302) {
            // j'ai trouvé une page Wikipédia direct !
            String url = urlConn.getHeaderField("Location");
            Document doc = ConnectionUtils.jsoupGetWithRetry(url);

            try {
                String title = doc.title();
                String realUrl = doc.select("link[rel=\"canonical\"]").get(0).attr("href");

                event.getChannel().sendMessage("J'ai trouvé ce que tu cherchais : **" + title + "**\n:arrow_right: " + realUrl).queue();
            } catch (Exception e) {
                event.getChannel().sendMessage("J'ai trouvé ce que tu cherchais !\n:arrow_right: " + url).queue();
            }
        } else if (urlConn.getResponseCode() == 200) {
            Document doc = Jsoup.parse(ConnectionUtils.connectionToInputStream(urlConn), "UTF-8", baseUrl);

            Elements linksToResults = doc.select(".mw-search-result-heading > a");
            if (linksToResults.isEmpty()) {
                event.getChannel().sendMessage("J'ai rien trouvé, désolé.").queue();
            } else {
                List<String> names = new ArrayList<>();
                List<String> links = new ArrayList<>();
                for (int i = 1; i < linksToResults.size() && i < 5; i++) {
                    names.add(linksToResults.get(i).text().trim());
                    links.add("https://fr.wikipedia.org" + linksToResults.get(i).attr("href"));
                }

                event.getChannel().sendMessage("Je n'ai pas trouvé exactement ce que tu cherchais. En revanche, j'ai trouvé : **" +
                        linksToResults.get(0).text().trim() + "**\n:arrow_right: https://fr.wikipedia.org" + linksToResults.get(0).attr("href")).queue(message -> {

                    if (!names.isEmpty()) {
                        message.addReaction(Utils.getEmojiFromUnicodeHex("e28fad")).queue();
                        nextSearchLinks.put(message.getIdLong(), links);
                        nextSearchNames.put(message.getIdLong(), names);

                        Runnable clear = () -> {
                            nextSearchLinks.remove(message.getIdLong());
                            nextSearchNames.remove(message.getIdLong());
                            log.debug("Résultats Wikipédia en cache : {}, avec les noms : {}", nextSearchLinks, nextSearchNames);
                        };

                        message.clearReactions().queueAfter(30, TimeUnit.MINUTES, success -> clear.run());
                    }
                });
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
            log.debug("Résultats Wikipédia en cache : {}, avec les noms : {}", nextSearchLinks, nextSearchNames);

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

                    log.debug("Résultats Wikipédia en cache : {}, avec les noms : {}", nextSearchLinks, nextSearchNames);
                });
    }
}
