package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.misc;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import ovh.maddie480.randomstuff.backend.utils.OutputStreamLogger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

public class AsciiTextCommand implements BotCommand {
    private static final Logger log = LoggerFactory.getLogger(AsciiTextCommand.class);

    @Override
    public String getCommandName() {
        return "ascii_text";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"font", "message*"};
    }

    @Override
    public String getShortHelp() {
        return "Ecrit un message en ascii art";
    }

    @Override
    public String getFullHelp() {
        return "Si la police n'est pas précisée, une police par défaut sera utilisée.\n" +
                "A noter que le rendu peut être très moche et illisible sur mobile, il est conseillé d'utiliser cette commande sur PC.\n\n" +
                "Liste des polices : " + String.join(", ", getAvailableFonts());
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
        Process figlet = null;
        String text = null;
        if (parameters.length == 2) {
            if (!getAvailableFonts().contains(parameters[0])) {
                event.getChannel().sendMessage("La police `" + parameters[0] + "` n'existe pas !\nFais `!help ascii_text` pour avoir la liste des polices.").queue();
            } else {
                figlet = OutputStreamLogger.redirectErrorOutput(log,
                        new ProcessBuilder("/usr/bin/figlet", "-f", parameters[0]).start());
                text = parameters[1];
            }
        } else {
            figlet = OutputStreamLogger.redirectErrorOutput(log,
                    new ProcessBuilder("/usr/bin/figlet").start());
            text = parameters[0];
        }

        if (figlet != null) {
            IOUtils.write(text, figlet.getOutputStream(), StandardCharsets.ISO_8859_1);
            figlet.getOutputStream().close();

            String resultAscii = IOUtils.toString(figlet.getInputStream(), UTF_8);
            if (resultAscii.length() + 7 > 2000) {
                event.getChannel().sendMessage("Le résultat dépasse les 2000 caractères. Essaie avec un texte plus court.").queue();
            } else {
                event.getChannel().sendMessage("```\n" + resultAscii + "```").queue();
            }
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }

    private static List<String> getAvailableFonts() {
        return Arrays.stream(new File("/usr/share/figlet").list())
                .filter(name -> name.endsWith(".flf"))
                .map(name -> name.substring(0, name.length() - 4))
                .sorted()
                .toList();
    }
}
