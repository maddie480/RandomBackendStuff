package com.max480.randomstuff.backend.discord.questcommunitybot.random;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;

public class BonjourCommand implements BotCommand {

    @Override
    public String getCommandName() {
        return "bonjour";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"termes de recherche*"};
    }

    @Override
    public String getShortHelp() {
        return "Le bot vous dit bonjour";
    }

    @Override
    public String getFullHelp() {
        return "Et c'est tout.";
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
        String msg = switch ((int) (Math.random() * 5)) {
            case 0 -> "Bonjour " + event.getAuthor().getName() + " !";
            case 1 -> "Salut " + event.getAuthor().getName() + ", ça va ?";
            case 2 -> "Yop " + event.getAuthor().getName() + " !";
            case 3 -> "Bien le bonjour, " + event.getAuthor().getName() + ".";
            case 4 -> "Salutations, " + event.getAuthor().getName() + ".";
            default ->
                    "non mais j'ai tiré au sort un nombre entre 0 et 4, et j'ai obtenu autre chose, c'est pas possible ça";
        };

        event.getChannel().sendMessage(msg).queue();
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) {
        return false;
    }
}
