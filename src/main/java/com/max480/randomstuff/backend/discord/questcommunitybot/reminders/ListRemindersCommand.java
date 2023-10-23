package com.max480.randomstuff.backend.discord.questcommunitybot.reminders;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;

public class ListRemindersCommand implements BotCommand {
    private final ReminderEngine reminderEngine;

    public ListRemindersCommand(ReminderEngine reminderEngine) {
        this.reminderEngine = reminderEngine;
    }

    @Override
    public String getCommandName() {
        return "listerappels";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Obtenir la liste de tes rappels";
    }

    @Override
    public String getFullHelp() {
        return "Tu peux les annuler avec `!delrappel [rappel*]`.";
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
        reminderEngine.listReminders(event.getChannel(), event.getAuthor());
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
