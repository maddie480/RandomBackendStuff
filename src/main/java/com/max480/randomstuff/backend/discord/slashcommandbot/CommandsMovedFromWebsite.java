package com.max480.randomstuff.backend.discord.slashcommandbot;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.text.StringEscapeUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Les commandes qui étaient sur max480-random-stuff.appspot.com, mais qui n'avaient en fait aucune
 * raison d'être ailleurs que dans le bot.
 */
public class CommandsMovedFromWebsite {
    private static final Logger log = LoggerFactory.getLogger(CommandsMovedFromWebsite.class);

    public String tryRunCommand(String command, String parameter, String username) {
        return switch (command) {
            case "/random" -> commandRandom(parameter);
            case "/chucknorris" -> commandChuckNorris(parameter == null || !parameter.equals("all"));
            case "/toplyrics" -> commandTopLyrics(false);
            case "/patoche" -> commandTopLyrics(true);
            case "/joiesducode" -> commandJoiesDuCode();
            case "/coronavirus" -> commandCoronavirus();
            case "/fakename" -> commandFakeName();
            case "/tendancesyoutube" -> commandTendancesYoutube();
            case "/putaclic" -> commandPutaclic();
            case "/randomparrot" -> commandRandomParrot();
            case "/monkeyuser" -> commandMonkeyUser();
            case "/xkcd" -> commandXkcd();
            case "/infopipo" -> commandInfoPipo();
            case "/eddy" -> commandEddy();
            case "/pipo" -> commandPipo(parameter, username);
            case "/weekend" -> commandWeekend(username);
            case "/noel" -> ChristmasFilmGenerator.generateStory();
            case "/jcvd" -> commandJCVD();
            case "/bientotleweekend" -> commandBientotLeWeekend();
            case "/languedebois" -> commandLangueDeBois();
            case "/vacances" -> commandVacances(parameter, username);
            case "/tableflip" ->
                    "{\"response_type\": \"in_channel\", \"text\": \"(\\u256f\\u00b0\\u25a1\\u00b0\\uff09\\u256f\\ufe35 \\u253b\\u2501\\u253b\"}";
            case "/ckc" ->
                    "{\"response_type\": \"in_channel\", \"text\": \"https://storage.googleapis.com/integ-de-covid/estcequeckc.html\"}";
            default -> null;
        };
    }

    private String commandRandom(String numberParameter) {
        JSONObject jsonObject = new JSONObject();

        if (numberParameter == null || numberParameter.isEmpty()) {
            // no number passed
            jsonObject.put("text", "Utilisation : `/random [nombre]`");
        } else {
            try {
                int nb = Integer.parseInt(numberParameter);
                if (nb <= 0) {
                    // negative number passed
                    jsonObject.put("text", "Avec un nombre strictement positif ça marchera mieux !");
                } else {
                    // all good!
                    jsonObject.put("response_type", "in_channel");
                    jsonObject.put("text", "[Tirage entre 1 et " + nb + "] J'ai tiré : " + ((int) (Math.random() * nb + 1)));
                }
            } catch (NumberFormatException e) {
                // what was passed is not a number
                jsonObject.put("text", "Avec un nombre, ça marchera mieux !");
            }
        }
        return jsonObject.toString();
    }

    private String commandChuckNorris(boolean onlyGood) {
        JSONObject jsonObject = new JSONObject();

        try {
            if (onlyGood) {
                while (!jsonObject.has("text")) {
                    // only more than 7/10 mark!
                    Document facts = ConnectionUtils.jsoupGetWithRetry("http://chucknorrisfacts.fr/facts/random");

                    List<String> text = facts.select(".card-text").stream().map(Element::text).toList();
                    List<Float> marks = facts.select(".card-footer span").stream()
                            .map(Element::text)
                            .map(mark -> {
                                // the format is (XXX/10)
                                mark = mark.substring(1, mark.length() - 4);
                                return Float.parseFloat(mark);
                            })
                            .toList();

                    for (int i = 0; i < marks.size(); i++) {
                        if (marks.get(i) >= 7) {
                            log.info("Fact selected: \"" + text.get(i) + "\" with mark " + marks.get(i));

                            jsonObject.put("text", text.get(i));
                            break;
                        }
                    }
                }
            } else {
                // any random fact will do.
                jsonObject.put("text", ConnectionUtils.jsoupGetWithRetry("http://chucknorrisfacts.fr/facts/random")
                        .select(".card-text")
                        .get(0).text());
            }

            jsonObject.put("response_type", "in_channel");
        } catch (Exception e) {
            log.error("Problem with /chucknorris", e);
            jsonObject.put("text", "Désolé, l'appel à l'API de chucknorrisfacts.fr a échoué. :ckc:");
        }

        return jsonObject.toString();
    }

    private String commandTopLyrics(boolean patoche) {
        JSONObject jsonObject = new JSONObject();

        try {
            // fetch song list
            Pair<List<String>, List<String>> results;
            if (patoche) {
                results = fetchPatocheList();
            } else {
                results = fetchSongList();
            }
            List<String> songTitles = results.getLeft();
            List<String> songUrls = results.getRight();

            // pick one at random
            int random = (int) (Math.random() * songTitles.size());
            String titre = songTitles.get(random);
            String url = songUrls.get(random);

            Document lyricsPage = ConnectionUtils.jsoupGetWithRetry(url);

            Elements songText = lyricsPage.select(".song-text div");
            String lyricsList = songText.stream()
                    .map(div -> div.childNodes().stream()
                            .map(element -> {
                                if (element instanceof TextNode) {
                                    return ((TextNode) element).text().trim();
                                } else if (element instanceof Element && ((Element) element).tagName().equals("br")) {
                                    return "\n";
                                }
                                return "";
                            })
                            .collect(Collectors.joining()))
                    .collect(Collectors.joining());

            // split the song in blocks like they are on the site
            while (lyricsList.contains("\n\n\n")) lyricsList = lyricsList.replace("\n\n\n", "\n\n");
            String[] lyricsSplit = lyricsList.split("\n\n");

            List<String> parolesFinal = new ArrayList<>();
            for (String parole : lyricsSplit) {
                List<String> lyricsParts = new ArrayList<>();
                lyricsParts.add(parole);

                // piece out the song in 5-line parts maximum, by cutting blocks in half until we're good
                while (lyricsParts.stream().anyMatch(parolePart -> parolePart.split("\n").length > 5)) {
                    List<String> newLyricsParts = new ArrayList<>();
                    for (String lyricsPart : lyricsParts) {
                        if (lyricsPart.split("\n").length > 5) {
                            // split in half
                            String[] lines = lyricsPart.split("\n");
                            StringBuilder firstBlock = new StringBuilder();
                            StringBuilder secondBlock = new StringBuilder();

                            int count = 0;
                            for (String line : lines) {
                                if (count++ < lines.length / 2) firstBlock.append(line).append("\n");
                                else secondBlock.append(line).append("\n");
                            }

                            newLyricsParts.add(firstBlock.toString().trim());
                            newLyricsParts.add(secondBlock.toString().trim());
                        } else {
                            newLyricsParts.add(lyricsPart);
                        }
                    }
                    lyricsParts = newLyricsParts;
                }

                parolesFinal.addAll(lyricsParts);
            }

            // then we just take a block at random and send it out. :p
            String randomLyrics = parolesFinal.get((int) (Math.random() * parolesFinal.size()));
            randomLyrics = "> " + randomLyrics.replace("\n", "\n> ");
            randomLyrics += "\n\n~ " + titre;

            jsonObject.put("response_type", "in_channel");
            jsonObject.put("text", randomLyrics);
        } catch (Exception e) {
            log.error("Problem with /toplyrics or /patoche", e);
            jsonObject.put("text", ":ckc:");
        }

        return jsonObject.toString();
    }

