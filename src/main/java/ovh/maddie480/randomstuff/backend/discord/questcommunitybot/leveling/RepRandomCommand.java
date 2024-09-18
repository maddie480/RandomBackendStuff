package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.leveling;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.Utils;

import java.io.IOException;
import java.util.List;

public class RepRandomCommand implements BotCommand {
    private final PlagiatTatsumaki levelingEngine;

    public RepRandomCommand(PlagiatTatsumaki levelingEngine) {
        this.levelingEngine = levelingEngine;
    }

    @Override
    public String getCommandName() {
        return "rep_random";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Donner un point de réputation à quelqu'un au hasard";
    }

    @Override
    public String getFullHelp() {
        return """
                Même effet que `!rep` mais en donnant de la réputation à quelqu'un au hasard sur le serveur.
                Tu peux donner un point de réputation par jour (remise à zéro à minuit).""";
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
        Guild guild = Utils.getQuestGuild(event.getJDA());
        List<Member> members = guild.getMembers();

        User target = members.get((int) (Math.random() * members.size())).getUser();
        levelingEngine.rep(event.getChannel(), event.getAuthor(), target);
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
