package com.max480.randomstuff.backend.discord.questcommunitybot.misc;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;

public class ChooseCommand implements BotCommand {
    @Override
    public String getCommandName() {
        return "choose";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"choix 1*", "choix 2..."};
    }

    @Override
    public String getShortHelp() {
        return "Choisit un élément au hasard dans une liste";
    }

    @Override
    public String getFullHelp() {
        return """
                Si un élément contient des espaces, il faut mettre des guillemets autour de son nom.
                Par exemple : `!choose Celeste "A Hat in Time" "League of Legends" "Team Fortress 2"`""";
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
        String picked = parameters[(int) (Math.random() * parameters.length)];
        event.getChannel().sendMessage("**" + event.getAuthor().getName() + "**, je choisis **" + picked + "** !").queue();
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
