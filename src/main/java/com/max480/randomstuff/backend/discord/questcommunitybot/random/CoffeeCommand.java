package com.max480.randomstuff.backend.discord.questcommunitybot.random;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.io.IOException;

public class CoffeeCommand extends AbstractFixedMessageCommand {
    @Override
    public String getCommandName() {
        return "coffee";
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        event.getChannel().sendMessage(":coffee:").queue();
    }
}
