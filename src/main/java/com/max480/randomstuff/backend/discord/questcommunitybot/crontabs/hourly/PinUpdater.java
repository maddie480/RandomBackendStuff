package com.max480.randomstuff.backend.discord.questcommunitybot.crontabs.hourly;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

public class PinUpdater {
    public static void update(TextChannel channel) {
        Message todayBotPin = channel.retrievePinnedMessages().complete().stream()
                .filter(pin -> "Todaybot".equals(pin.getAuthor().getName()))
                .findFirst().orElseThrow();

        Message todayBotLatest = channel.getIterableHistory().stream()
                .filter(pin -> "Todaybot".equals(pin.getAuthor().getName()))
                .findFirst().orElseThrow();

        if (todayBotPin.getIdLong() != todayBotLatest.getIdLong()) {
            todayBotPin.unpin().complete();
            todayBotLatest.pin().complete();
        }
    }
}
