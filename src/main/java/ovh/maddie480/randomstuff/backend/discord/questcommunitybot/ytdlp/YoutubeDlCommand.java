package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.ytdlp;

import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class YoutubeDlCommand implements BotCommand {
    private static final Logger log = LoggerFactory.getLogger(YoutubeDlCommand.class);

    @Override
    public String getCommandName() {
        return "youtube_dl";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"qualité", "url*"};
    }

    @Override
    public String getShortHelp() {
        return "Télécharge une vidéo avec yt-dlp";
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
        handleYoutubeDL(event.getMessage(), parameters, false, false);
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }

    static void handleYoutubeDL(Message msg, String[] parameters, boolean sponsorBlock, boolean audio) throws IOException {
        String format, url;

        if (parameters.length == 1) {
            // par défaut, on demande du 720p
            format = "best[height <= 720]";
            url = parameters[0];
        } else {
            format = parameters[0];
            url = parameters[1];
        }

        final File tempFolder = new File("/tmp/youtube_dl_" + System.currentTimeMillis());
        Files.createDirectories(tempFolder.toPath());

        msg.addReaction(Emoji.fromUnicode("\uD83D\uDC4C")).queue(); // ok_hand
        msg.delete().queueAfter(5, TimeUnit.MINUTES);

        new Thread("youtube-dl runner") {
            @Override
            public void run() {
                log.info("youtube-dl starting for format {} and url {} in {}", format, url, tempFolder.getAbsolutePath());

                try {
                    Process p;
                    if (sponsorBlock) {
                        p = new ProcessBuilder("/app/static/youtube-dl",
                                "-f", format, "--sponsorblock-remove", "sponsor,selfpromo", url)
                                .directory(tempFolder)
                                .inheritIO()
                                .start();
                    } else if (audio) {
                        p = new ProcessBuilder("/app/static/youtube-dl", "-f",
                                "bestaudio/bestaudio*", "-x", "--audio-format", "mp3", url)
                                .directory(tempFolder)
                                .inheritIO()
                                .start();
                    } else {
                        p = new ProcessBuilder("/app/static/youtube-dl", "-f", format, url)
                                .directory(tempFolder)
                                .inheritIO()
                                .start();
                    }

                    p.waitFor();

                    if (p.exitValue() != 0) {
                        log.warn("youtube-dl ckc, exit code = {}", p.exitValue());

                        msg.getChannel().sendMessage("Il y a eu un problème. <:A_ckc:644445091884171264>")
                                .queue(message -> message.delete().queueAfter(5, TimeUnit.MINUTES));
                    } else {
                        log.warn("youtube-dl done!");
                        youtubeDlUpload(msg, tempFolder);
                    }
                } catch (IOException | InterruptedException e) {
                    log.error("youtube-dl ckc", e);
                    msg.getChannel().sendMessage("Il y a eu un problème. <:A_ckc:644445091884171264>")
                            .queue(message -> message.delete().queueAfter(5, TimeUnit.MINUTES));
                }

                try {
                    FileUtils.deleteDirectory(tempFolder);
                } catch (IOException e) {
                    log.error("Could not delete youtube-dl directory", e);
                }
            }
        }.start();
    }

    private static void youtubeDlUpload(Message msg, File tempFolder) throws IOException {
        try (Stream<Path> files = Files.list(tempFolder.toPath())) {
            for (Path path : files.toList()) {
                File file = path.toFile();
                Files.move(file.toPath(), Paths.get("/shared/temp/youtube-dl/" + file.getName()));
                msg.getChannel().sendMessage("Fichier téléchargé : `" + file.getName() + "` !")
                        .queue(message -> message.delete().queueAfter(5, TimeUnit.MINUTES));
            }
        }
    }
}
