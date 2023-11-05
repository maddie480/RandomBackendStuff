package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.gamestats;

import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.utils.SplitUtil;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

import java.io.IOException;

public class GamestatsCommand implements BotCommand {
    private final GamestatsManager gamestatsManager;

    public GamestatsCommand(GamestatsManager gamestatsManager) {
        this.gamestatsManager = gamestatsManager;
    }

    @Override
    public String getCommandName() {
        return "gamestats";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"day|week|month|steam|nom d'un jeu"};
    }

    @Override
    public String getShortHelp() {
        return "Donne le Top 10 des jeux joués sur le serveur, et permet de récupérer les statistiques d'un _jeu_";
    }

    @Override
    public String getFullHelp() {
        return """
                `!gamestats` donne le classement des jeux les plus joués depuis le début (le 18/12/2018).
                `!gamestats day` donne le classement des jeux les plus joués aujourd'hui.
                `!gamestats week` donne le classement des jeux les plus joués cette semaine (depuis lundi).
                `!gamestats month` donne le classement des jeux les plus joués ce mois-ci.
                `!gamestats steam` donne le classement des jeux les plus joués sur Steam (en prenant en compte tous les gens qui ont associé leur compte avec la commande `!steam`).
                `!gamestats [jeu]` donne le temps passé sur un jeu en particulier, ainsi que quelques informations sur le jeu depuis une base de données de Discord.""";
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
            event.getChannel().sendMessage(gamestatsManager.postStats(GamestatsManager.ALLTIME)).queue();
        } else if (parameters[0].equals("day")) {
            event.getChannel().sendMessage(gamestatsManager.postStats(GamestatsManager.DAILY)).queue();
        } else if (parameters[0].equals("week")) {
            event.getChannel().sendMessage(gamestatsManager.postStats(GamestatsManager.WEEKLY)).queue();
        } else if (parameters[0].equals("month")) {
            event.getChannel().sendMessage(gamestatsManager.postStats(GamestatsManager.MONTHLY)).queue();
        } else if (parameters[0].equals("steam")) {
            event.getChannel().sendMessage(gamestatsManager.postStats(GamestatsManager.STEAM)).queue();
        } else {
            SplitUtil.split(gamestatsManager.getStatsForGame(parameters[0], event.getChannel()), 2000, true, SplitUtil.Strategy.NEWLINE, SplitUtil.Strategy.ANYWHERE)
                    .stream()
                    .map(split -> new MessageCreateBuilder().setContent(split).build())
                    .forEach(message -> event.getChannel().sendMessage(message).queue());
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
