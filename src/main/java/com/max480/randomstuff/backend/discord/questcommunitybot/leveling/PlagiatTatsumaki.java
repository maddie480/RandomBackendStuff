package com.max480.randomstuff.backend.discord.questcommunitybot.leveling;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.discord.questcommunitybot.Utils;
import com.max480.randomstuff.backend.discord.questcommunitybot.gamestats.GameDB;
import com.max480.randomstuff.backend.discord.questcommunitybot.gamestats.GamestatsManager;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.utils.FileUpload;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Queue;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.awt.Image.SCALE_SMOOTH;
import static java.awt.image.BufferedImage.TYPE_INT_ARGB;
import static java.time.temporal.ChronoUnit.DAYS;

public class PlagiatTatsumaki {
    private static final Logger logger = LoggerFactory.getLogger(PlagiatTatsumaki.class);

    private static final DecimalFormat separated = new DecimalFormat("#,##0");

    private final GamestatsManager gamestatsManager;

    public PlagiatTatsumaki(GamestatsManager gamestatsManager, Guild guild) throws IOException {
        this.gamestatsManager = gamestatsManager;

        roleIds = Arrays.asList(
                // G DÉPENÇER SEN KOMPTÉ (600900 pièces)
                520278937889275917L,

                // rôles par classe (1000 pièces)
                520277356565233664L,
                520276258198781974L,
                520275551630393344L,
                520274695287996436L,
                520273045399797770L,

                // pièces (100 => 10 => 1)
                520271287776575528L,
                520270775924555816L,
                520269197943767055L,

                // rôles gratuits
                349584207716155394L,
                349584154289111053L,
                349584189231857667L,
                349584129450573826L,
                349651944341635083L
        );

        rolePrices = Arrays.asList(
                // G DÉPENÇER SEN KOMPTÉ (600900 pièces)
                600900,

                // rôles par classe (1000 pièces)
                1000,
                1000,
                1000,
                1000,
                1000,

                // pièces (100 => 10 => 1)
                100,
                10,
                1,

                // rôles gratuits
                0,
                0,
                0,
                0,
                0
        );

        load(guild);
        logger.debug("""
                        Les stats Tatsumaki ont été chargées :\s
                        cash = {}
                        xp = {}
                        rep = {}
                        lastDailyAt = {}
                        lastRepAt = {}
                        boughtBackgrounds = {}
                        dailyStreak = {}
                        boughtGameBackgrounds = {}
                        ownedPaidRoles = {}""",
                cash, xp, rep, lastDailyAt, lastRepAt, boughtBackgrounds, dailyStreak, boughtGameBackgrounds, ownedPaidRoles);
    }

    // =============

    private static class PendingTransaction {
        long from;
        long to;
        long amount;
        String backgroundNameUrlEncoded;
        String backgroundUrl;
    }

    private static class GameBackground {
        final String gameNameUrlEncoded;
        final String backgroundUrl;

        GameBackground(String gameNameUrlEncoded, String backgroundUrl) {
            this.gameNameUrlEncoded = gameNameUrlEncoded;
            this.backgroundUrl = backgroundUrl;
        }

        public String toString() {
            return this.gameNameUrlEncoded + " @ " + this.backgroundUrl;
        }
    }

    private static class UserAndScore {
        long id;
        long rank;
        String userName;
        long score;
    }

    private static class Background {
        final String fileName;
        final String name;
        final String nameUrlEncoded;
        final long author;
        final int price;

        Background(String fileName) {
            String[] split = fileName.split(";");

            this.fileName = fileName;
            this.name = URLDecoder.decode(split[0], StandardCharsets.UTF_8);
            this.nameUrlEncoded = split[0];
            this.author = Long.parseLong(split[1]);
            this.price = Integer.parseInt(split[2].substring(0, split[2].indexOf(".")));
        }
    }

    private ConcurrentHashMap<Long, Long> cash = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Long> xp = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Long> rep = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, ZonedDateTime> lastSpokeAt = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, ZonedDateTime> lastDailyAt = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, ZonedDateTime> lastRepAt = new ConcurrentHashMap<>();

    private ConcurrentHashMap<Long, ArrayList<String>> boughtBackgrounds = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Integer> dailyStreak = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, ArrayList<String>> boughtGameBackgrounds = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, ArrayList<Long>> ownedPaidRoles = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, PendingTransaction> transactions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PendingTransaction> buyBackgroundTransactions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PendingTransaction> buyRoleTransactions = new ConcurrentHashMap<>();

    private void load(Guild guild) throws IOException {
        try (ObjectInputStream stream = new ObjectInputStream(new FileInputStream("tatsumaclone.ser"))) {
            cash = (ConcurrentHashMap<Long, Long>) stream.readObject();
            xp = (ConcurrentHashMap<Long, Long>) stream.readObject();
            rep = (ConcurrentHashMap<Long, Long>) stream.readObject();
            lastDailyAt = (ConcurrentHashMap<Long, ZonedDateTime>) stream.readObject();
            lastRepAt = (ConcurrentHashMap<Long, ZonedDateTime>) stream.readObject();
            boughtBackgrounds = (ConcurrentHashMap<Long, ArrayList<String>>) stream.readObject();
            dailyStreak = (ConcurrentHashMap<Long, Integer>) stream.readObject();
            boughtGameBackgrounds = (ConcurrentHashMap<Long, ArrayList<String>>) stream.readObject();
            ownedPaidRoles = (ConcurrentHashMap<Long, ArrayList<Long>>) stream.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(e);
        }

        if (ownedPaidRoles.isEmpty()) {
            logger.info("Initializing ownedPaidRoles map");
            for (Member member : guild.getMembers()) {
                List<Long> allPaidRoles = member.getRoles().stream()
                        .filter(userRole -> roleIds.contains(userRole.getIdLong())
                                && rolePrices.get(roleIds.indexOf(userRole.getIdLong())) != 0)
                        .map(Role::getIdLong)
                        .toList();

                if (!allPaidRoles.isEmpty()) {
                    ownedPaidRoles.put(member.getUser().getIdLong(), new ArrayList<>(allPaidRoles));
                }
            }
        }

        for (Map<Long, ?> map : Arrays.asList(cash, xp, rep, lastDailyAt, lastRepAt, boughtBackgrounds, dailyStreak, boughtGameBackgrounds, ownedPaidRoles)) {
            for (Long l : new ArrayList<>(map.keySet())) {
                if (guild.getMemberById(l) == null) {
                    logger.warn("On oublie l'utilisateur {} qui n'existe plus !", l);
                    map.remove(l);
                }
            }
        }

        try (Stream<Path> list = Files.list(Paths.get("backgrounds_user"))) {
            for (Path path : list.toList()) {
                String fileName = path.getFileName().toString();
                long id = Long.parseLong(fileName.substring(0, fileName.indexOf(".")));
                if (guild.getMemberById(id) == null) {
                    logger.warn("On supprime l'arrière-plan de {} parce qu'il n'existe plus", id);
                    Files.delete(path);
                }
            }
        }

        save();
    }

    private void save() {
        try (ObjectOutputStream stream = new ObjectOutputStream(new FileOutputStream("tatsumaclone.ser"))) {
            stream.writeObject(cash);
            stream.writeObject(xp);
            stream.writeObject(rep);
            stream.writeObject(lastDailyAt);
            stream.writeObject(lastRepAt);
            stream.writeObject(boughtBackgrounds);
            stream.writeObject(dailyStreak);
            stream.writeObject(boughtGameBackgrounds);
            stream.writeObject(ownedPaidRoles);
        } catch (IOException e) {
            logger.error("Impossible de sauver les statistiques Tatsumaki", e);
        }
    }

