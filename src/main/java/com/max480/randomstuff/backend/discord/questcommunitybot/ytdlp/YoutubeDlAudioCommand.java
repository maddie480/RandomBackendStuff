package com.max480.randomstuff.backend.discord.questcommunitybot.ytdlp;

import com.max480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class YoutubeDlAudioCommand implements BotCommand {
    private static final Logger log = LoggerFactory.getLogger(YoutubeDlAudioCommand.class);

    @Override
    public String getCommandName() {
        return "youtube_dl_audio";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"url*"};
    }

    @Override
    public String getShortHelp() {
        return "Télécharge du son avec youtube-dl et le convertit en MP3";
    }

    @Override
    public String getFullHelp() {
        return "Le résultat sera stocké dans `/shared/temp/youtube-dl` sur le VPS.";
    }

    @Override
    public boolean isAdminOnly() {
        return true;
    }

    @Override
    public boolean areParametersValid(String[] parameters) {
        return true;
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        YoutubeDlCommand.handleYoutubeDL(event.getMessage(), parameters, false, true);
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
