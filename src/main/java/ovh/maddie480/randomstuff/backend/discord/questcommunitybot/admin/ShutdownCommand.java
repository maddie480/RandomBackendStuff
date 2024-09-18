package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.admin;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;

import java.io.IOException;

public class ShutdownCommand implements BotCommand {
    @Override
    public String getCommandName() {
        return "shutdown";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Arrête le bot";
    }

    @Override
    public String getFullHelp() {
        return "Une action manuelle sera nécessaire pour le redémarrer.";
    }

    @Override
    public boolean isAdminOnly() {
        return true;
    }

    @Override
    public boolean areParametersValid(String[] parameters) {
        return true;
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        System.exit(0);
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
