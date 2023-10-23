package com.max480.randomstuff.backend.discord.questcommunitybot.leveling;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import com.max480.randomstuff.backend.discord.questcommunitybot.Utils;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;

public class ChooseBackgroundCommand implements BotCommand {
    private final PlagiatTatsumaki levelingEngine;

    public ChooseBackgroundCommand(PlagiatTatsumaki levelingEngine) {
        this.levelingEngine = levelingEngine;
    }

    @Override
    public String getCommandName() {
        return "choose_bg";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"nom*"};
    }

    @Override
    public String getShortHelp() {
        return "Choisir un arrière-plan";
    }

    @Override
    public String getFullHelp() {
        return "Tu devras l'acheter si tu ne le possèdes pas déjà.";
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
        levelingEngine.chooseBackground(event.getChannel(), event.getAuthor(), parameters[0]);
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        if (Utils.getUnicodeHexFromEmoji(event.getEmoji().getName()).equals("e29c85")) {
            return levelingEngine.onTickAddBuyBackground(event.getChannel(), event.getMessageIdLong(), event.getUser());
        }
        return false;
    }
}
