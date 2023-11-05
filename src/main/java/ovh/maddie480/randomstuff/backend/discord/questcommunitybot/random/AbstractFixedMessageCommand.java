package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.random;

import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;

public abstract class AbstractFixedMessageCommand implements BotCommand {
    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Renvoie un message fixe";
    }

    @Override
    public String getFullHelp() {
        return "En gros, cette commande ne sert à rien.";
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
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