    void onMessageReceived(MessageReceivedEvent message) {
        long authorId = message.getAuthor().getIdLong();

        if (!lastSpokeAt.containsKey(authorId) ||
                lastSpokeAt.get(authorId).plusMinutes(2).isBefore(ZonedDateTime.now())) {

            long oldExp = xp.getOrDefault(authorId, 0L);

            int level = 0;
            while (oldExp >= getLevelXP(level + 1)) {
                level++;
            }
            long nextLevel = getLevelXP(level + 1);

            int expGet = (int) (Math.random() * 11 + 10);
            long newExp = oldExp + expGet;
            xp.put(authorId, newExp);
            lastSpokeAt.put(authorId, ZonedDateTime.now());

            long newCash = cash.getOrDefault(authorId, 0L) + 1;
            cash.put(authorId, newCash);

            save();

            logger.debug("{} a obtenu {} exp en parlant, et en possède maintenant {}, il/elle a {} pièces. Seuil de niveau = {}", message.getAuthor(), expGet, newExp, newCash, nextLevel);

            if (nextLevel <= newExp) {
                message.getJDA().getTextChannelById(SecretConstants.LEVELING_NOTIFICATION_CHANNEL)
                        .sendMessage("<@" + authorId + ">, tu viens de passer au niveau **" + (level + 2) + "** !\n" +
                                "Voilà une pièce pour toi : <:piece_quest:573973440457867275>").queue();
            }
        }
    }

    void daily(MessageChannel channel, User author) {
        long authorId = author.getIdLong();

        if (!lastDailyAt.containsKey(authorId) ||
                lastDailyAt.get(authorId).truncatedTo(DAYS).plusDays(1).isBefore(ZonedDateTime.now())) {

            int streakStatus = dailyStreak.getOrDefault(authorId, 0);
            if (lastDailyAt.containsKey(authorId) && lastDailyAt.get(authorId).truncatedTo(DAYS).plusDays(2).isAfter(ZonedDateTime.now())) {
                // le dernier daily date d'hier
                streakStatus++;
            } else {
                // le dernier daily est plus ancien qu'hier => on le remet à 1
                streakStatus = 1;
            }

            long newCash = cash.getOrDefault(authorId, 0L);
            newCash += 200;
            if (streakStatus == 7) newCash += 350;
            cash.put(authorId, newCash);

            lastDailyAt.put(authorId, ZonedDateTime.now());

            String intro = "**" + author.getName() + "**, tu as gagné 200 pièces !";
            String credit = "\nTon crédit est maintenant de **" + separated.format(newCash) + "** pièces.";
            String streakBar = "\n**Combo :** " +
                    "`[" + StringUtils.repeat("=", streakStatus * 2) + StringUtils.repeat(" ", (7 - streakStatus) * 2) + "]`" +
                    " (" + streakStatus + "/7)";

            String message;
            if (streakStatus == 7) {
                message = intro + streakBar + "\n**Tu viens de réaliser un combo !** Tu gagnes 350 pièces supplémentaires." + credit;
                streakStatus = 0;
            } else {
                message = intro + credit + streakBar;
            }

            dailyStreak.put(authorId, streakStatus);
            save();

            channel.sendMessage(message).queue();
        } else {
            ZonedDateTime timeDailyAvailable = lastDailyAt.get(authorId).truncatedTo(DAYS).plusDays(1);

            long minutes = Instant.now().until(timeDailyAvailable.toInstant(), ChronoUnit.MINUTES);

            long remainingMinutes = minutes % 60;
            long hours = minutes / 60;

            String s = "";
            if (hours != 0) {
                s += (hours == 1 ? "1 heure et " : hours + " heures et ");
            }
            s += (remainingMinutes == 1 ? "1 minute" : remainingMinutes + " minutes");

            channel.sendMessage("**" + author.getName() + "**, tu dois encore attendre **" + s + "** avant de pouvoir relancer `!daily`.").queue();
        }
    }

    void rep(MessageChannel channel, User author, User receiver) {
        long authorId = author.getIdLong();
        long receiverId = receiver.getIdLong();

        if (authorId == receiverId) {
            channel.sendMessage("**" + author.getName() + "**, tu ne peux pas te donner de la réputation à toi-même. :sweat:").queue();
            return;
        }

        if (!lastRepAt.containsKey(authorId) ||
                lastRepAt.get(authorId).truncatedTo(DAYS).plusDays(1).isBefore(ZonedDateTime.now())) {

            long newRep = rep.getOrDefault(receiverId, 0L);
            newRep++;
            rep.put(receiverId, newRep);

            lastRepAt.put(authorId, ZonedDateTime.now());
            save();

            channel.sendMessage("**" + author.getName() + "**, tu as donné un point de réputation à <@" + receiverId + "> !\nIl/elle a maintenant **"
                    + separated.format(newRep) + "** " + (newRep == 1 ? "point" : "points") + " de réputation.").queue();
        } else {
            ZonedDateTime timeRepAvailable = lastRepAt.get(authorId).truncatedTo(DAYS).plusDays(1);

            long minutes = Instant.now().until(timeRepAvailable.toInstant(), ChronoUnit.MINUTES);

            long remainingMinutes = minutes % 60;
            long hours = minutes / 60;

            String s = "";
            if (hours != 0) {
                s += (hours == 1 ? "1 heure et " : hours + " heures et ");
            }
            s += (remainingMinutes == 1 ? "1 minute" : remainingMinutes + " minutes");

            channel.sendMessage("**" + author.getName() + "**, tu dois encore attendre **" + s + "** avant de pouvoir redonner de la réputation.").queue();
        }
    }

    void giveCredits(MessageChannel channel, User author, User receiver, long amount) {
        long authorId = author.getIdLong();
        long receiverId = receiver.getIdLong();

        if (authorId == receiverId) {
            channel.sendMessage("**" + author.getName() + "**, à quoi ça sert de se donner de l'argent à soi-même ? :thinking:").queue();
            return;
        }

        if (amount <= 0) {
            channel.sendMessage("... tu veux piquer de l'argent, sérieusement ? :sweat:").queue();
            return;
        }

        if (receiver.isBot()) {
            channel.sendMessage("**" + author.getName() + "**, tu ne peux pas donner de l'argent à des bots. :confused:").queue();
            return;
        }

        long authorCash = cash.getOrDefault(authorId, 0L);
        if (authorCash < amount) {
            channel.sendMessage("Désolé " + author.getName() + ", tu n'as pas assez d'argent ! Tu as " +
                    separated.format(authorCash) + (authorCash == 1 ? " pièce" : " pièces") + ".").queue();
            return;
        }

        channel.sendMessage("**" + author.getName() + "**, tu es sur le point de donner **" + separated.format(amount)
                        + "**" + (amount == 1 ? " pièce" : " pièces") + " à **" + receiver.getName() + "**.\n" +
                        "Pour confirmer, clique sur :white_check_mark:.")
                .queue(message -> {
                    PendingTransaction transaction = new PendingTransaction();
                    transaction.from = authorId;
                    transaction.to = receiverId;
                    transaction.amount = amount;

                    transactions.put(message.getIdLong(), transaction);

                    message.addReaction(Utils.getEmojiFromUnicodeHex("e29c85")).queue();

                    Runnable clear = () -> {
                        transactions.remove(message.getIdLong());
                        logger.debug("Cleared transaction confirmation for timeout, new map = {}", transactions);
                    };

                    if (channel instanceof TextChannel)
                        message.clearReactions().queueAfter(30, TimeUnit.MINUTES, success -> clear.run());
                    else
                        message.editMessage(message.getContentRaw() + "\n(expiré)").queueAfter(30, TimeUnit.MINUTES, success -> clear.run());

                    logger.debug("Added transaction confirmation, new map = {}", transactions);
                });
    }

    boolean onTickAddGiveCash(MessageChannel channel, long messageId, User author) {
        if (transactions.containsKey(messageId) && transactions.get(messageId).from == author.getIdLong()) {
            if (channel instanceof TextChannel) ((TextChannel) channel).clearReactionsById(messageId).queue();
            PendingTransaction transaction = transactions.remove(messageId);
            logger.debug("Cleared transaction confirmation, new map = {}", transactions);

            long authorCash = cash.getOrDefault(transaction.from, 0L);
            if (authorCash < transaction.amount) {
                channel.sendMessage("Désolé " + author.getName() + ", tu n'as pas assez d'argent ! Tu as " +
                        separated.format(authorCash) + (authorCash == 1 ? " pièce" : " pièces") + ".").queue();
                return true;
            }

            authorCash -= transaction.amount;
            cash.put(transaction.from, authorCash);

            long receiverCash = cash.getOrDefault(transaction.to, 0L);
            receiverCash += transaction.amount;
            cash.put(transaction.to, receiverCash);

            save();

            channel.sendMessage("<@" + transaction.to + ">, **" + author.getName() + "** vient de te donner " +
                    separated.format(transaction.amount) + (transaction.amount == 1 ? " pièce" : " pièces") + " ! Tu as maintenant " +
                    separated.format(receiverCash) + (receiverCash == 1 ? " pièce" : " pièces") + ".").queue();

            return true;
        }

        return false;
    }

