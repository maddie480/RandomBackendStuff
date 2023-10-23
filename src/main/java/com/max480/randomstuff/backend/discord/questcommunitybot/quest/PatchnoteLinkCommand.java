package com.max480.randomstuff.backend.discord.questcommunitybot.quest;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

public class PatchnoteLinkCommand implements BotCommand {
    @Override
    public String getCommandName() {
        return "patchnote_link";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Donne le lien vers le Patch Note de Quest";
    }

    @Override
    public String getFullHelp() {
        return "Le fichier est hosté par Maddie, puisque le site officiel laupok.fr n'existe plus depuis bien longtemps.";
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
        event.getChannel().sendMessage("""
                Voici le lien vers le Patch Note de Quest, que Laupok met régulièrement à jour lorsqu'il ajoute des fonctionnalités à son jeu.
                :arrow_right: https://docs.google.com/document/d/1RM6S9izL5Cw8FY7OlBUo8l4AvXY36i8puou_jv4sRtk/edit""").queue();
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) {
        return false;
    }
}
