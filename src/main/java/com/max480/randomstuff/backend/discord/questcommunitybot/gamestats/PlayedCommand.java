package com.max480.randomstuff.backend.discord.questcommunitybot.gamestats;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import com.max480.randomstuff.backend.discord.questcommunitybot.Utils;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;

public class PlayedCommand implements BotCommand {
    private static final Logger log = LoggerFactory.getLogger(PlayedCommand.class);

    private final GamestatsManager gamestatsManager;

    public PlayedCommand(GamestatsManager gamestatsManager) {
        this.gamestatsManager = gamestatsManager;
    }

    @Override
    public String getCommandName() {
        return "played";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"day|week|month|steam", "discord_id ou mention"};
    }

    @Override
    public String getShortHelp() {
        return "Permet de récupérer le top 10 des jeux joués par un _utilisateur_";
    }

    @Override
    public String getFullHelp() {
        return """
                `!played maddie480` donne le top 10 des jeux de maddie480 depuis le début (le 18/12/2018).
                `!played day maddie480` donne le top 10 des jeux de maddie480 aujourd'hui.
                `!played week maddie480` donne le top 10 des jeux de maddie480 cette semaine (depuis lundi).
                `!played month maddie480` donne le top 10 des jeux de maddie480 ce mois-ci.
                `!played steam maddie480` donne le top 10 des jeux de maddie480 sur Steam (si elle a associé son compte avec la commande `!steam`).
                Tu peux aussi ne pas donner de pseudo pour voir tes propres statistiques de jeu.

                Tu ne peux voir que les statistiques des utilisateurs qui ont activé la collecte des gamestats avec `!toggle_gamestats`.""";
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
        if (parameters.length == 0) {
            log.debug("Récupération des statistiques globales de l'utilisateur connecté");
            event.getChannel().sendMessage(gamestatsManager.getUserStats(event.getAuthor(), GamestatsManager.ALLTIME, true)).queue();
        } else {
            User user;
            if (parameters.length == 1 && Arrays.asList("day", "week", "month", "steam").contains(parameters[0])) {
                log.debug("Récupération de l'utilisateur connecté");
                user = event.getAuthor();
            } else {
                Member member = Utils.findMemberFromString(event.getChannel(), parameters.length == 1 ? parameters[0] : parameters[1]);
                log.debug("Utilisateur récupéré = {}", member);
                if (member == null) return;
                user = member.getUser();
            }

            boolean isSelfUser = (user.getIdLong() == event.getAuthor().getIdLong());

            switch (parameters[0]) {
                case "day" ->
                        event.getChannel().sendMessage(gamestatsManager.getUserStats(user, GamestatsManager.DAILY, isSelfUser)).queue();
                case "week" ->
                        event.getChannel().sendMessage(gamestatsManager.getUserStats(user, GamestatsManager.WEEKLY, isSelfUser)).queue();
                case "month" ->
                        event.getChannel().sendMessage(gamestatsManager.getUserStats(user, GamestatsManager.MONTHLY, isSelfUser)).queue();
                case "steam" ->
                        event.getChannel().sendMessage(gamestatsManager.getUserStats(user, GamestatsManager.STEAM, isSelfUser)).queue();
                default ->
                        event.getChannel().sendMessage(gamestatsManager.getUserStats(user, GamestatsManager.ALLTIME, isSelfUser)).queue();
            }
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
