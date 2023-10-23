package com.max480.randomstuff.backend.discord.questcommunitybot.leveling;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import com.max480.randomstuff.backend.discord.questcommunitybot.Utils;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;

public class RepCommand implements BotCommand {
    private final PlagiatTatsumaki levelingEngine;

    public RepCommand(PlagiatTatsumaki levelingEngine) {
        this.levelingEngine = levelingEngine;
    }

    @Override
    public String getCommandName() {
        return "rep";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"discord_id ou mention*"};
    }

    @Override
    public String getShortHelp() {
        return "Donner un point de réputation à quelqu'un";
    }

    @Override
    public String getFullHelp() {
        return "Tu peux donner un point de réputation par jour (remise à zéro à minuit).";
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
        Member receiver = Utils.findMemberFromString(event.getChannel(), parameters[0]);
        if (receiver != null) {
            levelingEngine.rep(event.getChannel(), event.getAuthor(), receiver.getUser());
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
