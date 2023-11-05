package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.random;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.io.IOException;

public class DabCommand extends AbstractFixedMessageCommand {
    @Override
    public String getCommandName() {
        return "dab";
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        event.getChannel().sendMessage("<:dabeline:568553780304412706>").queue();
    }
}
