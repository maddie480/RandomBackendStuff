package com.max480.randomstuff.backend.discord.questcommunitybot.leveling;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;

public class DailyCommand implements BotCommand {
    private final PlagiatTatsumaki levelingEngine;

    public DailyCommand(PlagiatTatsumaki levelingEngine) {
        this.levelingEngine = levelingEngine;
    }

    @Override
    public String getCommandName() {
        return "d";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Obtenir ses 200 pièces quotidiennes";
    }

    @Override
    public String getFullHelp() {
        return "Tu peux exécuter cette commande 1 fois par jour (remise à zéro à minuit).";
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
        levelingEngine.daily(event.getChannel(), event.getAuthor());
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
