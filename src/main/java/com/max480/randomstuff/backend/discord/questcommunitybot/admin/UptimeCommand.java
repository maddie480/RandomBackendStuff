package com.max480.randomstuff.backend.discord.questcommunitybot.admin;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

public class UptimeCommand implements BotCommand {
    private final ZonedDateTime startDate = ZonedDateTime.now();

    @Override
    public String getCommandName() {
        return "uptime";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Indique depuis combien de temps le Bot tourne";
    }

    @Override
    public String getFullHelp() {
        return "";
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
        long minutes = startDate.until(Instant.now(), ChronoUnit.MINUTES);

        long remainingMinutes = minutes % 60;
        long hours = (minutes / 60) % 24;
        long days = minutes / 24 / 60;

        String s = "Le Bot tourne depuis ";
        if (days != 0) {
            s += (days == 1 ? "1 jour, " : days + " jours, ");
        }
        if (hours != 0) {
            s += (hours == 1 ? "1 heure, " : hours + " heures, ");
        }
        s += (remainingMinutes == 1 ? "1 minute" : remainingMinutes + " minutes");
        s += ".";

        event.getChannel().sendMessage(s).queue();
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
