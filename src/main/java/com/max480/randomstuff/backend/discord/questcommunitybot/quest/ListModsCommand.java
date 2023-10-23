package com.max480.randomstuff.backend.discord.questcommunitybot.quest;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import com.max480.randomstuff.backend.discord.questcommunitybot.Utils;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.utils.SplitUtil;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

public class ListModsCommand implements BotCommand {
    private static final Logger log = LoggerFactory.getLogger(ListModsCommand.class);

    @Override
    public String getCommandName() {
        return "list_mods";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"discord_id ou mention"};
    }

    @Override
    public String getShortHelp() {
        return "Lister tous les mods, ou les mods de quelqu'un (si un pseudo est donné en paramètre)";
    }

    @Override
    public String getFullHelp() {
        return "Pour en savoir plus sur un mod, utiliser ensuite `!info_mod [nom du mod]` ou consultez le site.";
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
        if (parameters.length == 0) {
            StringBuilder modList = new StringBuilder("Voici tous les mods que je connais : ");

            for (Mod mod : getAllQuestMods()) {
                modList.append(mod.getName()).append(" v").append(mod.getVersion()).append(", ");
            }

            modList = new StringBuilder(modList.substring(0, modList.length() - 2));

            modList.append("\n\nVous pouvez aussi voir les mods ici :arrow_right: https://quest-community-bot.appspot.com/mods");

            SplitUtil.split(modList.toString(), 2000, true, SplitUtil.Strategy.WHITESPACE, SplitUtil.Strategy.ANYWHERE)
                    .stream()
                    .map(split -> new MessageCreateBuilder().setContent(split).build())
                    .forEach(message -> event.getChannel().sendMessage(message).queue());
        } else {
            Member member = Utils.findMemberFromString(event.getChannel(), parameters[0]);
            if (member == null) return;

            log.debug("Retrieving mods from {}", member);

            StringBuilder modList = new StringBuilder();

            for (Mod mod : getAllQuestMods()) {
                if (member.getUser().getIdLong() == mod.getAuthorDiscordId()) {
                    modList.append(mod.getName()).append(" v").append(mod.getVersion()).append(", ");
                }
            }

            if (modList.isEmpty()) {
                event.getChannel().sendMessage(member.getUser().getName() + " n'a pas de mod !").queue();
            } else {
                SplitUtil.split("Voici la liste des mods de " + member.getUser().getName() + " :\n" + modList, 2000, true, SplitUtil.Strategy.NEWLINE, SplitUtil.Strategy.ANYWHERE)
                        .stream()
                        .map(split -> new MessageCreateBuilder().setContent(split).build())
                        .forEach(message -> event.getChannel().sendMessage(message).queue());
            }
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) {
        return false;
    }

    static List<Mod> getAllQuestMods() throws IOException {
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://quest-community-bot.appspot.com/quest-mod-manager/database.csv");
             BufferedReader br = new BufferedReader(new InputStreamReader(is, UTF_8))) {

            List<Mod> result = new ArrayList<>();
            br.readLine();

            String s;
            while ((s = br.readLine()) != null) {
                result.add(new Mod(s));
            }

            return result;
        }
    }
}
