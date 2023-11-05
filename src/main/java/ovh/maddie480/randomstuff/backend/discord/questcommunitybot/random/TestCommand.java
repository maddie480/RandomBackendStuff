package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.random;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.io.IOException;

public class TestCommand extends AbstractFixedMessageCommand {
    @Override
    public String getCommandName() {
        return "test";
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        event.getChannel().sendMessage("Cette commande n'a _jamais_ existé. Tu t'attendais à quoi ? :p").queue();
    }
}
