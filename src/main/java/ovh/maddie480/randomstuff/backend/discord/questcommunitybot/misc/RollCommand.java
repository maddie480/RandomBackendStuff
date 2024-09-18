package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.misc;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;

import java.io.IOException;

public class RollCommand implements BotCommand {
    @Override
    public String getCommandName() {
        return "roll";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"max 1*", "max 2..."};
    }

    @Override
    public String getShortHelp() {
        return "Tire un ou plusieurs nombres au sort";
    }

    @Override
    public String getFullHelp() {
        return "Par exemple, `!roll 6 6` va tirer 2 nombres entre 1 et 6.";
    }

    @Override
    public boolean isAdminOnly() {
        return false;
    }

    @Override
    public boolean areParametersValid(String[] parameters) {
        for (String limite : parameters) {
            try {
                int limit = Integer.parseInt(limite);
                if (limit <= 0) {
                    return false;
                }
            } catch (NumberFormatException nfe) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        StringBuilder reponse = new StringBuilder("J'ai tiré : ");

        for (String limite : parameters) {
            int limit = Integer.parseInt(limite);
            reponse.append((int) (Math.random() * limit + 1)).append(", ");
        }

        String finalResponse = reponse.substring(0, reponse.length() - 2);
        event.getChannel().sendMessage(finalResponse).queue();
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
