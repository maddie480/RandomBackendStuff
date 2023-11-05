package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.leveling;

import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.Utils;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;

public class ProfileCommand implements BotCommand {
    private final PlagiatTatsumaki levelingEngine;

    public ProfileCommand(PlagiatTatsumaki levelingEngine) {
        this.levelingEngine = levelingEngine;
    }

    @Override
    public String getCommandName() {
        return "profile";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"discord_id ou mention"};
    }

    @Override
    public String getShortHelp() {
        return "Renvoie le profil de quelqu'un";
    }

    @Override
    public String getFullHelp() {
        return "Le profil indique le niveau, les points d'expérience et les jeux les plus joués.";
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
        if (parameters.length > 0) {
            Member resolved = Utils.findMemberFromString(event.getChannel(), parameters[0]);
            if (resolved != null) {
                levelingEngine.getUserProfile(event.getChannel(), resolved.getUser());
            }
        } else {
            levelingEngine.getUserProfile(event.getChannel(), event.getAuthor());
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
