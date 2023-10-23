package com.max480.randomstuff.backend.discord.questcommunitybot.random;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.io.IOException;

public class EasterEggCommand extends AbstractFixedMessageCommand {
    @Override
    public String getCommandName() {
        return "easteregg";
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        event.getChannel().sendMessage("Bien essayé !").queue();
    }
}
