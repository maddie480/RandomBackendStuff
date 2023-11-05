package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.quest;

import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.utils.SplitUtil;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

import java.io.IOException;

public class MyModsCommand implements BotCommand {
    @Override
    public String getCommandName() {
        return "my_mods";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Lister les mods que vous avez publiés";
    }

    @Override
    public String getFullHelp() {
        return "Et uniquement ceux que _vous_ avez publiés. Pour la liste complète :arrow_right: `!list_mods`";
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
        StringBuilder modList = new StringBuilder();

        for (Mod mod : ListModsCommand.getAllQuestMods()) {
            if (event.getAuthor().getIdLong() == mod.getAuthorDiscordId()) {
                modList.append(mod.getName()).append(" v").append(mod.getVersion()).append(", ");
            }
        }

        if (modList.isEmpty()) {
            event.getChannel().sendMessage("Tu n'as pas de mod !").queue();
        } else {
            SplitUtil.split("Voici la liste de tes mods :\n" + modList, 2000, true, SplitUtil.Strategy.NEWLINE, SplitUtil.Strategy.ANYWHERE)
                    .stream()
                    .map(split -> new MessageCreateBuilder().setContent(split).build())
                    .forEach(message -> event.getChannel().sendMessage(message).queue());
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) {
        return false;
    }
}
