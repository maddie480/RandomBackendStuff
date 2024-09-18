package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.leveling;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;

import java.io.IOException;

public class ChooseGameBackgroundCommand implements BotCommand {
    private final PlagiatTatsumaki levelingEngine;

    public ChooseGameBackgroundCommand(PlagiatTatsumaki levelingEngine) {
        this.levelingEngine = levelingEngine;
    }

    @Override
    public String getCommandName() {
        return "choose_game_bg";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"nom*"};
    }

    @Override
    public String getShortHelp() {
        return "Choisir l'arrière-plan d'un jeu";
    }

    @Override
    public String getFullHelp() {
        return "Tu devras l'acheter si tu ne le possèdes pas déjà.";
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
        levelingEngine.chooseGameBackground(event.getChannel(), event.getAuthor(), parameters[0]);
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