    boolean onTickAddBuyBackground(MessageChannel channel, long messageId, User author) {
        if (buyBackgroundTransactions.containsKey(messageId) && buyBackgroundTransactions.get(messageId).from == author.getIdLong()) {
            if (channel instanceof TextChannel) ((TextChannel) channel).clearReactionsById(messageId).queue();
            PendingTransaction transaction = buyBackgroundTransactions.remove(messageId);
            logger.debug("Cleared transaction confirmation, new map = {}", buyBackgroundTransactions);

            long authorCash = cash.getOrDefault(transaction.from, 0L);
            if (authorCash < transaction.amount) {
                channel.sendMessage("Désolé " + author.getName() + ", tu n'as pas assez d'argent ! Tu as " +
                        separated.format(authorCash) + (authorCash == 1 ? " pièce" : " pièces") + ".").queue();
                return true;
            }
            try {
                Background matchingBackground = findBackground(transaction.backgroundNameUrlEncoded);

                if (transaction.backgroundUrl == null && matchingBackground == null) {
                    channel.sendMessage("Il faut croire que l'arrière-plan a été supprimé. C'est embarrassant.").queue();
                } else {
                    if (transaction.backgroundUrl != null) {
                        channel.sendTyping().queue();

                        logger.debug("On télécharge " + transaction.backgroundUrl + " vers "
                                + "backgrounds_user" + File.separator + author.getIdLong() + ".png");

                        try (InputStream is = ConnectionUtils.openStreamWithTimeout(transaction.backgroundUrl)) {
                            IOUtils.copy(
                                    is,
                                    new FileOutputStream("backgrounds_user" + File.separator + author.getIdLong() + ".png"));
                        }

                        ArrayList<String> boughtBackgroundsForUser = boughtGameBackgrounds.getOrDefault(author.getIdLong(), new ArrayList<>());
                        boughtBackgroundsForUser.add(transaction.backgroundNameUrlEncoded);
                        boughtGameBackgrounds.put(author.getIdLong(), boughtBackgroundsForUser);

                        purgeBackgrounds();
                        save();

                        channel.sendMessage(":white_check_mark: Ton arrière-plan est maintenant **" +
                                URLDecoder.decode(transaction.backgroundNameUrlEncoded, StandardCharsets.UTF_8) + "** !").queue();
                    } else {
                        channel.sendTyping().queue();

                        logger.debug("cp "
                                + "/app/static/quest/background-repository" + File.separator + matchingBackground.fileName + " "
                                + "backgrounds_user" + File.separator + author.getIdLong() + ".png");

                        Files.copy(
                                Paths.get("/app/static/quest/background-repository", matchingBackground.fileName),
                                Paths.get("backgrounds_user", author.getIdLong() + ".png"),
                                StandardCopyOption.REPLACE_EXISTING);

                        ArrayList<String> boughtBackgroundsForUser = boughtBackgrounds.getOrDefault(author.getIdLong(), new ArrayList<>());
                        boughtBackgroundsForUser.add(matchingBackground.nameUrlEncoded);
                        boughtBackgrounds.put(author.getIdLong(), boughtBackgroundsForUser);

                        purgeBackgrounds();
                        save();

                        channel.sendMessage(":white_check_mark: Ton arrière-plan est maintenant **" + matchingBackground.name + "** !").queue();
                    }

                    authorCash -= transaction.amount;
                    cash.put(transaction.from, authorCash);

                    if (transaction.to != -1 && matchingBackground != null) {
                        int wonAmount = (int) (transaction.amount * 0.8);
                        long receiverCash = cash.getOrDefault(transaction.to, 0L);
                        receiverCash += wonAmount;
                        cash.put(transaction.to, receiverCash);

                        User backgroundAuthor = channel.getJDA().getUserById(transaction.to);
                        if (backgroundAuthor != null) {
                            backgroundAuthor.openPrivateChannel().queue(chan ->
                                    chan.sendMessage("**" + author.getName() + "** vient de t'acheter l'arrière-plan " +
                                            "**" + matchingBackground.name + "** ! Tu as gagné **"
                                            + wonAmount + (wonAmount == 1 ? " pièce.**" : " pièces.**")).queue());
                        }
                    }

                    save();
                }
            } catch (IOException e) {
                logger.error("Une erreur est survenue lors de l'achat de l'AP", e);
                channel.sendMessage("Une erreur est survenue. Désolé. :shrug:").queue();
            }

            return true;
        }

        return false;
    }

    boolean onTickAddBuyRole(MessageChannel channel, long messageId, User author) {
        if (buyRoleTransactions.containsKey(messageId) && buyRoleTransactions.get(messageId).from == author.getIdLong()) {
            if (channel instanceof TextChannel textChannel) textChannel.clearReactionsById(messageId).queue();
            PendingTransaction transaction = buyRoleTransactions.remove(messageId);
            logger.debug("Cleared transaction confirmation, new map = {}", buyRoleTransactions);

            long authorCash = cash.getOrDefault(transaction.from, 0L);
            if (authorCash < transaction.amount) {
                channel.sendMessage("Désolé " + author.getName() + ", tu n'as pas assez d'argent ! Tu as " +
                        separated.format(authorCash) + (authorCash == 1 ? " pièce" : " pièces") + ".").queue();
                return true;
            }

            authorCash -= transaction.amount;
            cash.put(transaction.from, authorCash);

            Role role = channel.getJDA().getRoleById(transaction.to);

            Guild guild = Utils.getQuestGuild(channel.getJDA());

            guild.addRoleToMember(guild.getMemberById(author.getIdLong()), role)
                    .reason("Commande !role - Rôle payant acheté")
                    .queue();

            channel.sendMessage(":white_check_mark: Tu as maintenant le rôle **" + role.getName() + "** !").queue();

            ArrayList<Long> ownedPaidRolesForUser = ownedPaidRoles.getOrDefault(author.getIdLong(), new ArrayList<>());
            ownedPaidRolesForUser.add(transaction.to);
            ownedPaidRoles.put(author.getIdLong(), ownedPaidRolesForUser);
            save();

            return true;
        }

        return false;
    }

    void getRanking(MessageChannel channel, User author, boolean byXp, boolean byCash, boolean includeBots) {
        String unit;
        ConcurrentHashMap<Long, Long> source;
        String rankingBy;

        if (byXp) {
            rankingBy = "XP";
            unit = "point";
            source = xp;
        } else if (byCash) {
            rankingBy = "nombre de pièces";
            unit = "pièce";
            source = cash;
        } else {
            rankingBy = "réputation";
            unit = "point";
            source = rep;
        }

        List<UserAndScore> fullRanking = new ArrayList<>(source.size());
        for (Map.Entry<Long, Long> entry : source.entrySet()) {
            User user = channel.getJDA().getUserById(entry.getKey());

            // si l'utilisateur est un bot, on l'enlève du classement si les bots ne sont pas inclus
            if (user != null && user.isBot() && !includeBots) continue;

            UserAndScore score = new UserAndScore();
            score.id = entry.getKey();
            score.userName = (user == null ? "[utilisateur inconnu]" : user.getName());
            score.score = entry.getValue();
            fullRanking.add(score);
        }
        fullRanking.sort(Comparator.comparing(item -> -item.score));

        for (int rank = 0; rank < fullRanking.size(); rank++) {
            fullRanking.get(rank).rank = rank + 1;
        }

        String ranking = fullRanking.stream()
                .limit(10)
                .map(user ->
                        (user.rank == 1 ? "1er" : user.rank + "ème")
                                + " - **"
                                + user.userName
                                + "** avec "
                                + separated.format(user.score) + " " + unit + (user.score == 1 ? "" : "s")
                )
                .collect(Collectors.joining("\n"));

        UserAndScore currentUser = fullRanking.stream()
                .filter(user -> user.id == author.getIdLong())
                .findFirst()
                .orElse(null);

        if (currentUser != null) {
            ranking += "\n\nTon classement sur le serveur : **"
                    + (currentUser.rank == 1 ? "1er" : currentUser.rank + "ème")
                    + "** avec "
                    + separated.format(currentUser.score) + " " + unit + (currentUser.score == 1 ? "" : "s");
        }

        channel.sendMessage("__**Classement du serveur par " + rankingBy + "**__\n" + ranking).queue();
    }

