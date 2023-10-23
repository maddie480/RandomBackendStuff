package com.max480.randomstuff.backend.discord.questcommunitybot.admin;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;
import java.time.temporal.ChronoUnit;

public class PingCommand implements BotCommand {
    @Override
    public String getCommandName() {
        return "ping";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Montre le ping du Bot";
    }

    @Override
    public String getFullHelp() {
        return "Permet d'évaluer sa vitesse de réponse.";
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
        long ping = event.getJDA().getGatewayPing();

        final String message = ":ping_pong: Pong"
                + " | Ping gateway : " + ping + " ms";

        long time = System.currentTimeMillis();

        final Message eventMessage = event.getMessage();

        event.getChannel().sendMessage(message).queue(postedPing -> {
            long timeDiff = System.currentTimeMillis() - time;

            postedPing.editMessage(message + " | Temps mis pour envoyer ce message : " + timeDiff + " ms"
                            + " | Temps entre nos deux messages : " + eventMessage.getTimeCreated().until(postedPing.getTimeCreated(), ChronoUnit.MILLIS) + " ms")
                    .queue();
        });
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
