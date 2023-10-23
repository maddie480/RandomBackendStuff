package com.max480.randomstuff.backend.discord.questcommunitybot.gamestats;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import com.max480.randomstuff.backend.discord.questcommunitybot.Utils;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

public class SteamCommand implements BotCommand {
    private static final Logger log = LoggerFactory.getLogger(SteamCommand.class);

    private final Map<Long, List<String>> steamProfilesForMessage = new HashMap<>();
    private final Map<Long, Long> allowedUsersForMessage = new HashMap<>();

    private final Map<String, String> discordToSteamUsers = new HashMap<>();

    private final GamestatsManager gamestatsManager;

    public SteamCommand(GamestatsManager gamestatsManager) {
        this.gamestatsManager = gamestatsManager;
        gamestatsManager.setSteamCommand(this);
    }

    @Override
    public String getCommandName() {
        return "steam";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"pseudo|disable*"};
    }

    @Override
    public String getShortHelp() {
        return "Associe ton compte Steam à ton compte Discord pour les commandes `!gamestats` et `!played`";
    }

    @Override
    public String getFullHelp() {
        return """
                Le bot recherchera ton pseudo dans la communauté Steam, et te proposera les différents profils trouvés, pour que tu puisses choisir le tien.
                Si tu changes d'avis, utilise `!steam disable`, et le bot oubliera ton pseudo et tous tes temps de jeu.

                **Cela n'est utile que si tu as rendu tes temps de jeu publics.** Sinon, bah... le bot ne pourra pas les voir. Et pour faire des gamestats dessus, c'est un peu dommage.""";
    }

    @Override
    public boolean isAdminOnly() {
        return false;
    }

    @Override
    public boolean areParametersValid(String[] parameters) {
        return true;
    }

    public void loadFile(Guild guild) {
        try (BufferedReader reader = new BufferedReader(new FileReader("steam_connections.txt"))) {
            String s;
            while ((s = reader.readLine()) != null) {
                if (s.contains(";")) {
                    String[] split = s.split(";");
                    discordToSteamUsers.put(split[0], split[1]);
                }
            }
        } catch (IOException e) {
            log.error("Impossible de lire les associations Steam", e);
        }

        // vérifier que les utilisateurs sont toujours sur le serveur
        for (String discordId : new ArrayList<>(discordToSteamUsers.keySet())) {
            Member member = guild.getMemberById(discordId);
            if (member != null) {
                log.debug("Gamestats de Steam : l'utilisateur {} existe", member);
            } else {
                log.warn("Gamestats de Steam : l'utilisateur {} n'existe plus !", discordId);
                discordToSteamUsers.remove(discordId);
                save();
            }
        }

        log.debug("Map des comptes Discord avec leurs comptes Steam = {}", discordToSteamUsers);
    }

    void refreshSteamStats(JDA client) throws IOException {
        log.debug("Refreshing Steam stats");

        final HashMap<String, HashMap<Long, Integer>> steamGamestats = new HashMap<>();

        for (Map.Entry<String, String> account : discordToSteamUsers.entrySet()) {
            addSteamGamestatsWithRetry(client, steamGamestats, account);
        }

        log.debug("Terminé ! Les gamestats de Steam contiennent {} jeux", steamGamestats.size());
        gamestatsManager.putSteamGamestats(steamGamestats);
    }

    private static void addSteamGamestatsWithRetry(JDA client,
                                                   HashMap<String, HashMap<Long, Integer>> steamGamestats,
                                                   Map.Entry<String, String> account) throws IOException {

        User user = client.getUserById(account.getKey());
        int tries = 3;
        log.debug("Je tente de récupérer les stats de {} (steamid {}), tentatives restantes = {}", user, account.getValue(), tries);

        ConnectionUtils.runWithRetry(() -> {
            addSteamGamestatsForUser(steamGamestats, account);
            return null;
        });
    }

    private static void addSteamGamestatsForUser(HashMap<String, HashMap<Long, Integer>> steamGamestats,
                                                 Map.Entry<String, String> account) throws IOException {
        Long discordId = Long.parseLong(account.getKey());
        String steamId = account.getValue();

        JSONObject games;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://api.steampowered.com/IPlayerService/GetOwnedGames/v0001/?key="
                + SecretConstants.STEAM_WEB_API_KEY + "&steamid=" + steamId + "&include_played_free_games=1&include_appinfo=1")) {

            games = new JSONObject(IOUtils.toString(is, UTF_8));
        }

        if (!games.getJSONObject("response").has("games")) {
            log.warn("Could not retrieve Steam gamestats for account {}! It probably has private play times.", steamId);
            return;
        }

        games.getJSONObject("response").getJSONArray("games").forEach(gameObj -> {
            JSONObject game = (JSONObject) gameObj;

            String name = game.getString("name");
            Integer gameTimeMinutes = game.getInt("playtime_forever");

            if (steamGamestats.containsKey(name)) {
                steamGamestats.get(name).put(discordId, gameTimeMinutes);
            } else {
                HashMap<Long, Integer> newMap = new HashMap<>();
                newMap.put(discordId, gameTimeMinutes);
                steamGamestats.put(name, newMap);
            }
        });
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        if (parameters[0].equals("disable")) {
            removeAccount(event.getChannel(), event.getAuthor());
            return;
        }

        // c'est un peu long (~2 secondes) alors on envoie un "en train d'écrire"
        event.getChannel().sendTyping().queue();

        // 1. Récupérer un cookie de session, parce que c'est obligatoire
        List<String> cookieJar;

        {
            log.debug("Récupération de cookies de session pour Steam...");
            HttpURLConnection homepage = ConnectionUtils.openConnectionWithTimeout("https://steamcommunity.com");

            homepage.connect();

            if (homepage.getResponseCode() == 200) {
                cookieJar = homepage.getHeaderFields().getOrDefault("Set-Cookie", Collections.emptyList())
                        .stream().map(cookie -> cookie.substring(0, cookie.indexOf(";")))
                        .collect(Collectors.toList());
            } else {
                throw new IOException("Page d'accueil - Steam a répondu avec un code " + homepage.getResponseCode());
            }
        }

        log.debug("J'ai récupéré des cookies pour Steam : {}", cookieJar);

        List<String> results;

        {
            // 2. Recherche
            log.debug("Lancement de la recherche pour {}...", parameters[0]);

            HttpURLConnection searchRequest = ConnectionUtils.openConnectionWithTimeout("https://steamcommunity.com/search/SearchCommunityAjax?text="
                    + URLEncoder.encode(parameters[0], UTF_8) + "&filter=users&steamid_user=false&page=1&"
                    + cookieJar.stream().filter(cookie -> cookie.startsWith("sessionid=")).findFirst().orElse(""));

            searchRequest.setRequestProperty("Cookie", String.join("; ", cookieJar));
            searchRequest.connect();

            if (searchRequest.getResponseCode() == 200) {
                JSONObject response = new JSONObject(IOUtils.toString(ConnectionUtils.connectionToInputStream(searchRequest), UTF_8));

                Document responseBody = Jsoup.parse(response.getString("html"));
                results = responseBody.select("a.searchPersonaName").stream()
                        .map(aTag -> aTag.attr("href"))
                        .collect(Collectors.toList());
            } else {
                throw new IOException("Recherche - Steam a répondu avec un code " + searchRequest.getResponseCode());
            }
        }

        log.debug("J'ai trouvé : {}", results);

        if (results.isEmpty()) {
            event.getChannel().sendMessage("Désolé, je n'ai pas trouvé d'utilisateur Steam correspondant à `" + parameters[0] + "`. Essaie encore !").queue();
        } else {
            event.getChannel().sendMessage("J'ai trouvé " + results.size() + (results.size() > 1 ? " résultats" : " résultat") + " " +
                    "pour `" + parameters[0] + "`. Clique sur :white_check_mark: si c'est ton profil"
                    + (results.size() > 1 ? " ou sur :track_next: pour afficher le résultat suivant" : "") + ".\n\n" +
                    ":arrow_right: " + results.get(0)).queue(message -> {

                steamProfilesForMessage.put(message.getIdLong(), results);
                allowedUsersForMessage.put(message.getIdLong(), event.getAuthor().getIdLong());
                log.debug("Steam reactions = {}", steamProfilesForMessage);
                log.debug("Steam authors = {}", allowedUsersForMessage);

                message.addReaction(Utils.getEmojiFromUnicodeHex("e29c85")).queue();
                if (results.size() > 1) {
                    message.addReaction(Utils.getEmojiFromUnicodeHex("e28fad")).queue();
                }

                Runnable clear = () -> {
                    steamProfilesForMessage.remove(message.getIdLong());
                    allowedUsersForMessage.remove(message.getIdLong());
                    log.debug("Cleared reactions for timeout, new map = {}", steamProfilesForMessage);
                    log.debug("Steam authors = {}", allowedUsersForMessage);
                };

                message.clearReactions().queueAfter(30, TimeUnit.MINUTES, success -> clear.run());
            });
        }
    }

    boolean hasUser(Long discordId) {
        return discordToSteamUsers.containsKey("" + discordId);
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        long messageId = event.getMessageIdLong();
        String reactionHex = Utils.getUnicodeHexFromEmoji(event.getEmoji().getName());
        MessageChannel channel = event.getChannel();

        if (event.getUserIdLong() == allowedUsersForMessage.getOrDefault(messageId, -1L)) {
            if (reactionHex.equals("e28fad")) {
                if (channel instanceof TextChannel textChannel) textChannel.clearReactionsById(messageId).queue();

                // dépiler un résultat et afficher le suivant
                List<String> results = steamProfilesForMessage.get(messageId);
                results.remove(0);

                steamProfilesForMessage.remove(messageId);
                allowedUsersForMessage.remove(messageId);

                channel.sendMessage(":arrow_right: " + results.get(0)).queue(message -> {
                    steamProfilesForMessage.put(message.getIdLong(), results);
                    allowedUsersForMessage.put(message.getIdLong(), event.getUserIdLong());

                    log.debug("Steam reactions = {}", steamProfilesForMessage);
                    log.debug("Steam authors = {}", allowedUsersForMessage);

                    message.addReaction(Utils.getEmojiFromUnicodeHex("e29c85")).queue();
                    if (results.size() > 1) {
                        message.addReaction(Utils.getEmojiFromUnicodeHex("e28fad")).queue();
                    }

                    Runnable clear = () -> {
                        steamProfilesForMessage.remove(message.getIdLong());
                        allowedUsersForMessage.remove(message.getIdLong());
                        log.debug("Cleared reactions for timeout, new map = {}", steamProfilesForMessage);
                        log.debug("Steam authors = {}", allowedUsersForMessage);
                    };

                    message.clearReactions().queueAfter(30, TimeUnit.MINUTES, success -> clear.run());
                });

                return true;
            }

            if (reactionHex.equals("e29c85")) {
                if (channel instanceof TextChannel textChannel) textChannel.clearReactionsById(messageId).queue();

                // dépiler un résultat et afficher le suivant
                String correctUrl = steamProfilesForMessage.get(messageId).get(0);

                steamProfilesForMessage.remove(messageId);
                allowedUsersForMessage.remove(messageId);
                log.debug("Steam reactions = {}", steamProfilesForMessage);
                log.debug("Steam authors = {}", allowedUsersForMessage);

                try {
                    log.debug("Récupération du steamid correspondant à {}...", correctUrl);
                    channel.sendTyping().queue();
                    InputStream is = ConnectionUtils.openStreamWithTimeout(correctUrl);

                    try (BufferedReader input = new BufferedReader(new InputStreamReader(is, UTF_8))) {
                        String line;
                        while ((line = input.readLine()) != null && !line.contains("g_rgProfileData =")) ;

                        if (line == null) {
                            throw new IOException("Impossible de trouver le steamid de l'utilisateur");
                        } else {
                            line = line.substring(line.indexOf("g_rgProfileData =") + "g_rgProfileData =".length(), line.lastIndexOf(";")).trim();

                            JSONObject object = new JSONObject(line);
                            String steamid = object.getString("steamid");
                            log.debug("J'ai trouvé : {}", steamid);

                            discordToSteamUsers.put(event.getUserId(), steamid);
                            save();

                            channel.sendTyping().queue();
                            try {
                                refreshSteamStats(channel.getJDA());
                                channel.sendMessage(":white_check_mark: C'est enregistré !").queue();
                            } catch (Exception e) {
                                log.error("Error during forced Steam refresh", e);
                                channel.sendMessage(":x: Ton profil a bien été enregistré, mais il y a eu un problème de relevé des stats Steam.").queue();
                            }

                            log.debug("Map des comptes Discord avec leurs comptes Steam = {}", discordToSteamUsers);
                        }
                    }
                } catch (IOException e) {
                    log.error("Impossible de communiquer avec Steam", e);
                    channel.sendMessage("Désolé, quelque chose s'est mal passé et je n'ai pas pu communiquer avec Steam. " +
                            "\n... <@" + SecretConstants.OWNER_ID + ">, tu peux jeter un oeil aux logs ?").queue();
                }

                return true;
            }
        }

        return false;
    }

    private void removeAccount(MessageChannel chan, User author) {
        if (discordToSteamUsers.containsKey(author.getId())) {
            discordToSteamUsers.remove(author.getId());
            save();

            chan.sendTyping().queue();
            try {
                refreshSteamStats(chan.getJDA());
                chan.sendMessage(":white_check_mark: Ton compte Steam a bien été dissocié de ton compte Discord.").queue();
            } catch (Exception e) {
                log.error("Error during forced Steam refresh", e);
                chan.sendMessage(":x: Ton compte Steam a bien été dissocié de ton compte Discord, " +
                        "mais il y a eu un problème de relevé des stats Steam, donc il est possible que tes stats n'aient pas été supprimées.").queue();
            }
        } else {
            chan.sendMessage("Tu n'as pas de compte Steam associé à ton compte Discord.\n" +
                    "Pour en associer un, utilise `!steam [pseudo]`.").queue();
        }
    }

    private void save() {
        log.debug("Sauvegarde des associations Discord / Steam");

        try (FileWriter file = new FileWriter("steam_connections.txt")) {
            for (Map.Entry<String, String> entry : discordToSteamUsers.entrySet()) {
                file.write(entry.getKey() + ";" + entry.getValue() + "\n");
            }
        } catch (IOException e) {
            log.error("Impossible d'enregistrer les associations Steam", e);
        }
    }
}
