package com.max480.randomstuff.backend.discord.questcommunitybot.misc;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;
import java.util.Date;

public class SnowflakeCommand implements BotCommand {
    @Override
    public String getCommandName() {
        return "snowflake";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"discord_id*"};
    }

    @Override
    public String getShortHelp() {
        return "Renvoie les informations contenues dans un ID Discord";
    }

    @Override
    public String getFullHelp() {
        return "Notamment la date de création de l'objet.";
    }

    @Override
    public boolean isAdminOnly() {
        return false;
    }

    @Override
    public boolean areParametersValid(String[] parameters) {
        try {
            Long.parseLong(parameters[0]);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        long snowflake = Long.parseLong(parameters[0]);

        long timestamp = (snowflake >> 22) + 1420070400000L;
        long workerID = (snowflake & 0x3E0000) >> 17;
        long processID = (snowflake & 0x1F000) >> 12;
        long increment = snowflake & 0xFFF;

        event.getChannel().sendMessage(
                "Date = " + new Date(timestamp).toLocaleString() + "." + (timestamp % 1000) + " (" + timestamp + ")\n" +
                        "Worker ID = " + workerID + "\n" +
                        "Process ID = " + processID + "\n" +
                        "Incrément = " + increment + "\n").queue();
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
