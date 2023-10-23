package com.max480.randomstuff.backend.discord.questcommunitybot.gamestats;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.text.DecimalFormat;
import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

public class GamestatsManager {
    private static final Logger log = LoggerFactory.getLogger(GamestatsManager.class);

    private final Set<String> enabledList = new HashSet<>();

    static final int DAILY = 0;
    static final int MONTHLY = 1;
    static final int ALLTIME = 2;
    static final int STEAM = 3;
    static final int WEEKLY = 4;

    // catégorie -> nom du jeu -> id du joueur -> temps de jeu
    private ArrayList<HashMap<String, HashMap<Long, Integer>>> gamestats = new ArrayList<>();

    private SteamCommand steamCommand;

    void setSteamCommand(SteamCommand steamCommand) {
        this.steamCommand = steamCommand;
    }

    public void run(Guild guild) throws IOException {
        loadFile(guild);

        new Thread(() -> {
            while (true) {
                try {
                    updateStats(guild);

                    if (ZonedDateTime.now().getHour() == 0) {
                        if (ZonedDateTime.now().getMinute() == 0) {
                            autoPurge();
                            wipeStats(DAILY);

                            if (ZonedDateTime.now().getDayOfWeek() == DayOfWeek.MONDAY) {
                                wipeStats(WEEKLY);
                            }

                            if (ZonedDateTime.now().getDayOfMonth() == 1) {
                                wipeStats(MONTHLY);
                            }
                        }
                    }

                    if (ZonedDateTime.now().getMinute() == 0) {
                        steamCommand.refreshSteamStats(guild.getJDA());
                    }
                } catch (Exception e) {
                    log.error("Uncaught exception during gamestats refresh", e);
                }

                try {
                    Thread.sleep(60000 - (ZonedDateTime.now().getSecond() * 1000
                            + ZonedDateTime.now().getNano() / 1_000_000) + 50);
                } catch (InterruptedException e) {
                    log.error("Sleep interrupted", e);
                }
            }
        }).start();
    }

    private void loadFile(Guild guild) throws IOException {
        // charger
        try (ObjectInputStream input = new ObjectInputStream(new FileInputStream("gamestats.ser"))) {
            gamestats = (ArrayList<HashMap<String, HashMap<Long, Integer>>>) input.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(e);
        }

        try (BufferedReader br = new BufferedReader(new FileReader("played_command_users.txt"))) {
            String s;
            while ((s = br.readLine()) != null) {
                enabledList.add(s);
            }
        }

        // vérifier que les utilisateurs sont toujours sur le serveur
        for (String discordId : new ArrayList<>(enabledList)) {
            Member member = guild.getMemberById(discordId);
            if (member != null) {
                log.debug("Utilisateurs de !gamestats : l'utilisateur {} existe", member);
            } else {
                log.warn("Utilisateurs de !gamestats : l'utilisateur {} n'existe plus !", discordId);
                enabledList.remove(discordId);

                try (BufferedWriter bw = new BufferedWriter(new FileWriter("played_command_users.txt"))) {
                    for (String bl : enabledList) {
                        bw.write(bl + "\n");
                    }
                }
            }
        }
    }

