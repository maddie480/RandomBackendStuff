package com.max480.randomstuff.backend.discord.questcommunitybot.misc;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import com.max480.randomstuff.backend.discord.questcommunitybot.leveling.DistanceEdition;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.utils.FileUpload;
import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class YuGiOhCommand implements BotCommand {
    private static final Logger log = LoggerFactory.getLogger(YuGiOhCommand.class);

    @Override
    public String getCommandName() {
        return "yugioh";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"nom de la carte*"};
    }

    @Override
    public String getShortHelp() {
        return "Donne toutes les informations d'une carte Yu-Gi-Oh";
    }

    @Override
    public String getFullHelp() {
        return "Les informations sur les cartes viennent de ce site : https://www.db.yugioh-card.com/yugiohdb/card_search.action";
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
        Map<String, String> infos = chercherCarteParMotCle(parameters[0]);

        if (infos == null) {
            event.getChannel().sendMessage("Désolé, je n'ai pas trouvé cette carte.").queue();
        } else {
            // prendre les infos de base
            EmbedBuilder builder = new EmbedBuilder()
                    .setTitle(infos.get("name"), infos.get("url"));

            byte[] image = null;
            try {
                if (infos.containsKey("image")) {
                    HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(infos.get("image"));
                    connection.setDoInput(true);
                    connection.setRequestProperty("Referer", infos.get("url"));
                    connection.setRequestProperty("Accept-Language", "fr,fr-FR;q=0.8,en-US;q=0.5,en;q=0.3");
                    connection.connect();

                    image = IOUtils.toByteArray(connection);
                }
            } catch (Exception e) {
                log.error("Erreur en récupérant l'image", e);
            }

            infos.remove("name");
            infos.remove("url");
            infos.remove("image");

            infos.forEach((name, value) -> builder.addField(name, value, value.length() < 20));

            if (image != null) {
                event.getChannel().sendFiles(FileUpload.fromData(image, "carte.png")).queue();
            }
            event.getChannel().sendMessageEmbeds(builder.build()).queue();
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }

    private static Map<String, String> chercherCarteParMotCle(String motCle) throws IOException {
        String searchUrl = "https://www.db.yugioh-card.com/yugiohdb/card_search.action?ope=1&sess=1&keyword=" + URLEncoder.encode(motCle, StandardCharsets.UTF_8) + "&stype=1&ctype=&starfr=&starto=&pscalefr=&pscaleto=&linkmarkerfr=&linkmarkerto=&link_m=2&atkfr=&atkto=&deffr=&defto=&othercon=2";
        log.debug("Recherche de la carte \"{}\" via l'URL {}", motCle, searchUrl);
        Document pageDeRecherche = Jsoup.connect(searchUrl)
                .header("Accept-Language", "fr,fr-FR;q=0.8,en-US;q=0.5,en;q=0.3")
                .userAgent("Maddie-Random-Stuff-Backend/1.0.0 (+https://github.com/maddie480/RandomBackendStuff)")
                .get();

        Elements linksToResults = pageDeRecherche.select(".link_value");
        Elements names = pageDeRecherche.select(".box_card_name");

        if (linksToResults.isEmpty()) {
            log.debug("Pas trouvé");
            return null;
        } else {
            int distanceEdition = Integer.MAX_VALUE;
            String urlLaPlusProche = null;
            String nomLePlusProche = null;

            Iterator<Element> iteratorLinks = linksToResults.iterator();
            Iterator<Element> iteratorNames = names.iterator();
            while (iteratorLinks.hasNext()) {
                String link = iteratorLinks.next().attr("value");
                String name = iteratorNames.next().text();

                int dist = DistanceEdition.computeDistance(name, motCle);

                link = "https://www.db.yugioh-card.com" + link;
                log.debug("Trouvé @ {}, avec le nom {}, distance d'édition de {}", link, name, dist);

                if (distanceEdition > dist) {
                    distanceEdition = dist;
                    urlLaPlusProche = link;
                    nomLePlusProche = name;
                }
            }
            Map<String, String> infos = recupererInfosCarte(urlLaPlusProche);
            infos.put("url", urlLaPlusProche);
            infos.put("name", nomLePlusProche);
            return infos;
        }
    }

    private static Map<String, String> recupererInfosCarte(String url) throws IOException {
        Document pageDeLaCarte = Jsoup.connect(url)
                .header("Accept-Language", "fr,fr-FR;q=0.8,en-US;q=0.5,en;q=0.3")
                .userAgent("Maddie-Random-Stuff-Backend/1.0.0 (+https://github.com/maddie480/RandomBackendStuff)").get();

        Elements allDescriptions = pageDeLaCarte.select(".item_box, .item_box_text");
        Elements allDescriptionsTitles = pageDeLaCarte.select(".item_box_title");

        Map<String, String> mappedDescs = new LinkedHashMap<>();
        Iterator<Element> iteratorDescriptions = allDescriptions.iterator();
        Iterator<Element> iteratorDescriptionsTitles = allDescriptionsTitles.iterator();

        while (iteratorDescriptions.hasNext()) {
            String next = iteratorDescriptions.next().text();
            String nextTitle = iteratorDescriptionsTitles.next().text();

            if (next.startsWith(nextTitle)) {
                next = next.substring(nextTitle.length()).trim();
            }

            mappedDescs.put(nextTitle, next);
        }

        try {
            String pageString = pageDeLaCarte.toString();
            if (pageString.contains("$('#thumbnail_card_image_1').attr('src', '")) {
                pageString = pageString.substring(pageString.indexOf("$('#thumbnail_card_image_1').attr('src', '") + 42);
                pageString = pageString.substring(0, pageString.indexOf("'"));
                mappedDescs.put("image", "https://www.db.yugioh-card.com" + pageString);
            }
        } catch (Exception e) {
            log.error("Erreur lors de la récupération de l'URL image", e);
        }

        return mappedDescs;
    }
}
