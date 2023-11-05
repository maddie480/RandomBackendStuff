package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.gamestats;

import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;
import java.util.Arrays;

public class ReverseGamestatsCommand implements BotCommand {
    private final GamestatsManager gamestatsManager;

    public ReverseGamestatsCommand(GamestatsManager gamestatsManager) {
        this.gamestatsManager = gamestatsManager;
    }

    @Override
    public String getCommandName() {
        return "gamestats";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"day|week|month|steam"};
    }

    @Override
    public String getShortHelp() {
        return "Donne le top 10 des jeux les _moins_ joués sur le serveur";
    }

    @Override
    public String getFullHelp() {
        return "C'est comme `!gamestats`, mais dans l'autre sens. Pour plus d'infos :arrow_right: `!help gamestats`";
    }

    @Override
    public boolean isAdminOnly() {
        return false;
    }

    @Override
    public boolean areParametersValid(String[] parameters) {
        return parameters.length == 0 || Arrays.asList("day", "week", "month", "steam").contains(parameters[0]);
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        if (parameters.length == 0) {
            event.getChannel().sendMessage(gamestatsManager.postReverseStats(GamestatsManager.ALLTIME)).queue();
        } else if (parameters[0].equals("day")) {
            event.getChannel().sendMessage(gamestatsManager.postReverseStats(GamestatsManager.DAILY)).queue();
        } else if (parameters[0].equals("week")) {
            event.getChannel().sendMessage(gamestatsManager.postReverseStats(GamestatsManager.WEEKLY)).queue();
        } else if (parameters[0].equals("month")) {
            event.getChannel().sendMessage(gamestatsManager.postReverseStats(GamestatsManager.MONTHLY)).queue();
        } else if (parameters[0].equals("steam")) {
            event.getChannel().sendMessage(gamestatsManager.postReverseStats(GamestatsManager.STEAM)).queue();
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