    void getUserProfile(MessageChannel channel, User target) {
        channel.sendTyping().queue();

        long xpUser = xp.getOrDefault(target.getIdLong(), 0L);
        long repUser = rep.getOrDefault(target.getIdLong(), 0L);

        Activity currentGame = null;
        Guild guild = Utils.getQuestGuild(channel.getJDA());
        if (guild.getMemberById(target.getIdLong()) != null) {
            currentGame = guild.getMemberById(target.getIdLong())
                    .getActivities().stream()
                    .findFirst()
                    .orElse(null);
        }

        BufferedImage image = createImage(xpUser, repUser,
                gamestatsManager.getUserStatsForProfile(target),
                currentGame,
                target.getEffectiveAvatarUrl(), target.getName(), target.getIdLong());

        if (image == null) {
            channel.sendMessage("Désolé, ça n'a pas fonctionné. :shrug:").queue();
        } else {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            try {
                ImageIO.write(image, "png", stream);

                channel.sendMessage("Et voici le profil de **" + target.getName() + "** :")
                        .addFiles(FileUpload.fromData(stream.toByteArray(), "profil.png")).queue();
            } catch (IOException e) {
                logger.error("Impossible de créer l'image de profil", e);
                channel.sendMessage("Désolé, ça n'a pas fonctionné. :shrug:").queue();
            }
        }

    }

