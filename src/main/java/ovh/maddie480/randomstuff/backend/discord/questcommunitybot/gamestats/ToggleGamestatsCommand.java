package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.gamestats;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;

import java.io.IOException;

public class ToggleGamestatsCommand implements BotCommand {
    private final GamestatsManager gamestatsManager;

    public ToggleGamestatsCommand(GamestatsManager gamestatsManager) {
        this.gamestatsManager = gamestatsManager;
    }

    @Override
    public String getCommandName() {
        return "toggle_gamestats";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Permet d'activer ou de désactiver la collecte des gamestats.";
    }

    @Override
    public String getFullHelp() {
        return "Si tu l'actives, tu apparaîtras dans le classement de la commande `!gamestats` et tu pourras voir tes statistiques avec la commande `!played`.";
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
        gamestatsManager.toggleGamestats(event.getChannel(), event.getAuthor());
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
