package com.max480.randomstuff.backend.discord.questcommunitybot.random;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class GenerateEmojiCommand implements BotCommand {

    @Override
    public String getCommandName() {
        return "generate";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Génère un emoji avec Celeste Emoji Generator";
    }

    @Override
    public String getFullHelp() {
        return " ... en vrai, la commande débloque juste l'accès à un channel pour le bot pendant 5 minutes.";
    }

    @Override
    public boolean isAdminOnly() {
        return true;
    }

    @Override
    public boolean areParametersValid(String[] parameters) {
        return true;
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        Guild s = event.getJDA().getGuildById(443390765826179072L);
        Role r = s.getRoleById(1053042632919617667L);
        Member m = s.getMemberById(892979056889430048L);

        if (!m.getRoles().contains(r)) {
            s.addRoleToMember(m, r).queue();
            s.removeRoleFromMember(m, r).queueAfter(5, TimeUnit.MINUTES);
            event.getMessage().delete().queue();
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) {
        return false;
    }
}
