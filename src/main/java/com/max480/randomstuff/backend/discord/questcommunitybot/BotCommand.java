package com.max480.randomstuff.backend.discord.questcommunitybot;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;

public interface BotCommand {
    String getCommandName();

    String[] getCommandParameters();

    String getShortHelp();

    String getFullHelp();

    boolean isAdminOnly();

    boolean areParametersValid(String[] parameters);

    void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException;

    boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException;
}
