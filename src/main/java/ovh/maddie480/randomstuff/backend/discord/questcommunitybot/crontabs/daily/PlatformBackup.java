package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.daily;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.OutputStreamLogger;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class PlatformBackup {
    private static final Logger logger = LoggerFactory.getLogger(PlatformBackup.class);

    public static void run(JDA client) throws IOException {
        runMessageDump(client);

        try {
            Process p = OutputStreamLogger.redirectAllOutput(logger,
                    new ProcessBuilder("/app/static/backup-platform.sh").start());

            p.waitFor();

            if (p.exitValue() != 0) {
                throw new IOException("backup-platform.sh quit with exit code " + p.exitValue());
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    private static void runMessageDump(JDA client) throws IOException {
        Files.createDirectories(Paths.get("/tmp/backup-discord-pins"));

        dumpMessagesFrom(client, 498584991194808354L);
        dumpMessagesFrom(client, 791795741919674388L);
        dumpMessagesFrom(client, 551822297573490749L);
        dumpMessagesFrom(client, 445236692136230943L);
        dumpMessagesFrom(client, 445631337315958796L);
    }

    private static void dumpMessagesFrom(JDA client, Long channelId) throws IOException {
        TextChannel channel = client.getTextChannelById(channelId);
        logger.debug("Dump des pins de {}", channel);

        List<Message> pinnedMessages = channel.retrievePinnedMessages().complete();
        logger.debug("{} messages récupérés", pinnedMessages.size());

        if (pinnedMessages.isEmpty()) return;

        OffsetDateTime date = pinnedMessages.get(0).getTimeCreated();

        try (PrintWriter printWriter = new PrintWriter("/tmp/backup-discord-pins/dump_" + channel.getName() + ".txt")) {
            for (Message message : pinnedMessages) {
                logger.debug("Current message: {} from {} at {}", message.getContentDisplay(),
                        message.getAuthor().getName(), message.getTimeCreated());

                if (date.until(message.getTimeCreated(), ChronoUnit.MINUTES) > 7) {
                    if (!date.truncatedTo(ChronoUnit.DAYS).isEqual(message.getTimeCreated().truncatedTo(ChronoUnit.DAYS))) {
                        printWriter.println("----------");
                    }
                    printWriter.println("----------\n");
                }

                date = message.getTimeCreated();
                dumpMessage(printWriter, message);

                if (!message.getAttachments().isEmpty()) {
                    for (Message.Attachment attachment : message.getAttachments()) {
                        String originalFileName = attachment.getFileName();

                        String newFileName = date.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_" + channel.getName() + "_" + originalFileName;

                        File dest = new File("/tmp/backup-discord-pins/" + newFileName);
                        FileUtils.copyToFile(ConnectionUtils.openStreamWithTimeout(attachment.getUrl()), dest);

                        printWriter.println("[Pièce jointe : " + newFileName + "]");
                    }
                }

                printWriter.println();
            }
        }
    }

    private static void dumpMessage(PrintWriter printWriter, Message msg) {
        printWriter.println("[" + msg.getTimeCreated().format(DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm:ss")) + "]\n" +
                "<" + getUsernameTransitionAware(msg.getAuthor()) + "> " + msg.getContentDisplay());
    }

    static String getUsernameTransitionAware(User user) {
        // new usernames are indicated with a #0000 discriminator, that is supposed to be invisible.
        if ("0000".equals(user.getDiscriminator())) {
            return user.getName();
        } else {
            return user.getName() + "#" + user.getDiscriminator();
        }
    }
}
