package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.reminders;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;

import java.io.IOException;

public class AddReminderCommand implements BotCommand {
    private final ReminderEngine reminderEngine;

    public AddReminderCommand(ReminderEngine reminderEngine) {
        this.reminderEngine = reminderEngine;
    }

    @Override
    public String getCommandName() {
        return "rappellemoi";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"rappel*"};
    }

    @Override
    public String getShortHelp() {
        return "Définir un rappel";
    }

    @Override
    public String getFullHelp() {
        return """
                Pour définir un rappel récurrent, tu dois inclure "à partir de" dans ta demande. Quelques exemples :
                !rappellemoi de tester le bot dans 10 minutes (unités supportées pour les durées : minutes, heures, jours et semaines)
                !rappellemoi de faire un test à 10h
                !rappellemoi de tester le bot lundi à 10h (formats supportés : "demain", "après-demain", les jours de la semaine ou "le 14 mai")
                !rappellemoi de réviser mes cours tous les jours à partir de 20h
                !rappellemoi d'aller au sport toutes les semaines à partir de lundi à 20h

                Les rappels te seront envoyés par MP.""";
    }

    @Override
    public boolean isAdminOnly() {
        return false;
    }

    @Override
    public boolean areParametersValid(String[] parameters) {
        return reminderEngine.isReminderValid(parameters[0]);
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        reminderEngine.addReminder(event.getChannel(), event.getAuthor(), parameters[0]);
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
