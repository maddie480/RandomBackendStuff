package com.max480.randomstuff.backend.discord.questcommunitybot.quest;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

public class QuestSetupLinkCommand implements BotCommand {
    @Override
    public String getCommandName() {
        return "questsetup_link";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Donne le lien pour télécharger Quest";
    }

    @Override
    public String getFullHelp() {
        return "C'est là que Laupok décrit ce qu'il y aura dans les prochaines versions de Quest.";
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
    public void runCommand(MessageReceivedEvent event, String[] parameters) {
        event.getChannel().sendMessage("Tu peux télécharger Quest ici :arrow_right: https://quest-community-bot.appspot.com/download/Quest-setup.exe").queue();
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) {
        return false;
    }
}
