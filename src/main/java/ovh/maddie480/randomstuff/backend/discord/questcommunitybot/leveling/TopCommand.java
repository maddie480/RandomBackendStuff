package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.leveling;

import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;
import java.util.Arrays;

public class TopCommand implements BotCommand {
    private final PlagiatTatsumaki levelingEngine;

    public TopCommand(PlagiatTatsumaki levelingEngine) {
        this.levelingEngine = levelingEngine;
    }

    @Override
    public String getCommandName() {
        return "top";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"rep|cash|xp", "nobots"};
    }

    @Override
    public String getShortHelp() {
        return "Renvoie le Top 10 du serveur dans une catégorie";
    }

    @Override
    public String getFullHelp() {
        return """
                `cash` donne le classement par nombre de pièces, `rep` par réputation, et `xp` par points d'expérience.
                Si tu ne donnes aucun paramètre, le bot te donnera le classement par `xp`.
                Il est possible d'ajouter le paramètre `nobots` pour exclure les bots du classement.""";
    }

    @Override
    public boolean isAdminOnly() {
        return false;
    }

    @Override
    public boolean areParametersValid(String[] parameters) {
        if (parameters.length == 0) {
            return true;
        }

        if (parameters.length == 1) {
            return Arrays.asList("rep", "cash", "xp", "nobots").contains(parameters[0]);
        }

        return Arrays.asList("rep", "cash", "xp").contains(parameters[0]) && "nobots".equals(parameters[1]);
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        boolean withBots = true;
        for (String parameter : parameters) {
            if ("nobots".equals(parameter)) {
                withBots = false;
                break;
            }
        }

        String type = "xp";
        if (parameters.length > 0 && !"nobots".equals(parameters[0])) {
            type = parameters[0];
        }

        levelingEngine.getRanking(event.getChannel(), event.getAuthor(),
                type.equals("xp"), type.equals("cash"), withBots);
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