    private static BufferedImage createImage(long xp, long rep, Map<String, String> topGames, Activity currentGame,
                                             String avatarUrl, String nick, Long userId) {
        int level = 0;
        while (xp >= getLevelXP(level + 1)) {
            level++;
        }

        long xpInLevel = xp - getLevelXP(level);
        long totalXpInLevel = getLevelXP(level + 1) - getLevelXP(level);

        level++; // le niveau d'affichage est 1 au-dessus du niveau technique

        BufferedImage image = new BufferedImage(512, 512, TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();

        try {
            graphics.setFont(Font.createFont(Font.TRUETYPE_FONT, PlagiatTatsumaki.class.getResourceAsStream("/0.ttf")));
        } catch (FontFormatException | IOException e) {
            logger.error("Impossible de charger la police du profil", e);
            return null;
        }

        {
            // 1. on peint l'arrière-plan
            BufferedImage arrierePlan = null;
            if (new File("backgrounds_user" + File.separator + userId + ".png").exists()) {
                try {
                    arrierePlan = ImageIO.read(new File("backgrounds_user" + File.separator + userId + ".png"));
                } catch (IOException e) {
                    logger.error("Impossible de charger le fond personnalisé {}", userId, e);
                }
            } else {
                Queue<String> games = new ArrayDeque<>(topGames.keySet());
                while (arrierePlan == null && !games.isEmpty()) {
                    String game = games.poll();
                    try {
                        if (new File("/app/static/quest/extra-game-backgrounds" + File.separator + URLEncoder.encode(game, StandardCharsets.UTF_8) + ".png").exists()) {
                            arrierePlan = ImageIO.read(new File("/app/static/quest/extra-game-backgrounds" + File.separator + URLEncoder.encode(game, StandardCharsets.UTF_8) + ".png"));
                        } else {
                            MessageEmbed embed = GameDB.findGame(game);
                            if (embed != null && embed.getImage() != null) {
                                arrierePlan = dlImage(embed.getImage().getUrl().replace(".webp", ".png"));
                            }
                        }
                    } catch (IOException e) {
                        logger.error("Impossible de charger le fond de {}", game, e);
                    }
                }
            }

            if (arrierePlan == null) {
                try {
                    arrierePlan = ImageIO.read(PlagiatTatsumaki.class.getResourceAsStream("/bgdefault.png"));
                } catch (IOException e) {
                    logger.error("Impossible de charger le fond par défaut", e);
                }
            }

            graphics.drawImage(arrierePlan, 0, 0, 512, 512, null);
        }

        {
            try {
                // 2. on dessine le masque par-dessus
                graphics.drawImage(ImageIO.read(PlagiatTatsumaki.class.getResourceAsStream("/Profil.png")), 0, 0, null);
            } catch (IOException e) {
                logger.error("Impossible de peindre le masque", e);
                return null;
            }
        }

        {
            try {
                // 3. on peint l'avatar
                graphics.drawImage(dlImage(avatarUrl + "?size=128").getScaledInstance(98, 98, SCALE_SMOOTH), 207, 12, null);
            } catch (IOException e) {
                logger.error("Impossible de peindre l'avatar", e);
                return null;
            }
        }

        {
            // 4. on peint la rep
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

            graphics.setColor(Color.white);
            int fontSize = 10;

            String s = "Rep " + rep;

            double width, height;
            do {
                graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, ++fontSize));
                Rectangle2D stringBounds = graphics.getFontMetrics().getStringBounds(s, graphics);
                width = stringBounds.getWidth();
                height = stringBounds.getHeight();
            } while (width < 75 && height < 40);

            graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, --fontSize));

            Rectangle2D stringBounds = graphics.getFontMetrics().getStringBounds(s, graphics);
            graphics.drawString(s, 93 - (int) (stringBounds.getWidth() / 2), 96 + (int) (stringBounds.getHeight() / 3));
        }

        {
            // 4. on peint le niveau
            int fontSize = 10;

            String s = "Lv " + level;

            double width, height;
            do {
                graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, ++fontSize));
                Rectangle2D stringBounds = graphics.getFontMetrics().getStringBounds(s, graphics);
                width = stringBounds.getWidth();
                height = stringBounds.getHeight();
            } while (width < 50 && height < 25);

            graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, --fontSize));

            Rectangle2D stringBounds = graphics.getFontMetrics().getStringBounds(s, graphics);
            graphics.drawString(s, 431 - (int) (stringBounds.getWidth() / 2), 103 + (int) (stringBounds.getHeight() / 3));
        }

        {
            // 5. on peint la barre d'XP
            int progression = (int) (xpInLevel * 398 / totalXpInLevel);
            graphics.setColor(new Color(114, 0, 255));
            graphics.fillRect(57, 122, progression, 23);

            graphics.setColor(Color.white);

            graphics.drawLine(56, 121, 56 + progression, 121);
            graphics.drawLine(56, 145, 56 + progression, 145);
            graphics.drawLine(56, 121, 56, 145);

            String s = "XP : " + separated.format(xpInLevel) + " / " + separated.format(totalXpInLevel) + " (total : " + separated.format(xp) + ")";
            graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, 11));
            Rectangle2D stringBounds = graphics.getFontMetrics().getStringBounds(s, graphics);
            graphics.drawString(s, 256 - (int) (stringBounds.getWidth() / 2), 133 + (int) (stringBounds.getHeight() / 3));
        }

        {
            // 6. on peint le pseudo
            int fontSize = 19;
            double width, height;

            AttributedString nickWithFallback;
            do {
                graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, --fontSize));
                nickWithFallback = getTextWithFallbackFont(graphics.getFont(), nick);
                Rectangle2D stringBounds = getWidthOfAttributedString(graphics, nickWithFallback);
                width = stringBounds.getWidth();
                height = stringBounds.getHeight();
            } while (width > 300 && height < 50);

            graphics.drawString(nickWithFallback.getIterator(), 256 - (int) (width / 2), 176 + (int) (height / 3));
        }


        {
            int count = 0;

            for (Map.Entry<String, String> gameEntry : topGames.entrySet()) {
                // peindre le logo s'il y en a un
                BufferedImage logo = null;

                String game = gameEntry.getKey();
                try {
                    if (new File("/app/static/quest/extra-game-logos" + File.separator + URLEncoder.encode(game, StandardCharsets.UTF_8) + ".png").exists()) {
                        logo = ImageIO.read(new File("/app/static/quest/extra-game-logos" + File.separator + URLEncoder.encode(game, StandardCharsets.UTF_8) + ".png"));
                    } else {
                        MessageEmbed embed = GameDB.findGame(game);
                        if (embed != null && embed.getThumbnail() != null) {
                            logo = dlImage(embed.getThumbnail().getUrl().replace(".webp", ".png?size=32"));
                        }
                    }
                } catch (IOException e) {
                    logger.error("Impossible de charger le fond de {}", game, e);
                }

                if (logo != null) {
                    graphics.drawImage(logo, 35, 215 + 40 * count, null);
                }

                graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, 13));

                double width, height;
                String gameNameCut = gameEntry.getKey();
                String finalStringToPaint;
                AttributedString gameNameCutWithFallbacks;
                do {
                    finalStringToPaint = gameNameCut + " : " + gameEntry.getValue();

                    gameNameCutWithFallbacks = getTextWithFallbackFont(graphics.getFont(), finalStringToPaint);
                    Rectangle2D stringBounds = getWidthOfAttributedString(graphics, gameNameCutWithFallbacks);
                    width = stringBounds.getWidth();
                    height = stringBounds.getHeight();

                    if (gameNameCut.length() <= 3) {
                        // on peut plus couper grand-chose de toute façon...
                        break;
                    }
                    gameNameCut = gameNameCut.substring(0, gameNameCut.length() - 4) + "...";
                } while (width > 405);

                graphics.drawString(gameNameCutWithFallbacks.getIterator(), 76, 231 + (int) (height / 3) + 40 * count);

                if (++count >= 5) break;
            }
        }

        {
            // on peint le jeu actuel
            BufferedImage logo = null;

            String playing = "Pas de jeu";
            if (currentGame != null) {
                if (currentGame.getType() == Activity.ActivityType.PLAYING) {
                    playing = "Joue à " + currentGame.getName();
                } else if (currentGame.getType() == Activity.ActivityType.LISTENING) {
                    playing = "Écoute " + currentGame.getName();
                } else if (currentGame.getType() == Activity.ActivityType.STREAMING) {
                    playing = "Streame " + currentGame.getName();
                } else if (currentGame.getType() == Activity.ActivityType.WATCHING) {
                    playing = "Regarde " + currentGame.getName();
                } else if (currentGame.getType() == Activity.ActivityType.COMPETING) {
                    playing = "En compétition sur " + currentGame.getName();
                } else if (currentGame.getType() == Activity.ActivityType.CUSTOM_STATUS) {
                    playing = currentGame.getName();
                } else {
                    playing = "???";
                }

                String game = currentGame.getName();
                try {
                    if (new File("/app/static/quest/extra-game-logos" + File.separator + URLEncoder.encode(game, StandardCharsets.UTF_8) + ".png").exists()) {
                        logo = ImageIO.read(new File("/app/static/quest/extra-game-logos" + File.separator + URLEncoder.encode(game, StandardCharsets.UTF_8) + ".png"));
                    } else {
                        MessageEmbed embed = GameDB.findGame(game);
                        if (embed != null && embed.getImage() != null) {
                            logo = dlImage(embed.getThumbnail().getUrl().replace(".webp", ".png?size=32"));
                        }
                    }
                } catch (IOException e) {
                    logger.error("Impossible de charger le logo de {}", game, e);
                }
            }

            int fontSize = 19;

            StringBuilder playingKickedOut = new StringBuilder();

            double width, height;
            AttributedString gameWithFallbacks;
            do {
                if (fontSize > 8) {
                    // rétrécir la police
                    graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, --fontSize));
                } else if (!playing.contains(" ")) {
                    // il n'y a pas de mot : couper des lettres
                    playingKickedOut.insert(0, playing.charAt(playing.length() - 1));
                    playing = playing.substring(0, playing.length() - 1);
                } else {
                    // couper des mots
                    playingKickedOut.insert(0, playing.substring(playing.lastIndexOf(" ")));
                    playing = playing.substring(0, playing.lastIndexOf(" "));
                }
                gameWithFallbacks = getTextWithFallbackFont(graphics.getFont(), playing);
                Rectangle2D stringBounds = getWidthOfAttributedString(graphics, gameWithFallbacks);
                width = stringBounds.getWidth();
                height = stringBounds.getHeight();
            } while (width > (logo != null ? 300 : 350) && height < 50);

            playingKickedOut = new StringBuilder(playingKickedOut.toString().trim());
            graphics.drawString(gameWithFallbacks.getIterator(), 256 + (logo != null ? 25 : 0) - (int) (width / 2), 447 + (int) (height / 3)
                    - (!playingKickedOut.isEmpty() ? 7 : 0));

            double width1 = width;

            if (!playingKickedOut.isEmpty()) {
                do {
                    gameWithFallbacks = getTextWithFallbackFont(graphics.getFont(), playingKickedOut.toString());
                    Rectangle2D stringBounds = getWidthOfAttributedString(graphics, gameWithFallbacks);
                    width = stringBounds.getWidth();
                    height = stringBounds.getHeight();

                    if (width > (logo != null ? 300 : 350) && height < 50) {
                        playingKickedOut = new StringBuilder(playingKickedOut.substring(0, playingKickedOut.length() - 4) + "...");
                    }
                } while (width > (logo != null ? 300 : 350) && height < 50);

                graphics.drawString(gameWithFallbacks.getIterator(), 256 + (logo != null ? 25 : 0) - (int) (width / 2), 447 + (int) (height / 3) + 7);
            }

            if (logo != null) {
                graphics.drawImage(logo, 235 - (int) (Math.max(width1, width) / 2), 431, null);
            }
        }

        return image;
    }

    private static Rectangle2D getWidthOfAttributedString(Graphics2D graphics2D, AttributedString attributedString) {
        AttributedCharacterIterator characterIterator = attributedString.getIterator();
        FontRenderContext fontRenderContext = graphics2D.getFontRenderContext();
        LineBreakMeasurer lbm = new LineBreakMeasurer(characterIterator, fontRenderContext);
        TextLayout textLayout = lbm.nextLayout(Integer.MAX_VALUE);
        return textLayout.getBounds();
    }

    private static AttributedString getTextWithFallbackFont(Font defaultFont, String text) {
        AttributedString string = new AttributedString(text);
        string.addAttribute(TextAttribute.FONT, defaultFont, 0, text.length());

        Font fallbackFont = new Font("Monospace", Font.BOLD, (int) (defaultFont.getSize() * 1.3));
        Font actualEmojiFont;
        try {
            Font emojiFont = Font.createFont(Font.TRUETYPE_FONT, PlagiatTatsumaki.class.getResourceAsStream("/NotoEmoji-Regular.ttf"));
            actualEmojiFont = emojiFont.deriveFont(defaultFont.getSize() * 1.3f);
        } catch (FontFormatException | IOException e) {
            throw new RuntimeException(e);
        }

        int begin = -1;
        for (int i = 0; i < text.length(); i++) {
            boolean charSupportedByEmojiFont = actualEmojiFont.canDisplay(text.codePointAt(i));
            boolean charSupportedByDefaultFont = defaultFont.canDisplay(text.charAt(i));
            boolean charSupported = charSupportedByDefaultFont || charSupportedByEmojiFont;
            if (charSupported && begin != -1) {
                logger.debug("Using fallback font on interval {}-{} of {} ({})",
                        begin, i, text, text.substring(begin, i));
                string.addAttribute(TextAttribute.FONT, fallbackFont, begin, i);

                begin = -1;
            } else if (!charSupported && begin == -1) {
                begin = i;
            }

            if (charSupportedByEmojiFont && !charSupportedByDefaultFont) {

                if (Character.isHighSurrogate(text.charAt(i)) ||
                        (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)))) {

                    logger.debug("Using emoji font for char {} of {} with UTF-16 surrogate ({})",
                            i, text, text.substring(i, i + 2));
                    string.addAttribute(TextAttribute.FONT, actualEmojiFont, i, i + 2);

                    i++;
                } else {
                    logger.debug("Using emoji font for char {} of {} ({})",
                            i, text, text.charAt(i));
                    string.addAttribute(TextAttribute.FONT, actualEmojiFont, i, i + 1);
                }
            }
        }

        if (begin != -1) {
            logger.debug("Using fallback font on interval {}-{} of {} ({})",
                    begin, text.length(), text, text.substring(begin));
            string.addAttribute(TextAttribute.FONT, fallbackFont, begin, text.length());
        }

        return string;
    }

    private static BufferedImage dlImage(String url) throws IOException {
        File cacheFile = new File("/tmp/profile_image_cache/" + URLEncoder.encode(url, StandardCharsets.UTF_8));
        if (cacheFile.exists()) {
            logger.debug("Reading {} from cache", url);
            return ImageIO.read(cacheFile);
        }

        logger.debug("Downloading {}", url);

        try (InputStream is = ConnectionUtils.openStreamWithTimeout(url)) {
            byte[] imageBytes = IOUtils.toByteArray(is);

            // on l'enregistre en cache
            Files.createDirectories(cacheFile.getParentFile().toPath());
            FileUtils.writeByteArrayToFile(cacheFile, imageBytes);

            return ImageIO.read(new ByteArrayInputStream(imageBytes));
        }
    }

    private static long getLevelXP(int level) {
        return (long) Math.pow(level * 9, 2);
    }

    void getUserCash(MessageChannel channel, User target, boolean other) {
        long argent = cash.getOrDefault(target.getIdLong(), 0L);
        channel.sendMessage("**" + target.getName()
                + (other ? "** a **" : "**, tu as **")
                + separated.format(argent)
                + " " + (argent == 1 ? "pièce" : "pièces") + "** !").queue();
    }

    void chooseBackground(MessageChannel channel, User author, String backgroundName) throws IOException {
        Long authorId = author.getIdLong();

        String nameUrlEncoded = URLEncoder.encode(backgroundName, StandardCharsets.UTF_8);
        Background matchingBackground = findBackground(nameUrlEncoded);

        if (matchingBackground == null) {
            channel.sendMessage("L'arrière-plan **" + backgroundName + "** est introuvable.").queue();
        } else {
            if (boughtBackgrounds.getOrDefault(author.getIdLong(), new ArrayList<>()).contains(matchingBackground.nameUrlEncoded)) {
                logger.debug("cp "
                        + "/app/static/quest/background-repository" + File.separator + matchingBackground.fileName + " "
                        + "backgrounds_user" + File.separator + author.getIdLong() + ".png");

                Files.copy(
                        Paths.get("/app/static/quest/background-repository", matchingBackground.fileName),
                        Paths.get("backgrounds_user", author.getIdLong() + ".png"),
                        StandardCopyOption.REPLACE_EXISTING);

                purgeBackgrounds();
                channel.sendMessage(":white_check_mark: Ton arrière-plan est maintenant **" + matchingBackground.name + "** !").queue();
            } else {
                // déclencher l'achat
                long authorCash = cash.getOrDefault(authorId, 0L);
                if (authorCash < matchingBackground.price) {
                    channel.sendMessage("Désolé " + author.getName() + ", tu n'as pas assez d'argent ! Tu as " +
                            separated.format(authorCash) + (authorCash == 1 ? " pièce" : " pièces") + ", et cet arrière-plan en coûte " +
                            "**" + separated.format(matchingBackground.price) + "**.").queue();
                    return;
                }

                channel.sendMessage("**" + author.getName() + "**, tu dois acheter cet arrière-plan avant de pouvoir l'utiliser." +
                                "\nL'arrière-plan **" + matchingBackground.name + "** coûte **" + separated.format(matchingBackground.price)
                                + (matchingBackground.price == 1 ? " pièce" : " pièces") + "**.\n" +
                                "Pour confirmer, clique sur :white_check_mark:.")
                        .queue(message -> {
                            PendingTransaction transaction = new PendingTransaction();
                            transaction.from = authorId;
                            transaction.to = matchingBackground.author;
                            transaction.backgroundNameUrlEncoded = matchingBackground.nameUrlEncoded;
                            transaction.amount = matchingBackground.price;

                            buyBackgroundTransactions.put(message.getIdLong(), transaction);

                            message.addReaction(Utils.getEmojiFromUnicodeHex("e29c85")).queue();

                            Runnable clear = () -> {
                                buyBackgroundTransactions.remove(message.getIdLong());
                                logger.debug("Cleared transaction confirmation for timeout, new map = {}", buyBackgroundTransactions);
                            };

                            if (channel instanceof TextChannel)
                                message.clearReactions().queueAfter(30, TimeUnit.MINUTES, success -> clear.run());
                            else
                                message.editMessage(message.getContentRaw() + "\n(expiré)").queueAfter(30, TimeUnit.MINUTES, success -> clear.run());

                            logger.debug("Added transaction confirmation, new map = {}", buyBackgroundTransactions);
                        });
            }
        }
    }

    private static final int GAME_BG_PRICE = 100;

    void chooseGameBackground(MessageChannel channel, User author, String gameName) throws IOException {
        Long authorId = author.getIdLong();

        String nameUrlEncoded = URLEncoder.encode(gameName, StandardCharsets.UTF_8);
        GameBackground matchingBackground = resolveGameBackground(nameUrlEncoded, true);

        if (matchingBackground == null) {
            channel.sendMessage("Je n'ai pas d'arrière-plan pour le jeu **" + gameName + "**.").queue();
        } else {
            gameName = URLDecoder.decode(matchingBackground.gameNameUrlEncoded, StandardCharsets.UTF_8);

            try (InputStream is = ConnectionUtils.openStreamWithTimeout(matchingBackground.backgroundUrl)) {
                channel.sendTyping().queue();

                if (boughtGameBackgrounds.getOrDefault(author.getIdLong(), new ArrayList<>()).contains(matchingBackground.gameNameUrlEncoded)) {
                    logger.debug("On télécharge " + matchingBackground.backgroundUrl + " vers "
                            + "backgrounds_user" + File.separator + author.getIdLong() + ".png");

                    IOUtils.copy(
                            is,
                            new FileOutputStream("backgrounds_user" + File.separator + author.getIdLong() + ".png"));

                    purgeBackgrounds();
                    channel.sendMessage(":white_check_mark: Ton arrière-plan est maintenant **" + gameName + "** !").queue();
                } else {
                    // déclencher l'achat
                    long authorCash = cash.getOrDefault(authorId, 0L);
                    if (authorCash < GAME_BG_PRICE) {
                        channel.sendMessage("Désolé " + author.getName() + ", tu n'as pas assez d'argent ! Tu as " +
                                separated.format(authorCash) + (authorCash == 1 ? " pièce" : " pièces") + ", et cet arrière-plan en coûte " +
                                "**" + separated.format(GAME_BG_PRICE) + "**.").queue();
                        return;
                    }

                    channel.sendMessage("Voici à quoi ressemble l'arrière-plan de **" + gameName + "** :")
                            .addFiles(FileUpload.fromData(is, "game_bg.png")).queue();

                    channel.sendMessage("**" + author.getName() + "**, tu dois acheter cet arrière-plan avant de pouvoir l'utiliser." +
                                    "\nL'arrière-plan **" + gameName + "** coûte **" + separated.format(GAME_BG_PRICE) + " pièces**.\n" +
                                    "Pour confirmer, clique sur :white_check_mark:.")
                            .queue(message -> {
                                PendingTransaction transaction = new PendingTransaction();
                                transaction.from = authorId;
                                transaction.to = -1;
                                transaction.backgroundNameUrlEncoded = matchingBackground.gameNameUrlEncoded;
                                transaction.amount = GAME_BG_PRICE;
                                transaction.backgroundUrl = matchingBackground.backgroundUrl;

                                buyBackgroundTransactions.put(message.getIdLong(), transaction);

                                message.addReaction(Utils.getEmojiFromUnicodeHex("e29c85")).queue();

                                Runnable clear = () -> {
                                    buyBackgroundTransactions.remove(message.getIdLong());
                                    logger.debug("Cleared transaction confirmation for timeout, new map = {}", buyBackgroundTransactions);
                                };

                                if (channel instanceof TextChannel)
                                    message.clearReactions().queueAfter(30, TimeUnit.MINUTES, success -> clear.run());
                                else
                                    message.editMessage(message.getContentRaw() + "\n(expiré)").queueAfter(30, TimeUnit.MINUTES, success -> clear.run());

                                logger.debug("Added transaction confirmation, new map = {}", buyBackgroundTransactions);
                            });
                }
            }
        }
    }

    void revertToDefault(MessageChannel channel, User author) throws IOException {
        logger.debug("rm " + "backgrounds_user" + File.separator + author.getIdLong() + ".png");
        Files.delete(Paths.get("backgrounds_user" + File.separator + author.getIdLong() + ".png"));

        purgeBackgrounds();
        channel.sendMessage(":white_check_mark: **" + author.getName() + "**, tu as à nouveau ton arrière-plan par défaut.").queue();
    }

    @Nullable
    private Background findBackground(String nameUrlEncoded) throws IOException {
        Path backgroundRepositoryDirectory = Paths.get("/app/static/quest/background-repository");

        String matchingBackground;
        try (Stream<Path> list = Files.list(backgroundRepositoryDirectory)) {
            matchingBackground = list
                    .filter(n -> n.getFileName().toString().startsWith(nameUrlEncoded + ";"))
                    .findFirst()
                    .map(n -> n.getFileName().toString())
                    .orElse(null);
        }

        if (matchingBackground == null) {
            try (Stream<Path> list = Files.list(backgroundRepositoryDirectory)) {
                matchingBackground = list
                        .filter(n -> n.getFileName().toString().toLowerCase().startsWith(nameUrlEncoded.toLowerCase() + ";"))
                        .findFirst()
                        .map(n -> n.getFileName().toString())
                        .orElse(null);
            }
        }
        logger.debug("Correspondance pour {} : {}", nameUrlEncoded, matchingBackground);

        if (matchingBackground == null) {
            return null;
        } else {
            return new Background(matchingBackground);
        }
    }

    private GameBackground resolveDefaultGame(JDA jda, Long userId) {
        Queue<String> games = new ArrayDeque<>(gamestatsManager.getUserStatsForProfile(
                jda.getUserById(userId)).keySet());

        while (!games.isEmpty()) {
            String game = games.poll();
            if (new File("/app/static/quest/extra-game-backgrounds" + File.separator + URLEncoder.encode(game, StandardCharsets.UTF_8) + ".png").exists()) {
                return new GameBackground(
                        URLEncoder.encode(game, StandardCharsets.UTF_8),
                        "https://maddie480.ovh/quest/game_backgrounds/" + URLEncoder.encode(game, StandardCharsets.UTF_8) + ".png");
            } else {
                MessageEmbed embed = GameDB.findGame(game);
                if (embed != null && embed.getImage() != null) {
                    return new GameBackground(
                            URLEncoder.encode(embed.getTitle(), StandardCharsets.UTF_8),
                            embed.getImage().getUrl().replace(".webp", ".png"));
                }
            }
        }

        return null;
    }

    private String getBackgroundsByUser(JDA jda, long userId) throws IOException {
        Path backgroundRepositoryDirectory = Paths.get("/app/static/quest/background-repository");

        String ownedBackgrounds;
        try (Stream<Path> list = Files.list(backgroundRepositoryDirectory)) {
            ownedBackgrounds = list
                    .map(n -> new Background(n.getFileName().toString()))
                    .filter(bg -> boughtBackgrounds.getOrDefault(userId, new ArrayList<>()).contains(bg.nameUrlEncoded))
                    .map(background -> background.fileName)
                    .sorted()
                    .collect(Collectors.joining("\n"));
        }

        String unownedBackgrounds;
        try (Stream<Path> list = Files.list(backgroundRepositoryDirectory)) {
            unownedBackgrounds = list
                    .map(n -> new Background(n.getFileName().toString()))
                    .filter(bg -> !boughtBackgrounds.getOrDefault(userId, new ArrayList<>()).contains(bg.nameUrlEncoded))
                    .map(background -> background.fileName)
                    .sorted()
                    .collect(Collectors.joining("\n"));
        }

        String authorsList;
        try (Stream<Path> list = Files.list(backgroundRepositoryDirectory)) {
            authorsList = list
                    .map(n -> new Background(n.getFileName().toString()).author)
                    .collect(Collectors.toSet())
                    .stream()
                    .map(authorId -> {
                        User author = jda.getUserById(authorId);
                        String userName = "[utilisateur inconnu]";
                        if (author != null) {
                            userName = author.getName();
                        }
                        return authorId + ";" + URLEncoder.encode(userName, StandardCharsets.UTF_8);
                    })
                    .collect(Collectors.joining("\n"));
        }

        long amount = cash.getOrDefault(userId, 0L);

        GameBackground defaultGame = resolveDefaultGame(jda, userId);
        AtomicBoolean boughtDefaultBg = new AtomicBoolean(false);

        String gameBackgrounds = boughtGameBackgrounds.getOrDefault(userId, new ArrayList<>()).stream()
                .sorted()
                .map(game -> {
                    if (defaultGame != null && game.equals(defaultGame.gameNameUrlEncoded)) boughtDefaultBg.set(true);

                    GameBackground resolved = resolveGameBackground(game, false);
                    if (resolved == null) return null;
                    else return game + ";" + resolved.backgroundUrl;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));

        if (defaultGame == null &&
                !boughtGameBackgrounds.getOrDefault(userId, new ArrayList<>()).contains("QUEST")) {

            gameBackgrounds = "default;QUEST;https://maddie480.ovh/quest/game_backgrounds/QUEST.png\n" + gameBackgrounds;
        }

        if (defaultGame != null && !boughtDefaultBg.get()) {
            gameBackgrounds = "default;" + defaultGame.gameNameUrlEncoded + ";" + defaultGame.backgroundUrl + "\n" + gameBackgrounds;
        }

        try {
            return amount + "\n" + URLEncoder.encode(jda.getUserById(userId).getName(), StandardCharsets.UTF_8) + "\n"
                    + ownedBackgrounds + "\n===\n" + unownedBackgrounds + "\n===\n" + authorsList + "\n===\n" + gameBackgrounds;
        } catch (Exception e) {
            return null;
        }
    }

    void sendBackgroundLink(MessageChannel channel, User author) throws IOException {
        channel.sendTyping().queue();

        long token = generateTokenFor(channel.getJDA(), author.getIdLong());
        sendBackgroundsToCloudStorage();

        channel.sendMessage("Voici la galerie des arrière-plans : https://maddie480.ovh/quest/backgrounds?token=" + token + "\n" +
                "(Cette page indique aussi les arrière-plans que **tu** possèdes déjà.)").queue();
    }

    private long generateTokenFor(JDA jda, Long authorId) throws IOException {
        logger.debug("Generating background data for " + authorId);

        long token;
        do {
            token = (long) (Math.random() * Long.MAX_VALUE);
        } while (Files.exists(Paths.get("/shared/temp/quest-backgrounds/" + token + ".txt")));

        // obtenir les infos du token
        String data = getBackgroundsByUser(jda, authorId);

        // les envoyer sur Cloud Storage
        try (OutputStream os = Files.newOutputStream(Paths.get("/shared/temp/quest-backgrounds/" + token + ".txt"))) {
            IOUtils.write(data, os, StandardCharsets.UTF_8);
        }
        return token;
    }

    private void sendBackgroundsToCloudStorage() throws IOException {
        Path tempDir = Paths.get("/shared/temp/quest-backgrounds");

        Path uploadDir = tempDir.resolve("quest_backgrounds");
        Files.createDirectories(uploadDir);
        try (Stream<Path> list = Files.list(Paths.get("/app/static/quest/background-repository"))) {
            for (Path p : list.toList()) {
                logger.debug("Sending background " + p.toAbsolutePath() + " to Cloud Storage");
                Files.copy(p, uploadDir.resolve(p.getFileName().toString()));
            }
        }

        uploadDir = tempDir.resolve("quest_game_backgrounds");
        Files.createDirectories(uploadDir);
        try (Stream<Path> list = Files.list(Paths.get("/app/static/quest/extra-game-backgrounds"))) {
            for (Path p : list.toList()) {
                logger.debug("Sending game background " + p.toAbsolutePath() + " to Cloud Storage");
                Files.copy(p, uploadDir.resolve(p.getFileName().toString()));
            }
        }
    }

    private void purgeBackgrounds() throws IOException {
        List<Path> filesToDelete;

        try (Stream<Path> walker = Files.walk(Paths.get("/shared/temp/quest-backgrounds"))) {
            filesToDelete = walker
                    .filter(Files::isRegularFile)
                    .toList();
        }

        for (Path path : filesToDelete) {
            logger.info("Background purge: deleting file {}", path.toAbsolutePath());
            Files.delete(path);
        }
    }

    private static GameBackground resolveGameBackground(String nameUrlEncoded, boolean ignoreCase) {
        String name = URLDecoder.decode(nameUrlEncoded, StandardCharsets.UTF_8);
        String[] fileNames = new File("/app/static/quest/extra-game-backgrounds").list((dir, name1) -> {
            String nameUrlDecoded = URLDecoder.decode(name1, StandardCharsets.UTF_8);
            return ignoreCase ? nameUrlDecoded.equalsIgnoreCase(name + ".png") :
                    nameUrlDecoded.equals(name + ".png");
        });

        if (fileNames.length > 1) return null;

        if (fileNames.length == 1) {
            logger.debug("Reading game background {} from file {}", name, fileNames[0]);
            return new GameBackground(
                    fileNames[0].substring(0, fileNames[0].length() - 4),
                    "https://maddie480.ovh/quest/game_backgrounds/" + fileNames[0]);
        }

        MessageEmbed resolved = GameDB.findGame(name, ignoreCase);
        if (resolved != null && resolved.getImage() != null) {
            String url = resolved.getImage().getUrl().replace(".webp", ".png");
            logger.debug("Reading game background {} from {}", name, url);

            return new GameBackground(
                    URLEncoder.encode(resolved.getTitle(), StandardCharsets.UTF_8), url);
        }
        return null;
    }

    // ================== gestion des rôles

    private final List<Long> roleIds;
    private final List<Integer> rolePrices;

    void pickRole(MessageChannel channel, Member member, String role) {
        Guild guild = Utils.getQuestGuild(channel.getJDA());
        if (!guild.getSelfMember().canInteract(member)) {
            channel.sendMessage("Désolé, je n'ai pas le droit de modifier tes rôles." +
                    " C'est sûrement que tu dois avoir le droit de les modifier toi-même... :thinking:").queue();
            return;
        }

        Role correspondingRole = roleIds.stream()
                .map(guild::getRoleById)
                .min(Comparator.comparingInt(roleObj -> distanceEdition(role, roleObj)))
                .orElse(null);

        int distance = distanceEdition(role, correspondingRole);

        logger.debug("Le rôle qui correspond le mieux est {} à une distance d'édition de {}", correspondingRole, distance);
        if (distance > 3) {
            channel.sendMessage("Je n'ai pas trouvé le rôle que tu cherches. Pour savoir quels rôles je peux te donner," +
                    " tape `!help role`.").queue();
            return;
        }

        int correspondingRolePrice = rolePrices.get(roleIds.indexOf(correspondingRole.getIdLong()));
        logger.debug("Le rôle {} coûte {} pièces", correspondingRole, correspondingRolePrice);

        if (member.getRoles().contains(correspondingRole)) {
            logger.debug("Rôle déjà attribué");
            channel.sendMessage("Mais... tu as déjà le rôle **" + correspondingRole.getName() + "** !").queue();
            return;
        }

        if (correspondingRolePrice == 0) {
            logger.debug("Rôle gratuit");

            // vérifier si l'utilisateur a d'autres rôles gratuits
            List<Role> rolesToRemove = member.getRoles().stream()
                    .filter(userRole -> roleIds.contains(userRole.getIdLong())
                            && rolePrices.get(roleIds.indexOf(userRole.getIdLong())) == 0)
                    .collect(Collectors.toList());

            if (!rolesToRemove.isEmpty()) {
                logger.debug("Rôles gratuits à supprimer = {}", rolesToRemove);
                for (Role roleToRemove : rolesToRemove) {
                    guild.removeRoleFromMember(member, roleToRemove)
                            .reason("Commande !role - Le membre a choisi un autre rôle gratuit")
                            .complete();
                }
            }

            logger.debug("Je donne le rôle gratuit {}", correspondingRole);
            guild.addRoleToMember(member, correspondingRole)
                    .reason("Commande !role - Rôle gratuit")
                    .queue();

            channel.sendMessage(":white_check_mark: Tu as maintenant le rôle **" + correspondingRole.getName() + "** !").queue();
        } else {
            logger.debug("Rôle payant");

            if (ownedPaidRoles.getOrDefault(member.getUser().getIdLong(), new ArrayList<>())
                    .contains(correspondingRole.getIdLong())) {

                logger.debug("L'utilisateur a déjà acheté le rôle");

                guild.addRoleToMember(member, correspondingRole)
                        .reason("Commande !role - Rôle payant déjà acheté")
                        .queue();

                channel.sendMessage(":white_check_mark: Tu as maintenant le rôle **" + correspondingRole.getName() + "** !").queue();
            } else {
                Long authorId = member.getUser().getIdLong();
                String authorName = member.getUser().getName();

                // déclencher l'achat
                long authorCash = cash.getOrDefault(authorId, 0L);
                if (authorCash < correspondingRolePrice) {
                    channel.sendMessage("Désolé " + authorName + ", tu n'as pas assez d'argent ! Tu as " +
                            separated.format(authorCash) + (authorCash == 1 ? " pièce" : " pièces") + ", et ce rôle en coûte " +
                            "**" + separated.format(correspondingRolePrice) + "**.").queue();
                    return;
                }

                channel.sendMessage("**" + member.getUser().getName() + "**, le rôle **" + correspondingRole.getName()
                                + "** coûte **" + separated.format(correspondingRolePrice)
                                + (correspondingRolePrice == 1 ? " pièce" : " pièces") + "**.\n" +
                                "Pour l'acheter, clique sur :white_check_mark:.")
                        .queue(message -> {
                            PendingTransaction transaction = new PendingTransaction();
                            transaction.from = authorId;
                            transaction.to = correspondingRole.getIdLong();
                            transaction.amount = correspondingRolePrice;

                            buyRoleTransactions.put(message.getIdLong(), transaction);

                            message.addReaction(Utils.getEmojiFromUnicodeHex("e29c85")).queue();

                            Runnable clear = () -> {
                                buyRoleTransactions.remove(message.getIdLong());
                                logger.debug("Cleared role transaction confirmation for timeout, new map = {}", buyRoleTransactions);
                            };

                            message.clearReactions().queueAfter(30, TimeUnit.MINUTES, success -> clear.run());

                            logger.debug("Added transaction confirmation, new map = {}", buyRoleTransactions);
                        });
            }
        }
    }

    private int distanceEdition(String roleName, Role role) {
        // normaliser le nom du rôle
        String thisRoleName = role.getName();

        roleName = normalize(roleName);
        thisRoleName = normalize(thisRoleName);

        if (roleName.startsWith("les")) roleName = roleName.substring(3);
        if (thisRoleName.startsWith("les")) thisRoleName = thisRoleName.substring(3);

        return DistanceEdition.computeDistance(roleName, thisRoleName);
    }

    private static String normalize(String name) {
        name = StringUtils.stripAccents(name).toLowerCase();
        StringBuilder endName = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            if (Character.isLetter(name.charAt(i)) || name.charAt(i) == '.' || Character.isDigit(name.charAt(i))) {
                endName.append(name.charAt(i));
            }
        }
        return endName.toString();
    }
}