    private Pair<List<String>, List<String>> fetchSongList() throws IOException {
        try {
            List<String> songTitles = new ArrayList<>();
            List<String> songUrls = new ArrayList<>();

            String[] artists = new String[]{"Jul", "Aya Nakamura", "Heuss L'Enfoiré", "Gambi"};
            String[] urlLists = new String[]{
                    "https://www.paroles.net/jul", "https://www.paroles.net/aya-nakamura",
                    "https://www.paroles.net/heuss-l-enfoire", "https://www.paroles.net/gambi"};

            for (int i = 0; i < artists.length; i++) {
                final String artiste = artists[i];

                Document page = ConnectionUtils.jsoupGetWithRetry(urlLists[i]);

                List<String> titles = new ArrayList<>();
                List<String> urls = new ArrayList<>();

                page.select(".song-listing-extra").get(1).select("a")
                        .forEach(element -> {
                            String songurl = element.attr("href");
                            if (songurl.startsWith("/")) {
                                songurl = "https://www.paroles.net" + songurl;
                            }
                            urls.add(songurl);
                            titles.add(artiste + ", _" + element.text() + "_");
                        });

                log.info(artists[i] + " has " + urls.size() + " songs");

                songTitles.addAll(titles);
                songUrls.addAll(urls);
            }

            log.info("Total: " + songUrls.size() + " songs");
            return Pair.of(songTitles, songUrls);
        } catch (Exception e) {
            log.error("Getting songs broke", e);
            throw e;
        }
    }

    private Pair<List<String>, List<String>> fetchPatocheList() throws IOException {
        try {
            List<String> patocheTitles = new ArrayList<>();
            List<String> patocheUrls = new ArrayList<>();

            // hardcoded, we probably won't reach page 3 at any point :p
            for (String url : new String[]{"https://www.paroles.net/patrick-sebastien", "https://www.paroles.net/patrick-sebastien-2"}) {
                Document page = ConnectionUtils.jsoupGetWithRetry(url);

                List<String> titres = new ArrayList<>();
                List<String> urls = new ArrayList<>();

                page.select("div[typeof=\"v:Song\"] > .center-on-mobile.box-content")
                        .select("a")
                        .forEach(element -> {
                            String songurl = element.attr("href");
                            if (songurl.startsWith("/")) {
                                songurl = "https://www.paroles.net" + songurl;
                            }
                            urls.add(songurl);
                            titres.add("Patrick Sébastien, _" + element.text() + "_");
                        });

                log.info(urls.size() + " chansons added to /patoche");

                patocheTitles.addAll(titres);
                patocheUrls.addAll(urls);
            }

            log.info("Total /patoche: " + patocheUrls.size() + " songs");
            return Pair.of(patocheTitles, patocheUrls);
        } catch (Exception e) {
            log.error("Getting songs broke", e);
            throw e;
        }
    }

    private String commandJoiesDuCode() {
        JSONObject jsonObject = null;
        try {
            while (jsonObject == null) {
                // just try until we succeed :a:
                jsonObject = tryLoadingJoiesDuCode();
            }
        } catch (Exception e) {
            log.error("Problem with /joiesducode", e);
            jsonObject = new JSONObject();
            jsonObject.put("text", "Désolé, la récupération a échoué. :ckc:");
        }

        return jsonObject.toString();
    }

    private JSONObject tryLoadingJoiesDuCode() throws IOException {
        JSONObject jsonObject = new JSONObject();

        Connection.Response resp = Jsoup.connect("https://lesjoiesducode.fr/random")
                .userAgent("Maddie-Random-Stuff-Backend/1.0.0 (+https://github.com/maddie480/RandomBackendStuff)")
                .execute();
        String url = resp.url().toString();
        Document content = Jsoup.parse(resp.body());
        String title = content.select(".blog-post-title").text();

        Elements gif = content.select(".blog-post-content object");
        Elements image = content.select(".blog-post-content img");

        String imageUrl = null;
        if (!gif.isEmpty()) {
            imageUrl = gif.attr("data");
        } else if (!image.isEmpty()) {
            imageUrl = image.attr("src");
        }

        if (imageUrl == null || (!imageUrl.startsWith("http://") && !imageUrl.startsWith("https://"))) {
            // if the result is anything other than an URL, just return null and we'll retry.
            return null;
        } else {
            HashMap<String, String> attachment = new HashMap<>();
            attachment.put("fallback", title + " : " + url);
            attachment.put("title", title);
            attachment.put("image_url", imageUrl);

            jsonObject.put("attachments", Collections.singletonList(attachment));
        }

        jsonObject.put("response_type", "in_channel");
        return jsonObject;
    }

    private static class CoronavirusStats {
        final String country;
        final int confirmed;
        final int deaths;
        final int recovered;

        public CoronavirusStats(String country, int confirmed, int deaths, int recovered) {
            this.country = country;
            this.confirmed = confirmed;
            this.deaths = deaths;
            this.recovered = recovered;
        }
    }

    private String commandCoronavirus() {
        JSONObject jsonObject = new JSONObject();

        try {
            // get coronavirus stats and aggregate them
            JSONArray countries;
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://disease.sh/v2/countries?sort=cases")) {
                countries = new JSONArray(IOUtils.toString(is, UTF_8));
            }

            ArrayList<CoronavirusStats> stats = new ArrayList<>(countries.length());
            for (Object country : countries) {
                JSONObject countryObj = (JSONObject) country;
                stats.add(new CoronavirusStats(countryObj.getString("country"),
                        countryObj.getInt("cases"),
                        countryObj.getInt("deaths"),
                        countryObj.getInt("recovered")));
            }

            DecimalFormat format = new DecimalFormat("#,###", DecimalFormatSymbols.getInstance(Locale.FRANCE));

            // aggregated stats
            StringBuilder message = new StringBuilder("**Statistiques du coronavirus**\n__Dans le monde :__ ");
            message.append(format.format(stats.stream().mapToInt(s -> s.confirmed).sum())).append(" cas, ");
            message.append(format.format(stats.stream().mapToInt(s -> s.deaths).sum())).append(" morts, ");
            message.append(format.format(stats.stream().mapToInt(s -> s.recovered).sum())).append(" guéris\n__En France (**");

            // stats for France
            CoronavirusStats frenchStats = stats.stream().filter(s -> s.country.equals("France")).findFirst()
                    .orElseThrow(() -> new Exception("aaaa la France n'existe pas"));
            int francePosition = stats.indexOf(frenchStats) + 1;
            message.append(francePosition).append(francePosition == 1 ? "er" : "e").append("**) :__ ");
            message.append(format.format(frenchStats.confirmed)).append(" cas, ");
            message.append(format.format(frenchStats.deaths)).append(" morts, ");
            message.append(format.format(frenchStats.recovered)).append(" guéris\n\n__Top 5 :__\n");

            // top 5 countries
            int position = 1;
            for (CoronavirusStats countryStats : stats) {
                message.append("**").append(countryStats.country).append("** : ");
                message.append(format.format(countryStats.confirmed)).append(" cas, ");
                message.append(format.format(countryStats.deaths)).append(" morts, ");
                message.append(format.format(countryStats.recovered)).append(" guéris\n");

                if (++position > 5) break;
            }

            jsonObject.put("text", message.toString().trim());
            jsonObject.put("response_type", "in_channel");
        } catch (Exception e) {
            log.error("Problem with /coronavirus", e);
            jsonObject.put("text", "Désolé, la récupération de la base de données a échoué. :ckc:");
        }

