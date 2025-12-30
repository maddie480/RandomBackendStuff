package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.misc;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

/**
 * This is a service that reposts stuff prefixed with # to a webhook.
 */
public class WebhookReposter {
    private static final Logger logger = LoggerFactory.getLogger(WebhookReposter.class);

    private static final long ACTIVE_IN_CHANNEL_ID = 445631337315958796L;
    private static final long AVATAR_CHANNEL_ID = 1280617841980080158L;

    private static final Map<String, String> avatarUrls = new HashMap<>();

    private record Identity(String prefix, String avatarFileName, String nickname) {}

    public static boolean onMessageReceived(MessageReceivedEvent event) throws IOException {
        if (event.getChannel().getIdLong() != ACTIVE_IN_CHANNEL_ID
                || event.getAuthor().isBot()) {

            return false;
        }

        // try finding the identity matching the prefix
        Identity identity = getIdentities().stream()
            .filter(id -> event.getMessage().getContentRaw().startsWith(id.prefix()))
            .findFirst().orElse(null);
        if (identity == null) return false;

        // download all attachments to a temp directory
        Path tempDirectory = Files.createTempDirectory("webhookreposter_");
        List<Path> attachments = new ArrayList<>();
        for (Message.Attachment attachment : event.getMessage().getAttachments()) {
            try {
                logger.debug("Downloading attachment {}", attachment.getFileName());
                Path file = tempDirectory.resolve(attachment.getFileName());
                file = attachment.getProxy().downloadToPath(file).get();
                attachments.add(file);
            } catch (InterruptedException | ExecutionException e) {
                throw new IOException(e);
            }
        }

        // upload the URL as a Discord attachment
        String avatarUrl;
        synchronized (avatarUrls) {
            avatarUrl = avatarUrls.get(identity.avatarFileName());
            logger.debug("Cached avatar URL for {} is {}", identity.avatarFileName(), avatarUrl);
            if (avatarUrl == null) {
                avatarUrl = event.getJDA()
                    .getTextChannelById(AVATAR_CHANNEL_ID)
                    .sendMessage(MessageCreateData.fromFiles(FileUpload.fromData(
                        Paths.get("webhook_reposter", identity.avatarFileName()))))
                    .complete()
                    .getAttachments().get(0)
                    .getUrl();
                avatarUrls.put(identity.avatarFileName(), avatarUrl);
                logger.debug("Uploaded the avatar, got URL: {}", avatarUrl);

                new Thread(() -> {
                    try {
                        Thread.sleep(3_600_000L);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    synchronized (avatarUrls) {
                        avatarUrls.remove(identity.avatarFileName());
                        logger.debug("Cached avatar for {} expired", identity.avatarFileName());
                    }
                }).start();
            }
        }

        // repost the message
        WebhookExecutor.executeWebhook(
                SecretConstants.REPOST_WEBHOOK_URL,
                avatarUrl,
                identity.nickname(),
                event.getMessage().getContentRaw().substring(identity.prefix().length()).trim(),
                /* allowUserMentions: */ false,
                attachments.stream().map(Path::toFile).toList()
        );
        event.getMessage().delete().queue();

        // delete the temp directory with the attachments
        for (Path path : attachments) {
            logger.debug("Deleting temp file for attachment at {}", path.toAbsolutePath());
            Files.delete(path);
        }
        Files.delete(tempDirectory);

        return true;
    }

    private static List<Identity> getIdentities() throws IOException {
        try (Stream<String> lines = Files.lines(Paths.get("webhook_reposter_identities.csv"))) {
            return lines.map(line -> {
                String[] split = line.split(";", 3);
                return new Identity(
                    /* prefix: */ split[0],
                    /* avatarFileName: */ split[1],
                    /* nickname: */ split[2]
                );
            }).toList();
        }
    }
}
