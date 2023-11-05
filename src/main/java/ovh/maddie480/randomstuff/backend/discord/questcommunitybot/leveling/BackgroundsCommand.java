package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.leveling;

import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;

public class BackgroundsCommand implements BotCommand {
    private final PlagiatTatsumaki levelingEngine;

    public BackgroundsCommand(PlagiatTatsumaki levelingEngine) {
        this.levelingEngine = levelingEngine;
    }

    @Override
    public String getCommandName() {
        return "backgrounds";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Donne le lien vers un site listant tous les arrière-plans";
    }

    @Override
    public String getFullHelp() {
        return "Le lien sera personnalisé pour afficher les arrière-plans que tu as achetés.";
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
        levelingEngine.sendBackgroundLink(event.getChannel(), event.getAuthor());
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
