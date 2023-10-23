package com.max480.randomstuff.backend.discord.questcommunitybot.leveling;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import com.max480.randomstuff.backend.discord.questcommunitybot.Utils;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;

public class CashCommand implements BotCommand {
    private final PlagiatTatsumaki levelingEngine;

    public CashCommand(PlagiatTatsumaki levelingEngine) {
        this.levelingEngine = levelingEngine;
    }

    @Override
    public String getCommandName() {
        return "cash";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"discord_id ou mention"};
    }

    @Override
    public String getShortHelp() {
        return "Permet de savoir combien tu as de pièces";
    }

    @Override
    public String getFullHelp() {
        return "En passant le pseudo de quelqu'un, tu peux regarder le crédit de quelqu'un d'autre.";
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
        if (parameters.length > 0) {
            Member resolved = Utils.findMemberFromString(event.getChannel(), parameters[0]);
            if (resolved != null) {
                levelingEngine.getUserCash(event.getChannel(), resolved.getUser(), true);
            }
        } else {
            levelingEngine.getUserCash(event.getChannel(), event.getAuthor(), false);
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        if (Utils.getUnicodeHexFromEmoji(event.getEmoji().getName()).equals("e29c85")) {
            return levelingEngine.onTickAddGiveCash(event.getChannel(), event.getMessageIdLong(), event.getUser());
        }
        return false;
    }
}