    private void updateStats(Guild guild) {
        if (guild.getJDA().getStatus() != JDA.Status.CONNECTED) {
            log.warn("Je ne collecte pas les gamestats parce que le bot n'est pas connecté. (statut = {})", guild.getJDA().getStatus());
            return;
        }

        guild.getMembers().forEach(this::countStatsForMember);

        try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(new FileOutputStream("gamestats.ser"))) {
            objectOutputStream.writeObject(gamestats);
        } catch (IOException e) {
            log.error("Impossible d'enregistrer les gamestats", e);
        }
    }

    private void countStatsForMember(net.dv8tion.jda.api.entities.Member member) {
        if (!member.getUser().isBot()) {
            for (Activity game : member.getActivities()) {
                if (game.getType() == Activity.ActivityType.PLAYING) {
                    String playedGame = game.getName();
                    long userId = member.getUser().getIdLong();

                    if (!enabledList.contains(Long.toString(userId))) {
                        continue;
                    }

                    for (int i = 0; i < 5; i++) {
                        if (i == STEAM) i++;

                        if (!gamestats.get(i).containsKey(playedGame)) {
                            log.debug("C'est la première fois que quelqu'un joue à {} dans le type {}", playedGame, i);

                            HashMap<Long, Integer> playTime = new HashMap<>();
                            playTime.put(userId, 1);

                            gamestats.get(i).put(playedGame, playTime);
                        } else {
                            int playTime = gamestats.get(i).get(playedGame).getOrDefault(userId, 0);
                            gamestats.get(i).get(playedGame).put(userId, playTime + 1);
                        }
                    }
                }
            }
        }
    }

    void putSteamGamestats(HashMap<String, HashMap<Long, Integer>> steamGamestats) {
        gamestats.set(STEAM, steamGamestats);
    }

    private static class Game implements Comparable<Game> {
        String gameName;
        int playTime;
        int playerCount;

        @Override
        public int compareTo(@NotNull GamestatsManager.Game o) {
            return o.playTime - playTime;
        }
    }

    String postStats(int type) {
        log.debug("Récupération du top 10 du type {}", type);
        String sorted = getGamesSortedByPlayTime(type).stream()
                .limit(10)
                .map(this::gameToStatString)
                .collect(Collectors.joining("\n"));

        if (type == STEAM) {
            return "Voici les jeux les plus joués sur Steam par les membres du serveur :\n" + sorted;
        }
        return "Voici les jeux les plus joués sur le serveur " +
                (type == DAILY ? "aujourd'hui " : (type == WEEKLY ? "cette semaine " : (type == MONTHLY ? "ce mois-ci " : "")))
                + ":\n" + sorted;
    }

    String postReverseStats(int type) {
        log.debug("Récupération du flop 10 de type {}", type);
        List<Game> games = getGamesSortedByPlayTime(type);
        Collections.reverse(games);

        String sorted = games.stream()
                .limit(10)
                .map(this::gameToStatString)
                .collect(Collectors.joining("\n"));

        if (type == STEAM) {
            return "Voici les jeux les _moins_ joués sur Steam par les membres du serveur :\n" + sorted;
        }
        return "Voici les jeux les _moins_ joués sur le serveur (mais qui ont été joués quand même) " +
                (type == DAILY ? "aujourd'hui " : (type == WEEKLY ? "cette semaine " : (type == MONTHLY ? "ce mois-ci " : ""))) + ":\n" + sorted;
    }

    @NotNull
    private List<Game> getGamesSortedByPlayTime(int type) {
        return gamestats.get(type).entrySet().stream()
                .map(entry -> {
                    Game game = new Game();
                    game.gameName = entry.getKey();
                    game.playTime = entry.getValue().values().stream().mapToInt(i -> i).sum();
                    game.playerCount = entry.getValue().size();
                    return game;
                })
                .sorted()
                .collect(Collectors.toList());
    }

    @NotNull
    private String gameToStatString(Game game) {
        return "- **" + game.gameName + "** : " + formatTime(game.playTime)
                + " avec " + game.playerCount + (game.playerCount == 1 ? " joueur" : " joueurs");
    }

    String getStatsForGame(String game, MessageChannel channel) {
        // essayer de trouver la bonne casse
        Set<String> gameSet = new HashSet<>(gamestats.get(ALLTIME).keySet());
        gameSet.addAll(gamestats.get(STEAM).keySet());

        if (!gameSet.contains(game)) {
            String gameNameToCorrect = game;
            List<String> candidates = gameSet.stream()
                    .filter(name -> name.equalsIgnoreCase(gameNameToCorrect))
                    .collect(Collectors.toList());

            log.debug("Résultat de la recherche case-insensitive : {}", candidates);
            if (candidates.size() == 1) {
                log.debug("J'ai trouvé la bonne casse pour {} : c'est {}", game, candidates.get(0));
                game = candidates.get(0);
            }
        }

        if (!gameSet.contains(game)) {
            return "Désolé, je ne trouve pas ce jeu (`" + game + "`).";
        } else {
            StringBuilder builder = new StringBuilder("Temps de jeu pour **" + game + "** : ");

            if (gamestats.get(DAILY).containsKey(game)) {
                appendStatsForType(channel.getJDA(), game, builder, DAILY, "aujourd'hui");
            }
            if (gamestats.get(WEEKLY).containsKey(game)) {
                appendStatsForType(channel.getJDA(), game, builder, WEEKLY, "cette semaine");
            }
            if (gamestats.get(MONTHLY).containsKey(game)) {
                appendStatsForType(channel.getJDA(), game, builder, MONTHLY, "ce mois-ci");
            }
            if (gamestats.get(ALLTIME).containsKey(game)) {
                appendStatsForType(channel.getJDA(), game, builder, ALLTIME, "depuis le 18/12/2018");
            }
            if (gamestats.get(STEAM).containsKey(game)) {
                appendStatsForType(channel.getJDA(), game, builder, STEAM, "sur Steam");
            }

            final String finalGame = game;
            new Thread("Game info resolver") {
                @Override
                public void run() {
                    MessageEmbed embed = GameDB.findGame(finalGame);
                    if (embed != null) {
                        channel.sendMessageEmbeds(embed).queue();
                    }
                }
            }.start();

            return builder.toString();
        }
    }

    String getUserStats(User user, int type, boolean isSelfUser) {
        if (!enabledList.contains(user.getId())) {
            if (!isSelfUser) {
                return user.getName() + " n'a pas activé les gamestats.";
            } else {
                return "Tu n'a pas activé les gamestats. Utilise `!toggle_gamestats` pour les activer.";
            }
        }

        String gameList = gamestats.get(type).entrySet().stream()
                .filter(entry -> entry.getValue().containsKey(user.getIdLong()))
                .map(entry -> gamestatsEntryToGame(user, entry))
                .sorted()
                .limit(10)
                .map(game -> "- **" + game.gameName + "** : " + formatTime(game.playTime))
                .collect(Collectors.joining("\n"));

        if (gameList.isEmpty()) {
            switch (type) {
                case DAILY -> {
                    return "Je n'ai pas vu " + user.getName() + " jouer à un jeu aujourd'hui.";
                }
                case WEEKLY -> {
                    return "Je n'ai pas vu " + user.getName() + " jouer à un jeu cette semaine.";
                }
                case MONTHLY -> {
                    return "Je n'ai pas vu " + user.getName() + " jouer à un jeu ce mois-ci.";
                }
                case STEAM -> {
                    if (!steamCommand.hasUser(user.getIdLong())) {
                        return user.getName() + " n'a pas associé son compte Steam à son compte Discord avec la commande `!steam`.";
                    } else {
                        return user.getName() + " n'a pas de temps de jeu. Soit sa liste de jeux est privée, soit il/elle n'a joué à aucun jeu. :shrug:";
                    }
                }
                default -> {
                    return "Je n'ai jamais vu " + user.getName() + " jouer à un jeu.";
                }
            }
        } else {
            return "Top 10 des jeux joués par " + user.getName() +
                    (type == DAILY ? " aujourd'hui" : (type == WEEKLY ? " cette semaine" : (type == MONTHLY ? " ce mois-ci" : (type == STEAM ? " sur Steam" : ""))))
                    + " :\n" + gameList;
        }
    }

    public Map<String, String> getUserStatsForProfile(User user) {
        List<Game> gameList = gamestats.get(ALLTIME).entrySet().stream()
                .filter(entry -> entry.getValue().containsKey(user.getIdLong()))
                .map(entry -> gamestatsEntryToGame(user, entry))
                .sorted()
                .collect(Collectors.toList());

        List<Game> steamGameList = gamestats.get(STEAM).entrySet().stream()
                .filter(entry -> entry.getValue().containsKey(user.getIdLong()))
                .map(entry -> gamestatsEntryToGame(user, entry))
                .sorted()
                .collect(Collectors.toList());

        log.debug("Jeux Discord = {}", gameList);
        log.debug("Jeux Steam = {}", steamGameList);

        for (Game steamGame : steamGameList) {
            Game discordGame = gameList.stream().filter(game -> game.gameName.equals(steamGame.gameName)).findFirst().orElse(null);

            if (discordGame != null && discordGame.playTime < steamGame.playTime) {
                // remplacer
                log.debug("Plus de temps sur Steam que Discord pour {} -> remplacer", discordGame.gameName);
                gameList.remove(discordGame);
                gameList.add(steamGame);
            } else if (discordGame == null) {
                // ajouter
                log.debug("{} uniquement sur Steam -> ajouter", steamGame.gameName);
                gameList.add(steamGame);
            }
        }

        gameList.sort(Game::compareTo);

        Map<String, String> map = new LinkedHashMap<>();
        for (Game game : gameList) {
            map.put(game.gameName, formatTime(game.playTime));
        }
        return map;
    }

    @NotNull
    private GamestatsManager.Game gamestatsEntryToGame(User user, Map.Entry<String, HashMap<Long, Integer>> entry) {
        Game game = new Game();
        game.gameName = entry.getKey();
        game.playTime = entry.getValue().get(user.getIdLong());
        return game;
    }

    private void appendStatsForType(JDA client, String game, StringBuilder builder, int type, String typeName) {
        List<Game> sortedGames = getGamesSortedByPlayTime(type);
        int position = sortedGames.indexOf(sortedGames.stream().filter(g -> g.gameName.equals(game)).findFirst().orElse(null)) + 1;

        builder.append("\n- **").append(formatTime(gamestats.get(type).get(game).values().stream().mapToInt(i -> i).sum())).append("** ").append(typeName)
                .append(" (").append(position).append(position == 1 ? "er / " : "ème / ").append(gamestats.get(type).size())
                .append(") :\n    - ");

        builder.append(gamestats.get(type).get(game).entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> -entry.getValue()))
                .map(entry -> formatTime(entry.getValue()) + " pour " +
                        Optional.ofNullable(client.getUserById(entry.getKey()))
                                .map(User::getName)
                                .orElse("[utilisateur inconnu]"))
                .collect(Collectors.joining("\n    - ")));
    }

    private String formatTime(int time) {
        int hours = time / 60;
        int minutes = time % 60;

        if (hours == 0) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        } else {
            return hours + "h" + new DecimalFormat("00").format(minutes);
        }
    }

    private void wipeStats(int type) {
        log.warn("RAZ des stats de type {}", type);
        gamestats.set(type, new HashMap<>());
    }

    void toggleGamestats(MessageChannel channel, User user) throws IOException {
        if (enabledList.contains(user.getId())) {
            enabledList.remove(user.getId());
            channel.sendMessage(":white_check_mark: Tu as **désactivé** la collecte des gamestats.\n" +
                    "Si tu ne changes pas d'avis, tes gamestats seront supprimées du bot à minuit.").queue();
        } else {
            enabledList.add(user.getId());
            channel.sendMessage(":white_check_mark: Tu as **activé** la collecte des gamestats.\n" +
                    "Tes temps de jeu seront maintenant inclus dans la commande `!gamestats`, et tu pourras voir tes statistiques avec `!played`.").queue();
        }

        try (BufferedWriter bw = new BufferedWriter(new FileWriter("played_command_users.txt"))) {
            for (String bl : enabledList) {
                bw.write(bl + "\n");
            }
        }
    }

    private void autoPurge() {
        for (HashMap<String, HashMap<Long, Integer>> category : gamestats) {
            for (HashMap<Long, Integer> timesPerUser : category.values()) {
                Set<Long> toConsider = new HashSet<>(timesPerUser.keySet());
                for (Long userId : toConsider) {
                    if (!enabledList.contains(userId.toString())) {
                        log.warn("Deleting play time for user {}", userId);
                        timesPerUser.remove(userId);
                    }
                }
            }

            Set<String> games = new HashSet<>(category.keySet());
            for (String game : games) {
                if (category.get(game).isEmpty()) {
                    log.warn("Deleting now empty game {}", game);
                    category.remove(game);
                }
            }
        }
    }
}