        return jsonObject.toString();
    }

    private String commandFakeName() {
        JSONObject jsonObject = new JSONObject();

        try {
            Document document = ConnectionUtils.jsoupGetWithRetry("https://www.fakenamegenerator.com/gen-random-fr-fr.php");
            String name = "**" + document.select(".address h3").text() + "**\n"
                    + document.select(".address .adr").html().replace("<br>", "\n").trim();
            while (name.contains("\n\n")) {
                name = name.replace("\n\n", "\n");
            }

            jsonObject.put("response_type", "in_channel");
            jsonObject.put("text", name);
        } catch (Exception e) {
            log.error("Problem with /fakename", e);
            jsonObject.put("text", "Désolé, la récupération a échoué. :ckc:");
        }

        return jsonObject.toString();
    }

    private static class VideoTendance {
        public int views;
        public ZonedDateTime publishedDate;
        public String author;
        public String title;
        public int position;
        public String id;
    }

    private String commandTendancesYoutube() {
        JSONObject jsonObject = new JSONObject();

        try {
            List<VideoTendance> tendancesYoutube = refreshTendancesYoutube();
            VideoTendance video = tendancesYoutube.get((int) (Math.random() * tendancesYoutube.size()));

            String text = "**" + video.title + "**\n" +
                    "_Par " + video.author + ", publié le " + video.publishedDate.format(DateTimeFormatter.ofPattern("dd MMMM yyyy 'à' HH'h'mm", Locale.FRENCH)) + ", "
                    + new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.FRENCH)).format(video.views) + " vues" +
                    " - #" + video.position + " des tendances_\n:arrow_right: "
                    + "https://youtu.be/" + video.id;

            jsonObject.put("response_type", "in_channel");
            jsonObject.put("text", text);
        } catch (Exception e) {
            log.error("Problem while getting YouTube trends", e);
            jsonObject.put("text", "Désolé, la récupération a échoué. :ckc:");
        }

        return jsonObject.toString();
    }

    private List<VideoTendance> refreshTendancesYoutube() throws IOException {
        log.info("Refresh YouTube trends");

        List<VideoTendance> tendancesYoutube = new ArrayList<>();

        JSONObject youtubeResponse = new JSONObject(IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://www.googleapis.com/youtube/v3/videos?hl=fr&maxResults=50" +
                "&regionCode=FR&chart=mostPopular&part=snippet,statistics&key=" + SecretConstants.YOUTUBE_API_KEY), UTF_8));

        for (Object video : youtubeResponse.getJSONArray("items")) {
            try {
                VideoTendance videoObj = new VideoTendance();
                JSONObject snippet = ((JSONObject) video).getJSONObject("snippet");
                JSONObject statistics = ((JSONObject) video).getJSONObject("statistics");
                JSONObject localized = snippet.getJSONObject("localized");

                videoObj.author = snippet.getString("channelTitle");
                videoObj.publishedDate = ZonedDateTime.parse(snippet.getString("publishedAt"));
                videoObj.title = localized.getString("title");
                videoObj.views = Integer.parseInt(statistics.getString("viewCount"));
                videoObj.position = tendancesYoutube.size() + 1;
                videoObj.id = ((JSONObject) video).getString("id");
                tendancesYoutube.add(videoObj);
            } catch (Exception e) {
                log.warn("Could not parse video", e);
            }
        }

        if (tendancesYoutube.isEmpty()) {
            throw new IOException("There is no valid video! :a:");
        }

        log.info(tendancesYoutube.size() + " videos found");
        return tendancesYoutube;
    }

    private String commandPutaclic() {
        JSONObject jsonObject = new JSONObject();

        try {
            Document document = ConnectionUtils.jsoupGetWithRetry("http://www.le-toaster.fr/generateur-buzz/");
            String text = document.select("article h2").text().trim();

            jsonObject.put("response_type", "in_channel");
            jsonObject.put("text", text);
        } catch (Exception e) {
            log.error("Problem with /putaclic", e);
            jsonObject.put("text", "Désolé, la récupération a échoué. :ckc:");
        }

        return jsonObject.toString();
    }

    private String commandRandomParrot() {
        try {
            Map<String, String> parrots = ConnectionUtils.jsoupGetWithRetry("https://storage.googleapis.com/integ-de-covid/parrot-quick-importer-online.html")
                    .select(".target")
                    .stream()
                    .collect(Collectors.toMap(node -> node.attr("title"), node -> node.attr("data-target")));

            List<String> parrotNames = new ArrayList<>(parrots.keySet());

            final int selected = (int) (Math.random() * parrotNames.size());

            HashMap<String, String> attachment = new HashMap<>();
            attachment.put("fallback", parrotNames.get(selected) + " : " + parrots.get(parrotNames.get(selected)));
            attachment.put("title", parrotNames.get(selected));
            attachment.put("image_url", parrots.get(parrotNames.get(selected)));

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("response_type", "in_channel");
            jsonObject.put("attachments", Collections.singletonList(attachment));
            return jsonObject.toString();
        } catch (Exception e) {
            log.error("Problem with /randomparrot", e);

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("text", "Désolé, la récupération des parrots a échoué. :ckc:");
            return jsonObject.toString();
        }
    }

    private String commandMonkeyUser() {
        List<String> links = new ArrayList<>();
        List<String> names = new ArrayList<>();

        final String sourceCode;

        try {
            sourceCode = IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://www.monkeyuser.com/"), UTF_8);
        } catch (Exception e) {
            log.error("Problem with /monkeyuser", e);

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("text", "Désolé, la consultation de Monkey User a échoué. :ckc:");
            return jsonObject.toString();
        }

        // comics are all in the source code of the page... in JS form. Extract them using epic regex
        final Pattern imageRegex = Pattern.compile("^\\s+images\\.push\\(\"([^\"]+)\"\\);");
        final Pattern titlesRegex = Pattern.compile("^\\s+titles\\.push\\(\"([^\"]+)\"\\);");
        for (String line : sourceCode.split("\n")) {
            Matcher m = imageRegex.matcher(line);
            if (m.matches()) {
                links.add("https://www.monkeyuser.com/assets/images/" + m.group(1));
            }

            m = titlesRegex.matcher(line);
            if (m.matches()) {
                names.add(m.group(1));
            }
        }

        if (links.size() == 0 || links.size() != names.size()) {
            // there are no links or we have more/less comic links than titles. it means there probably is a problem somewhere...
            log.error("Problem while getting Monkey User comics: " + links.size() + " links and " + names.size() + " names found");

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("text", "Désolé, la consultation de Monkey User a échoué. :ckc:");
            return jsonObject.toString();
        }

        int selected;
        do {
            selected = (int) (Math.random() * links.size());
        } while (!links.get(selected).toLowerCase(Locale.ROOT).endsWith(".png"));

        HashMap<String, String> attachment = new HashMap<>();
        attachment.put("fallback", names.get(selected) + " : " + links.get(selected));
        attachment.put("title", names.get(selected));
        attachment.put("image_url", links.get(selected));

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("response_type", "in_channel");
        jsonObject.put("attachments", Collections.singletonList(attachment));
        return jsonObject.toString();
    }

    private String commandXkcd() {
        final Document xkcd;

        try {
            xkcd = ConnectionUtils.jsoupGetWithRetry("https://c.xkcd.com/random/comic/");
        } catch (Exception e) {
            log.error("Problem while getting xkcd comic", e);

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("text", "Désolé, la consultation de xkcd a échoué. :ckc:");
            return jsonObject.toString();
        }

        String title = xkcd.select("#ctitle").text();
        String image = "https:" + xkcd.select("#comic img").attr("src");
        String description = xkcd.select("#comic img").attr("title");

        HashMap<String, String> attachment = new HashMap<>();
        attachment.put("fallback", title + " : " + image + "\n" + description);
        attachment.put("title", title);
        attachment.put("image_url", image);
        attachment.put("text", description);

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("response_type", "in_channel");
        jsonObject.put("attachments", Collections.singletonList(attachment));
        return jsonObject.toString();
    }

    private String commandInfoPipo() {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("response_type", "in_channel");
            jsonObject.put("text", "`" + IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://www.luc-damas.fr/pipotron/fail_geek/"), UTF_8) + "`");
            return jsonObject.toString();
        } catch (IOException e) {
            log.error("Problem while getting info pipo", e);

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("text", ":ckc:");
            return jsonObject.toString();
        }
    }

    private String commandJCVD() {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("text", ConnectionUtils.jsoupGetWithRetry("https://www.faux-texte.com/jean-claude-2.htm")
                    .select("#TheTexte p").get(0).text());
            jsonObject.put("response_type", "in_channel");
        } catch (Exception e) {
            log.error("Problem with /jcvd", e);
            jsonObject.put("text", "Désolé, l'appel à faux-texte.com a échoué. :ckc:");
        }

        return jsonObject.toString();
    }

    /**
     * Ported from a website I cannot find anymore.
     */
    private String commandEddy() {
        final String[][] generator = {
                {"Chapitre abstrait 3 du conpendium :", "C’est à dire ici, c’est le contraire, au lieu de panacée,", "Au nom de toute la communauté des savants,", "Lorsqu’on parle de tous ces points de vues,", "C’est à dire quand on parle de ces rollers,", "Quand on parle de relaxation,", "Nous n’allons pas seulement danser ou jouer au football,", "D'une manière ou d'une autre,", "Quand on prend les triangles rectangles,", "Se consolidant dans le système de insiding et outsiding,", "Lorsque l'on parle des végétaliens, du végétalisme,", "Contre la morosité du peuple,", "Tandis que la politique est encadrée par des scientifiques issus de Sciences Po et Administratives,", "On ne peut pas parler de politique administrative scientifique,", "Pour emphysiquer l'animalculisme,", "Comme la coumbacérie ou le script de Aze,", "Vous avez le système de check-up vers les anti-valeurs, vous avez le curuna, or", "La convergence n’est pas la divergence,", "L’émergence ici c’est l’émulsion, c’est pas l’immersion donc", "Imbiber, porter", "Une semaine passée sans parler du peuple c’est errer sans abri, autrement dit", "Actuellement,", "Parallèlement,", "Mesdames et messieurs fidèles,"},
                {"la cosmogonisation", "l'activisme", "le système", "le rédynamisme", "l'ensemble des 5 sens", "la société civile", "la politique", "la compétence", "le colloque", "la contextualisation", "la congolexicomatisation", "la congolexicomatisation", "la congolexicomatisation", "la congolexicomatisation", "la prédestination", "la force", "la systématique", "l'ittérativisme", "le savoir", "l'imbroglio", "la concertation politique", "la délégation", "la pédagogie", "la réflexologie"},
                {"vers la compromettance pour des saint-bioules", "vers ce qu’on appelle la dynamique des sports", "de la technicité informatisée", "de la Théorie Générale des Organisations", "autour de la Géo Physique Spatiale", "purement technique", "des lois du marché", "de l'orthodoxisation", "inter-continentaliste", "à l'égard de la complexité", "éventualiste sous cet angle là", "de toute la République Démocratique du Congo", "à l'incognito", "autour de l'ergonométrie", "indispensable(s) en science et culture", "autour de phylogomènes généralisés", "à forciori,", "par rapport aux diplomaties"},
                {"tend à ", "nous pousse à ", "fait allusion à ", "va ", "doit ", "consiste à ", "nous incite à", "vise à", "semble", "est censé(e)", "paraît", "peut", "s'applique à", "consent à", "continue à", "invite à", "oblige à", "parvient à", "pousse à", "se résume à", "suffit à", "se résoud à", "sert à", "tarde à"},
                {"incristaliser", "imposer", "intentionner ", "mettre un accent sur ", "tourner ", "informatiser ", "aider ", "défendre ", "gérer ", "prévaloir ", "vanter ", "rabibocher", "booster", "porter d'avis sur ce qu'on appelle", "cadrer", "se baser sur", "effaceter", "réglementer", "régler", "faceter", "partager", "uniformiser", "défendre", "soutenir", "propulser", "catapulter", "établir"},
                {"les interchanges", "mes frères propres", "les revenus", "cette climatologie", "une discipline", "la nucléarité", "l'upensmie", "les sens dynamitiels", "la renaissance africaine", "l'estime du savoir", "une kermesse", "une certaine compétitivité", "cet environnement de 2 345 410 km²", "le kilométrage", "le conpemdium", "la quatripartie", "les encadrés", "le point adjacent", "la bijectivité", "le panafricanisme", "ce système phénoménal", "le système de Guipoti : 1/B+1/B’=1/D", "une position axisienne", "les grabuses lastiques", "le chicouangue", "le trabajo, le travail, la machinale, la robotisation", "les quatre carrés fous du fromage"},
                {"autour des dialogues intercommunautaires", "provenant d'une dynamique syncronique", "vers le monde entier", "propre(s) aux congolais", "vers Lovanium", "vers l'humanisme", "comparé(e)(s) la rénaque", "autour des gens qui connaissent beaucoup de choses", "possédant la francophonie", "dans ces prestances", "off-shore", "dans Kinshasa", "dans la sous-régionalité", "dans le prémice", "belvédère", "avec la formule 1+(2x5)", "axé(e)(s) sur la réalité du terrain", "dans les camps militaires non-voyants", "avéré(e)(s)", "comme pour le lancement de Troposphère V"},
                {", tu sais ça", ", c’est clair", ", je vous en prie", ", merci", ", mais oui", ", Bonne Année", ", bonnes fêtes"}
        };

        StringBuilder s = new StringBuilder();
        for (String[] parts : generator) {
            String chosen = parts[(int) (Math.random() * parts.length)];
            s.append(" ").append(chosen);
        }

        s = new StringBuilder(s.substring(1).replace("  ", " ").replace(" ,", ",") + ".");

        JSONObject outputJSON = new JSONObject();

        outputJSON.put("response_type", "in_channel");
        outputJSON.put("text", s.toString());
        return outputJSON.toString();
    }

    /**
     * Ported from https://www.pipotronic.com/
     */
    private String commandPipo(String param, String userName) {
        int count = 1;
        StringBuilder phrases = new StringBuilder();

        String supMessage = "";

        if (param != null && !param.trim().isEmpty()) {
            try {
                count = Integer.parseInt(param);

                if (count > 5) {
                    // force-cap to 5
                    supMessage = "@" + userName + ": J'envoie 5 pipos au maximum. Ca suffit...";
                    count = 5;
                } else if (count <= 0) {
                    // messages in case the user invokes with 0 or less
                    switch ((int) (Math.random() * 5)) {
                        case 0 -> phrases = new StringBuilder("Tu veux que je te réponde quoi au juste là ?");
                        case 1 -> phrases = new StringBuilder("T'es sérieux ?");
                        case 2 -> phrases = new StringBuilder("...");
                        case 3 -> phrases = new StringBuilder("<rien du tout>");
                        case 4 ->
                                phrases = new StringBuilder("Hum... " + count + " pipos ? T'essaierais pas de me pipoter là ?");
                    }

                    JSONObject outputJSON = new JSONObject();

                    outputJSON.put("response_type", "in_channel");
                    outputJSON.put("text", phrases.toString());
                    return outputJSON.toString();
                }
            } catch (NumberFormatException nfe) {
                // invalid number, will default to 1
                supMessage = "@" + userName + ": La prochaine fois, passe-moi un nombre en paramètre ;)";
            }
        }

        for (int l = 0; l < count; l++) {
            String[][] pipo = {
                    {"Face à", "Relativement à", "Pour optimiser", "Pour accentuer", "Afin de maîtriser", "Au moyen d#", "Depuis l'émergence d#", "Pour challenger", "Pour défier",
                            "Pour résoudre", "En termes de redynamisation d#", "Concernant l'implémentation d#", "À travers", "En s'orientant vers", "En termes de process, concernant",
                            "En rebondissant sur", "Pour intégrer", "Une fois internalisée", "Pour externaliser", "Dans la lignée d#", "En synergie avec",
                            "Là où les benchmarks désignent", "Au cœur d#", "En auditant", "Une fois evaluée", "Partout où domine", "Pour réagir à", "En jouant", "Parallèlement à",
                            "Malgré", "En réponse à", "En réaction à", "Répliquant à", "En phase de montée en charge d#", "En réponse à", "En phase de montée en charge d#", "Grâce à",
                            "Perpendiculairement à", "Indépendamment d#", "Corrélativement à", "Tangentiellement à", "Concomitamment à", "Par l'implémentation d#"
                    },
                    {"la problématique", "l'opportunité", "la mondialisation", "une globalisation", "la bulle", "la culture", "la synergie", "l'efficience", "la compétitivité",
                            "une dynamique", "une flexibilité", "la revalorisation", "la crise", "la stagflation", "la convergence", "une réactivité", "une forte croissance",
                            "la gouvernance", "la prestation", "l'offre", "l'expertise", "une forte suppléance", "une proposition de valeur", "une supply chain", "la démarche",
                            "une plate-forme", "une approche", "la mutation", "l'adaptabilité", "la pluralité", "une solution", "la multiplicité", "la transversalité",
                            "la mutualisation"
                    },
                    {"opérationnelle,", "quantitative,", "des expertises,", "porteuse,", "autoporteuse,", "collaborative,", "accélérationnelle,", "durable,", "conjoncturelle,",
                            "institutionnelle,", "managériale,", "multi-directionnelle,", "communicationnelle,", "organisationnelle,", "entrepreneuriale,", "motivationnelle,",
                            "soutenable,", "qualitative,", "stratégique,", "interne / externe,", "online / offline,", "situationnelle,", "référentielle,", "institutionnelle,",
                            "globalisante,", "solutionnelle,", "opérationnelle,", "compétitionnelle,", "gagnant-gagnant,", "interventionnelle,", "sectorielle,", "transversale,",
                            "des prestations,", "ambitionnelle,", "des sous-traitances,", "corporate,", "asymétrique,", "budget", "référentielle"
                    },
                    {"les cadres doivent ", "les personnels concernés doivent ", "les personnels concernés doivent ", "les N+1 doivent ", "le challenge consiste à",
                            "le défi est d#", "il faut", "on doit", "il faut", "on doit", "il faut", "on doit", "il faut", "on doit", "chacun doit", "les fournisseurs vont",
                            "les managers décident d#", "les acteurs du secteur vont", "les responsables peuvent", "la conjecture peut", "il est impératif d#",
                            "un meilleur relationnel permet d#", "une ambition s'impose :", "mieux vaut", "le marché exige d#", "le marché impose d#", "il s'agit d#",
                            "voici notre ambition :", "une réaction s'impose :", "voici notre conviction :", "les bonnes pratiques consistent à", "chaque entité peut",
                            "les décideurs doivent", "il est requis d#", "les sociétés s'engagent à", "les décisionnaires veulent", "les experts doivent",
                            "la conjecture pousse les analystes à", "les structures vont", "il faut un signal fort :", "la réponse est simple :", "il faut créer des occasions :",
                            "la réponse est simple :", "l'objectif est d#", "l'objectif est évident :", "l'ambition est claire :", "chaque entité doit", "une seule solution :",
                            "il y a nécessité d#", "il est porteur d#", "il faut rapidement", "il faut muscler son jeu : ", "la réponse client permet d#",
                            "la connaissance des paramètres permet d#", "les éléments moteurs vont"
                    },
                    {"optimiser", "faire interagir", "capitaliser sur", "prendre en considération", "anticiper ", "intervenir dans", "imaginer", "solutionner", "piloter",
                            "dématerialiser", "délocaliser", "coacher", "investir sur", "valoriser", "flexibiliser", "externaliser", "auditer", "sous-traiter", "revaloriser", "habiliter",
                            "requalifier", "revitaliser", "solutionner", "démarcher", "budgetiser", "performer", "incentiver", "monitorer", "segmenter", "désenclaver", "décloisonner",
                            "déployer", "réinventer", "flexibiliser", "optimiser", "piloter", "révolutionner", "gagner", "réussir", "connecter", "faire converger", "planifier",
                            "innover sur", "monétiser", "concrétiser", "impacter", "transformer", "prioriser", "chiffrer", "initiativer", "budgetiser", "rénover", "dominer"
                    },
                    {"solutions", "issues", "axes mobilisateurs", "problématiques", "cultures", "alternatives", "interactions", "issues", "expertises", "focus", "démarches",
                            "alternatives", "thématiques", "atouts", "ressources", "applications", "applicatifs", "architectures", "prestations", "process", "performances", "bénéfices",
                            "facteurs", "paramètres", "capitaux", "sourcing", "émergences", "kick-off", "recapitalisations", "produits", "frameworks", "focus", "challenges", "décisionnels",
                            "ouvertures", "fonctionnels", "opportunités", "potentiels", "territoires", "leaderships", "applicatifs", "prestations", "plans sociaux", "wordings",
                            "harcèlements", "monitorings", "montées en puissance", "montées en régime", "facteurs", "harcèlements", "référents", "éléments", "nécessités",
                            "partenariats", "retours d'expérience", "dispositifs", "potentiels", "intervenants", "directives", "directives", "perspectives", "contenus", "implications",
                            "kilo-instructions", "supports", "potentiels", "mind mappings", "thématiques", "workshops", "cœurs de mission", "managements", "orientations", "cibles"
                    },
                    {"métier", "prospect", "customer", "back-office", "client", "envisageables", "à l'international", "secteur", "client", "vente", "projet", "partenaires", "durables",
                            "à forte valeur ajoutée", "soutenables", "chiffrables", "évaluables", "force de vente", "corporate", "fournisseurs", "bénéfices", "convivialité",
                            "compétitivité", "investissement", "achat", "performance", "à forte valeur ajoutée", "dès l'horizon 2020", "à fort rendement", "qualité", "logistiques",
                            "développement", "risque", "terrain", "mobilité", "praticables", "infrastructures", "organisation", "projet", "recevables", "investissement",
                            "conseil", "conseil", "sources", "imputables", "intermédiaires", "leadership", "pragmatiques", "framework", "coordination", "d'excellence", "stratégie",
                            "de confiance", "crédibilité", "compétitivité", "méthodologie", "mobilité", "efficacité", "efficacité"
                    }
            };

            String[] selectedPipos = new String[pipo.length];
            int i = 0;
            for (String[] pipoLine : pipo) {
                selectedPipos[i++] = pipoLine[(int) (Math.random() * pipoLine.length)];
            }

            for (i = 0; i < selectedPipos.length - 1; i++) {
                if (selectedPipos[i].endsWith("#")) {
                    String pipoWithoutTrailingChar = selectedPipos[i].substring(0, selectedPipos[i].length() - 1);

                    if (selectedPipos[i + 1].startsWith("a")
                            || selectedPipos[i + 1].startsWith("e")
                            || selectedPipos[i + 1].startsWith("i")
                            || selectedPipos[i + 1].startsWith("o")
                            || selectedPipos[i + 1].startsWith("u")
                            || selectedPipos[i + 1].startsWith("y")) {
                        selectedPipos[i] = pipoWithoutTrailingChar + "'";
                    } else {
                        selectedPipos[i] = pipoWithoutTrailingChar + "e ";
                    }
                } else {
                    selectedPipos[i] += " ";
                }
            }

            String phrase = (selectedPipos[0] + " "
                    + selectedPipos[1] + " "
                    + selectedPipos[2] + " "
                    + selectedPipos[3] + " "
                    + selectedPipos[4] + " les "
                    + selectedPipos[5] + " "
                    + selectedPipos[6] + ".");

            while (phrase.contains("  "))
                phrase = phrase.replace("  ", " ");
            phrase = phrase.replace("' ", "'");
            phrase = phrase.replace(" .", ".");

            phrase = "_" + phrase + "_";

            if (phrases.length() == 0) phrases = new StringBuilder(phrase);
            else phrases.append("\n").append(phrase);
        }

        if (!supMessage.isEmpty()) {
            phrases.append("\n").append(supMessage);
        }

        JSONObject outputJSON = new JSONObject();
        outputJSON.put("response_type", "in_channel");
        outputJSON.put("text", phrases.toString());
        return outputJSON.toString();
    }

    private String commandWeekend(String userName) {
        String endingPhrase = "*C'est le week-end \\o/*";
        String notFinishedPhrase = "@" + userName + ", le week-end est dans ";

        // weekend is at 6pm on Friday. Go back if today is Saturday or Sunday.
        ZonedDateTime zdt = ZonedDateTime.now().withZoneSameInstant(ZoneId.of("Europe/Paris"));
        while (zdt.getDayOfWeek() != DayOfWeek.FRIDAY) {
            if (Arrays.asList(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).contains(zdt.getDayOfWeek())) {
                zdt = zdt.minusDays(1);
            } else {
                zdt = zdt.plusDays(1);
            }
        }
        zdt = zdt.withHour(18).withMinute(0).withSecond(0).withNano(0);

        long time = zdt.toInstant().toEpochMilli();
        long now = System.currentTimeMillis();

        long seconds = ((time - now) / (1000));
        long minutes = seconds / 60;
        seconds %= 60;

        long hours = minutes / 60;
        minutes %= 60;

        long days = hours / 24;
        hours %= 24;

        JSONObject outputJSON = new JSONObject();
        String phrase;
        if (time - now < 0) {
            phrase = endingPhrase;
        } else {
            phrase = notFinishedPhrase
                    + (days == 0 ? "" : days + " jour(s), ") + hours + " heure(s), " + minutes + " minute(s) et " + seconds + " seconde(s) !";
        }

        outputJSON.put("response_type", "in_channel");
        outputJSON.put("text", phrase);
        return outputJSON.toString();
    }

    private String commandLangueDeBois() {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("text", ConnectionUtils.jsoupGetWithRetry("https://www.faux-texte.com/langue-bois-2.htm")
                    .select("#TheTexte p")
                    .stream().map(element -> {
                        String s = element.text();
                        if (!s.endsWith(".")) s += ".";
                        return s;
                    }).collect(Collectors.joining("\n")));
            jsonObject.put("response_type", "in_channel");
        } catch (Exception e) {
            log.error("Problem with /languedebois", e);
            jsonObject.put("text", "Désolé, l'appel à faux-texte.com a échoué. :ckc:");
        }

        return jsonObject.toString();
    }

    private String commandBientotLeWeekend() {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("text", "_Est-ce que c'est bientôt le week-end ?_\n" +
                    ConnectionUtils.jsoupGetWithRetry("https://estcequecestbientotleweekend.fr/").select(".msg")
                            .stream().map(Element::text).collect(Collectors.joining("\n")));
            jsonObject.put("response_type", "in_channel");
        } catch (Exception e) {
            log.error("Problem with /bientotleweekend", e);
            jsonObject.put("text", "Désolé, la slash command n'a pas fonctionné. :ckc:");
        }

        return jsonObject.toString();
    }

    private String commandVacances(String target, String userId) {
        purgeVacances();

        JSONObject jsonObject = new JSONObject();
        if (target != null && !target.isEmpty()) {
            // define a new holiday date.
            try {
                ZonedDateTime targetDateTime = ZonedDateTime.of(LocalDate.parse(target, DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                        LocalTime.of(18, 0, 0, 0), ZoneId.of("Europe/Paris"));

                log.info("Countdown target is: " + targetDateTime);

                if (targetDateTime.isBefore(ZonedDateTime.now())) {
                    // date is in the past
                    jsonObject.put("text", "La date que tu m'as donnée (" +
                            targetDateTime.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH))
                            + ") est dans le passé. Essaie encore. :confused:");
                } else {
                    // date is valid, save it
                    saveTheDate(userId, targetDateTime.toInstant().toEpochMilli());

                    jsonObject.put("text", "OK ! La date de tes vacances (" +
                            targetDateTime.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH))
                            + ") a bien été enregistrée.\nTape `/vacances` pour avoir un compte à rebours !");
                }
            } catch (DateTimeParseException ex) {
                // date is invalid
                log.warn("Date parse error: " + ex);
                jsonObject.put("text", "Je n'ai pas compris la date que tu m'as donnée en paramètre.");
            }
        } else {
            // find out how much time is left until holidays
            Long targetSaved = findTheDate(userId);
            String message;
            if (targetSaved != null && (message = findDiff(targetSaved)) != null) {
                jsonObject.put("response_type", "in_channel");
                jsonObject.put("text", message);
            } else {
                // user has no holiday saved
                jsonObject.put("text", "Tu n'as pas défini la date de tes vacances.\n" +
                        "Lance la commande `/vacances [JJ/MM/AAAA]` pour le faire.");
            }
        }

        return jsonObject.toString();
    }

    private Map<String, Long> loadDatabase() {
        try (BufferedReader br = new BufferedReader(new FileReader("slash_command_bot_vacances.csv"))) {
            HashMap<String, Long> result = new HashMap<>();
            String s;
            while ((s = br.readLine()) != null) {
                String[] line = s.split(";");
                result.put(line[0], Long.parseLong(line[1]));
            }
            return result;
        } catch (IOException e) {
            log.error("Could not load slash_command_bot_vacanes.csv", e);
            return new HashMap<>();
        }
    }

    private void saveDatabase(Map<String, Long> database) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter("slash_command_bot_vacances.csv"))) {
            for (Map.Entry<String, Long> entry : database.entrySet()) {
                bw.write(entry.getKey() + ";" + entry.getValue() + "\n");
            }
        } catch (IOException e) {
            log.error("Could not save slash_command_bot_vacanes.csv", e);
        }
    }

    public void purgeVacances() {
        // purge past holidays from database
        final Map<String, Long> database = loadDatabase();
        final Set<String> toDelete = new HashSet<>();
        for (Map.Entry<String, Long> entry : database.entrySet()) {
            if (entry.getValue() < System.currentTimeMillis() - 86_400_000L) {
                log.info("Deleting holiday " + entry.getKey() + " from the database because date is " +
                        new Date(entry.getValue()));
                toDelete.add(entry.getKey());
            }
        }

        if (!toDelete.isEmpty()) {
            for (String s : toDelete) {
                database.remove(s);
            }
            saveDatabase(database);
        }
    }

    private void saveTheDate(String userId, long target) {
        final Map<String, Long> database = loadDatabase();
        database.put(userId, target);
        saveDatabase(database);
    }

    private Long findTheDate(String userId) {
        final Map<String, Long> database = loadDatabase();
        return database.getOrDefault(userId, null);
    }

    private String findDiff(long time) {
        long now = System.currentTimeMillis();

        long seconds = ((time - now) / (1000));
        long minutes = seconds / 60;
        seconds %= 60;

        long hours = minutes / 60;
        minutes %= 60;

        long days = hours / 24;
        hours %= 24;

        String phrase;

        if (time - now < 0 && time - now > -86_400_000L) {
            phrase = "**C'est les vacances ! \\o/**";
        } else if (time - now < 0) {
            return null;
        } else {
            phrase = "Les vacances sont dans "
                    + (days == 0 ? "" : days + " jour(s), ") + hours + " heure(s), " + minutes + " minute(s) et " + seconds + " seconde(s) !";
        }

        return phrase;
    }

    /**
     * This is ported straight from JS from https://lunatopia.fr/blog/films-de-noel
     */
    private static class ChristmasFilmGenerator {
        private static final String[] titre = new String[]{"12 cadeaux pour Noël", "Une mariée pour Noël", "Le détour de Noël",
                "Un duo pour Noël", "La mélodie de Noël", "Un Noël de conte de fées", "Un Noël de rêve", "Un rêve pour Noël",
                "Des cookies pour Noël", "La mariée de décembre", "Un mariage pour Noël", "Un cadeau de rêve", "Un Noël parfait",
                "Un Noël royal", "Un prince pour Noël", "Un vœux pour Noël", "Le meilleur Noël", "Noël en famille", "L'aventure de Noël",
                "La buche de Noël", "Un noël enchanté", "Le meilleur des cadeaux", "Un cadeau de rêve", "L'amour en cadeau", "Un amour de Noël",
                "Le Noël de l'amour", "L'amour en héritage", "L'héritage de Noël", "Une surprise pour Noël", "Une lettre au père Noël", "La liste de Noël",
                "Un Noël à emporter", "Noël en fête", "Noël sous les étoiles", "Une couronne pour Noël", "Un Noël courronné", "Je t'aime comme Noël",
                "Épouse moi pour Noël", "Miss Noël", "Mister Noël", "Mon amour de Noël", "Mon rêve de Noël", "Un rêve pour Noël", "Des vacances de rêve",
                "Il était une fois à Noël", "Le calendrier de l'avent", "Orgueil, préjugés et cadeaux sous le sapin", "De l'amour sous le sapin",
                "Un baiser sous le gui", "L'amour sous le gui", "Réunion de Noël", "Des retrouvailles à Noël", "En route pour Noël", "Mariée d'hiver",
                "La promesse de Noël", "Une promesse sous le gui", "Le secret de Noël", "Un secret pour Noël", "Un secret sous le sapin",
                "Le plus beau jour de l'année", "Le plus beau Noël de ma vie", "Le plus beau cadeau de ma vie", "Les neufs vies de Noël", "Le plus beau des Noël",
                "Le plus doux des Noël", "La carte de Noël"};

        private static final String[] nomMeuf = new String[]{"Lacey Charm", "Alicia Witt", "Ambert McIver", "Ava Mitchell", "April McDonagall", "Kristin Elliott",
                "Candace Cameron Burke", "Emily McLayne", "Jessie Jones", "Candice Everdeen", "Kaylynn Riley McAlistair", "Alexandra McKintosh", "Abbey Patterson",
                "Cate Sweetin", "Jodie Walker", "Belinda Shaw", "Merritt Patterson", "Nancy Davis", "Candy McLair", "Donna Mills", "Christie Reynolds", "Pennie Miller"};

        private static final String[] nomMec = new String[]{"Nick Cane", "Richard Wright", "Gabriel Hogan", "Edgar Jones", "Rory Gallagher", "Sam Page", "Gabe Walker",
                "Eon Bailey", "Brennan Hebert", "Dylon O'Neil", "Henri Walsh", "Andrew Mann", "Dustin McGary", "Matthew McDonagall", "Brian McAlistair"};

        private static final String[] etat = new String[]{"Ohio", "Nebraska", "Wisconsin", "Wyoming", "Oregon", "Montana", "Minnesota", "Maine", "Vermont",
                "Connecticut", "Kentucky", "Texas", "Missouri", "Illinois", "Indiana", "Arizona", "Arkansas", "Oklahoma", "Iowa", "Kansas", "Colorado",
                "Idaho", "Nevada", "Utah"};

        private static final String[] petiteVille = new String[]{"Ridgetown", "Riverside", "Winslow", "Eureka", "Carmel", "New Castle", "Crystal River", "Franklin",
                "Fairfield", "Greenville", "Kingston", "Springfield", "Arlington", "Georgetown", "Madison", "Salen", "Old Lebanon", "Port Clinton", "Ashland",
                "Ashville", "Fort Jackson", "Milton", "Newport", "Clayton", "Dayton", "Lexington", "Milford", "Winchester", "Port Hudson", "Davenportside", "Burbank",
                "Lakewood", "Marion Falls", "Sioux Falls", "Edison", "Arlingwood", "Ann Arbor", "Mary Valley", "Thousand Oaks", "Treehills", "Kentford",
                "Port New Haven", "Crystal Falls"};

        private static final String[] grandeVille = new String[]{"New-York", "Los Angeles", "Chicago", "San Francisco", "Seattle", "Washington", "Las Vegas", "Manhattan"};

        private static final String[] metierMeuf = new String[]{"styliste", "décoratrice d'intérieur", "photographe de mode", "photographe de sports extrêmes", "avocate",
                "agent immobilier", "illustratrice de livres pour enfants", "pianiste de renommée mondiale", "cheffe d'entreprise", "publiciste", "graphiste",
                "directrice de communication", "guide touristique", "journaliste dans la mode", "architecte", "architecte d'intérieur", "organisatrice de mariage"};

        private static final String[] metierMec = new String[]{"directeur d'école", "éleveur", "fermier", "ébeniste", "potier", "céramiste", "libraire", "vétérinaire",
                "fleuriste", "sculpteur sur glace"};

        private static final String[] histoire = new String[]{"laMeuf, avocate dans l'immobilier, fait la rencontre de leMec, un petit libraire qui essaye d'empêcher la construction du centre commercial dont elle s'occupe. Va-t-elle abandonner ses projets par amour ?",
                "laMeuf, metierCool qui travaille beaucoup trop, est obligée de retourner dans son etatPaume natal pour s'occuper de l'héritage de sa grand-mère et fait la rencontre de leMec, jeune vétérinaire de la ville. Entre sa vie à bigCity avec un salaire à 5 chiffre et les plaisirs simples de la campagne, le choix va être difficile&nbsp;!",
                "laMeuf, metierCool à bigCity, rentre à contre-cœur passer les fêtes en famille. Les choses empirent quand elle se retrouve obligée à faire équipe avec son ex, leMec, pour la chasse au trésor de Noël de villePaume, etatPaume, sa ville natale.",
                "Dans 4 jours, laMeuf doit épouser un ambitieux milliardaire, mais sa rencontre avec leMec, le traiteur de la cérémonie, va tout remettre en question.",
                "Très investie dans sa carrière de metierCool, laMeuf retourne à villePaume, sa ville natale, pour veiller sur sa grand-mère. Celle-ci lui présente leMec, jeune metierVrai, qui aurait bien besoin d'aide pour organiser le bal de Noël.",
                "À quelques semaines de Noël, laMeuf est embauchée pour décorer les locaux de CandyCane Corp. Elle devra composer avec leMec, le PDG, qui a succédé à son père à la tête de l'entreprise, mais déteste Noël.",
                "Quand une jeune mariée se voit accorder un vœux par l'ange de Noël, elle souhaite devenir célibataire à nouveau. Mais sa vie de femme libre n'est pas aussi épanouissante qu'elle l'aurait cru, et elle se met en tête de reconquérir son mari.",
                "laMeuf et Mandie McKinnie sont rivales depuis que cette dernière a triché au concours annuel de cookies en 4e. Elles travaillent désormais dans la même école, et sont en compétition constante. L'arrivée en ville de leMec, un jeune metierVrai amoureux de la patisserie qui propose de faire renaître le concours annuel de cookies va mettre le feu aux poudres&nbsp;!",
                "laMeuf, jeune divorcée et metierCool, donnerait tout pour sa fille Emma. Forcée de fermer sa boutique de bigCity, le retour dans son etatPaume natal est rude. Heureusement, le professeur de musique d'Emma, leMec, les aide à s'ajuster à cette nouvelle vie.",
                "Deux New-Yorkais se retrouvent bloqués par une tempête de Neige à villePaume, etatPaume. laMeuf, désespérément romantique, doit trouver un moyen de se rendre chez ses beaux parents à temps pour sa soirée de fiançailles. Heureusement, leMec, célibataire endurci, va l'aider&hellip;",
                "Alors qu'elle vient d'abandonner son 3ème fiancé à l'autel, laMeuf jure de renoncer à toute relation jusqu'à ce qu'elle trouve «&nbsp;le bon&nbsp;». Mais sa rencontre avec leMec, metierVrai, célibataire endurci plein de charme, va mettre sa promesse à rude épreuve.",
                "Lors d'une soirée un peu trop arrosée, leMec a fait un pari&nbsp;: il doit convaincre une femme de l'épouser avant Noël, soit dans 4 semaines&nbsp;! Il jette son dévolu sur laMeuf, la talentueuse metierCool qu'il vient d'embaucher.",
                "laMeuf vient de perdre son emploi de metierCool. Elle rencontre par hasard leMec, patron surbooké qui la charge d'acheter ses cadeaux de Noël. Elle va lui transmettre l'esprit de Noël et bien plus encore&hellip;",
                "Quand on l'envoie superviser les travaux de rénovation d'un hôtel perdu à villePaume, etatPaume, laMeuf s'attends à passer le pire Noël de sa vie. C'est compter sans la présence de leMec, conducteur de chantier bourru qui va lui faire découvrir le charme de la vie à la campagne&hellip;",
                "laMeuf, journaliste à bigCity, bâcle un article capital. En guise de punition, on l'envoie à villePaume, etatPaume, faire un reportage sur leMec, metierVrai. Il est veuf, revêche et taciturne. Ils se détestent. Puis la magie de Noël entre en jeu&hellip;",
                "Fraîchement plaquée par son mari, laMeuf retourne vivre chez ses parents dans son etatPaume natal. Elle fait la rencontre de leMec, metierVrai grincheux et moqueur. Ils se détestent au premier regard&hellip;",
                "Lorsque laMeuf, metierCool, rencontre leMec, elle est loin de se douter qu'il est le prince héritier de Cénovie en visite incognito à villePaume, etatPaume.",
                "laMeuf, jeune héritière frivole, est envoyée par ses parents à villePaume, etatPaume pour apprendre la valeur du travail et le sens des autres. Heureusement, leMec, metierVrai, saura l'aider&hellip;",
                "La ville de villePaume, etatPaume est frappée par une tempête de neige. laMeuf, metierCool, est bloquée plusieurs jours et doit apprendre la patience. Heureusement, leMec, jeune metierVrai bourru, est là pour l'aider.",
                "Tout juste débarquée à villePaume, etatPaume après une rupture difficile, laMeuf, jeune citadine metierCool, fait la connaissance de leMec, metierVrai qui lui fait découvrir l'amour.",
                "Quand leMec, metierVrai romantique, et laMeuf, metierCool de bigCity qui ne croit plus en l'amour, se rencontrent, ils n'ont rien en commun. Et pourtant, ils vont devoir faire équipe pour organiser la parade de Noël de villePaume, etatPaume.",
                "laMeuf, jeune metierCool à bigCity, semble tout avoir&nbsp;: une carrière en constante évolution et un riche fiancé prénommé Alistair. À la mort de sa grand-mère, elle hérite de sa ferme de Noël à villePaume, etatPaume. Venue faire le tour de la propriété pour la vendre au riche promoteur Milton McDollar, elle rencontre le charmant leMec, metierVrai très attaché à la ferme&hellip;",
                "Quand Elena et laMeuf, sœurs jumelles se retrouvent invitées à des soirées de Noël qui ne les intéressent pas, elles décident d'échanger leur place pendant les fêtes. Les leçons qu'elles vont apprendre vont changer leurs vies à jamais.",
                "laMeuf vit les pires vacances de sa vie&nbsp;: son fiancé vient de la plaquer et une grève aérienne l'oblige à prolonger ses vacances à villePaume, etatPaume. Pour ne rien arranger, l'hôtel de luxe qu'elle avait réservé est plein, et elle doit partager une auberge rustique avec leMec, metierVrai bourru."
        };

        private static String draw(String[] array) {
            return array[(int) (Math.random() * array.length)];
        }

        static String generateStory() {
            String storyFull = draw(histoire)
                    .replace("laMeuf", draw(nomMeuf))
                    .replace("leMec", draw(nomMec))
                    .replace("etatPaume", draw(etat))
                    .replace("villePaume", draw(petiteVille))
                    .replace("bigCity", draw(grandeVille))
                    .replace("metierCool", draw(metierMeuf))
                    .replace("metierVrai", draw(metierMec));

            storyFull = StringEscapeUtils.unescapeHtml4(storyFull);
            String titleFull = draw(titre);
            int posterIndex = (int) (Math.random() * 83);

            HashMap<String, String> attachment = new HashMap<>();
            attachment.put("fallback", "**" + titleFull + "**\n" + storyFull);
            attachment.put("title", titleFull);
            attachment.put("text", storyFull);
            attachment.put("image_url", "http://lunatopia.fr/media/pages/blog/films-de-noel/" + posterIndex + ".jpg");

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("response_type", "in_channel");
            jsonObject.put("attachments", Collections.singletonList(attachment));

            return jsonObject.toString();
        }
    }
}
