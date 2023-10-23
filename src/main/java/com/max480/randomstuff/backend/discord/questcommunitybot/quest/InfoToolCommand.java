package com.max480.randomstuff.backend.discord.questcommunitybot.quest;

import com.max480.quest.modmanagerbot.QuestToolManager;
import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;

public class InfoToolCommand implements BotCommand {
    @Override
    public String getCommandName() {
        return "info_tool";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"name*"};
    }

    @Override
    public String getShortHelp() {
        return "Obtenir des informations sur un outil";
    }

    @Override
    public String getFullHelp() {
        return "Donne une description détaillée, la version de l'outil, un lien vers un article (s'il est fourni)...";
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
        if (!ListToolsCommand.getAllQuestTools().containsKey(InfoModCommand.normalize(parameters[0]))) {
            event.getChannel().sendMessage("J'ai pas trouvé. Utilise `!list_tools` pour avoir la liste des outils et logiciels de la communauté.").queue();
        } else {
            sendInfoTool(event.getChannel(), parameters[0]);
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) {
        return false;
    }

    static void sendInfoTool(MessageChannel channel, String toolName) throws IOException {
        QuestToolManager.Tool tool = ListToolsCommand.getAllQuestTools().get(InfoModCommand.normalize(toolName));
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle(tool.name, tool.downloadUrl)
                .setDescription(tool.longDescription)
                .addField("Version", tool.version, true)
                .addField("Auteur", tool.author, true)
                .addField("Téléchargement", tool.downloadUrl, true);

        if (tool.moreInfoUrl != null) {
            builder.addField("Plus d'infos", tool.moreInfoUrl, false);
        }
        if (tool.imageUrl != null) {
            builder.setImage(tool.imageUrl);
        }

        channel.sendMessageEmbeds(builder.build()).queue();
    }
}
