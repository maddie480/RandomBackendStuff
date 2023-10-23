package com.max480.randomstuff.backend.discord.questcommunitybot.random;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.io.IOException;

public class SpamCommand extends AbstractFixedMessageCommand {
    @Override
    public String getCommandName() {
        return "spam";
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        event.getChannel().sendMessage("S").queue();
        event.getChannel().sendMessage("P").queue();
        event.getChannel().sendMessage("A").queue();
        event.getChannel().sendMessage("M").queue();
    }
}
