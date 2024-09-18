package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.misc;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;

import java.io.IOException;
import java.net.URLEncoder;

import static java.nio.charset.StandardCharsets.UTF_8;

public class URLEncodeCommand implements BotCommand {
    @Override
    public String getCommandName() {
        return "urlencode";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"message*"};
    }

    @Override
    public String getShortHelp() {
        return "URL-encode le message";
    }

    @Override
    public String getFullHelp() {
        return "Utile pour ajouter de nouveaux fonds d'écran et des logos pour les profils.";
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
        event.getChannel().sendMessage("Voici le résultat : `" + URLEncoder.encode(parameters[0], UTF_8) + "`").queue();
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
