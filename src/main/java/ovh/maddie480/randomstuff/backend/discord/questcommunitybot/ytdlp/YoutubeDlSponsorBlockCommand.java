package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.ytdlp;

import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;

public class YoutubeDlSponsorBlockCommand implements BotCommand {
    @Override
    public String getCommandName() {
        return "youtube_dl_sb";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"qualité", "url*"};
    }

    @Override
    public String getShortHelp() {
        return "Télécharge une vidéo avec youtube-dl et enlève les segments sponsorisés avec SponsorBlock";
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
        YoutubeDlCommand.handleYoutubeDL(event.getMessage(), parameters, true, false);
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
