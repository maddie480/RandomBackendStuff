package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.leveling;

import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.Utils;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;

public class GiveCashCommand implements BotCommand {
    private final PlagiatTatsumaki levelingEngine;

    public GiveCashCommand(PlagiatTatsumaki levelingEngine) {
        this.levelingEngine = levelingEngine;
    }

    @Override
    public String getCommandName() {
        return "give_cash";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"discord_id ou mention*", "nombre*"};
    }

    @Override
    public String getShortHelp() {
        return "Donner de l'argent à quelqu'un";
    }

    @Override
    public String getFullHelp() {
        return "Attention, tu ne pourras pas récupérer cet argent sauf si on te le rend.";
    }

    @Override
    public boolean isAdminOnly() {
        return false;
    }

    @Override
    public boolean areParametersValid(String[] parameters) {
        try {
            Long.parseLong(parameters[1]);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        long amount = Long.parseLong(parameters[1]);
        Member receiver = Utils.findMemberFromString(event.getChannel(), parameters[0]);
        if (receiver != null) {
            levelingEngine.giveCredits(event.getChannel(), event.getAuthor(), receiver.getUser(), amount);
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        if (Utils.getUnicodeHexFromEmoji(event.getEmoji().getName()).equals("e29c85")) {
            return levelingEngine.onTickAddGiveCash(event.getChannel(), event.getMessageIdLong(), event.getUser());
        }
        return false;
    }
}
