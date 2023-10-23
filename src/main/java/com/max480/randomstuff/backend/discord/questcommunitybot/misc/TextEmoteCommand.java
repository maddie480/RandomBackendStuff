package com.max480.randomstuff.backend.discord.questcommunitybot.misc;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public class TextEmoteCommand implements BotCommand {
    @Override
    public String getCommandName() {
        return "texte_emote";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"texte*"};
    }

    @Override
    public String getShortHelp() {
        return "Renvoie le texte écrit avec les emotes :regional_indicator_a:, :regional_indicator_b:, etc";
    }

    @Override
    public String getFullHelp() {
        return "Dans le texte donné en paramètre, les espaces seront doublés (pour que le texte soit plus lisible)," +
                " les accents seront retirés, et toutes les lettres et chiffres ainsi que les \"!\" et les \"?\" seront remplacés par des emotes.";
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
        String text = event.getMessage().getContentDisplay().substring(parameters[0].length() + 1);
        String result = turnIntoEmotes(text);
        while (result.length() > 2000) {
            text = text.substring(0, text.length() - 1);
            result = turnIntoEmotes(text) + "\n[message tronqué]";
        }

        event.getChannel().sendMessage(result).queue();
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }

    @NotNull
    private String turnIntoEmotes(String text) {
        StringBuilder dest = new StringBuilder();
        for (char c : StringUtils.stripAccents(text.toLowerCase()).toCharArray()) {
            if (c >= 'a' && c <= 'z') {
                dest.append(":regional_indicator_").append(c).append(":");
            } else {
                switch (c) {
                    case '0' -> dest.append(":zero:");
                    case '1' -> dest.append(":one:");
                    case '2' -> dest.append(":two:");
                    case '3' -> dest.append(":three:");
                    case '4' -> dest.append(":four:");
                    case '5' -> dest.append(":five:");
                    case '6' -> dest.append(":six:");
                    case '7' -> dest.append(":seven:");
                    case '8' -> dest.append(":eight:");
                    case '9' -> dest.append(":nine:");
                    case '!' -> dest.append(":exclamation:");
                    case '?' -> dest.append(":question:");
                    case ' ' -> dest.append("  ");
                    default -> dest.append(c);
                }
            }
        }
        return dest.toString();
    }
}
