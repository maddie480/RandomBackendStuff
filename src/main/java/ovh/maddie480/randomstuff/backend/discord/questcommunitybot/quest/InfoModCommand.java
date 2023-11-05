package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.quest;

import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class InfoModCommand implements BotCommand {
    @Override
    public String getCommandName() {
        return "info_mod";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"name*"};
    }

    @Override
    public String getShortHelp() {
        return "Obtenir des informations sur un mod";
    }

    @Override
    public String getFullHelp() {
        return "Donne les infos qui sont affichées dans le Mod Manager.";
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
        ListModsCommand.getAllQuestMods().stream()
                .filter(mod -> normalize(mod.getName()).equals(normalize(parameters[0])))
                .findFirst()
                .ifPresentOrElse(
                        mod -> printModInfo(event, mod),
                        () -> event.getChannel().sendMessage("Mod non trouvé.").queue()
                );
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) {
        return false;
    }

    private void printModInfo(MessageReceivedEvent event, Mod parsedMod) {
        EmbedBuilder embedBuilder = new EmbedBuilder()
                .setTitle(parsedMod.getName(), parsedMod.getModUrl())
                .addField("Version", parsedMod.getVersion(), true)
                .addField("Auteur", parsedMod.getAuthor(), true);

        if (!parsedMod.getDescription().isEmpty()) {
            embedBuilder.setDescription(parsedMod.getDescription()
                    .replace("<br>", "\n")
                    .replace("<i>", "*")
                    .replace("<u>", "__")
                    .replace("</i>", "*")
                    .replace("</u>", "__")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("<hr>", "\n----------\n"));
        }
        if (!parsedMod.getWebPage().isEmpty()) {
            embedBuilder.addField("Page Web", parsedMod.getWebPage(), false);
        }
        if (!parsedMod.getImageUrl().isEmpty()) {
            embedBuilder.setThumbnail(parsedMod.getImageUrl());
        }

        List<String> properties = new ArrayList<>();
        if (parsedMod.isNeedTexmod()) {
            properties.add("Nécessite le texmod");
        }
        if (parsedMod.isHasCheckpointSupport()) {
            properties.add("Supporte les checkpoints");
        }
        if (!properties.isEmpty()) {
            embedBuilder.addField("Propriétés", String.join(", ", properties), false);
        }

        event.getChannel().sendMessageEmbeds(embedBuilder.build()).queue();
    }

    static String normalize(String name) {
        name = StringUtils.stripAccents(name).toLowerCase();
        StringBuilder endName = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            if (Character.isLetter(name.charAt(i)) || name.charAt(i) == '.' || Character.isDigit(name.charAt(i))) {
                endName.append(name.charAt(i));
            }
        }
        return endName.toString();
    }
}
