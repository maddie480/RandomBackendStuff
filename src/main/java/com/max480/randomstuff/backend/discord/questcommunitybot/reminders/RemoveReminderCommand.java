package com.max480.randomstuff.backend.discord.questcommunitybot.reminders;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;

public class RemoveReminderCommand implements BotCommand {
    private final ReminderEngine reminderEngine;

    public RemoveReminderCommand(ReminderEngine reminderEngine) {
        this.reminderEngine = reminderEngine;
    }

    @Override
    public String getCommandName() {
        return "delrappel";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"rappel*"};
    }

    @Override
    public String getShortHelp() {
        return "Supprimer un rappel";
    }

    @Override
    public String getFullHelp() {
        return "Par exemple : `!delrappel tester le bot`";
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
        reminderEngine.removeReminder(event.getChannel(), event.getAuthor(), parameters[0]);
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
