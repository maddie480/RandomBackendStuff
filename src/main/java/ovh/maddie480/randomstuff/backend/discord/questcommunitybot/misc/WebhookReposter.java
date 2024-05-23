package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.misc;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

/**
 * This is a service that reposts stuff prefixed with # to a webhook.
 */
public class WebhookReposter {
    private static final long ACTIVE_IN_CHANNEL_ID = 445631337315958796L;
    private static final long AVATAR_CHANNEL_ID = 445236692136230943L;

    private record Identity(String prefix, long avatarMessageId, String nickname) {}

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
                Path file = tempDirectory.resolve(attachment.getFileName());
                file = attachment.getProxy().downloadToPath(file).get();
                attachments.add(file);
            } catch (InterruptedException | ExecutionException e) {
                throw new IOException(e);
            }
        }

        // get the URL of the avatar, which is uploaded as a Discord attachment
        String avatarUrl = event.getJDA()
                .getTextChannelById(AVATAR_CHANNEL_ID)
                .retrieveMessageById(identity.avatarMessageId()).complete()
                .getAttachments().get(0)
                .getUrl();

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
                    /* avatarMessageId: */ Long.parseLong(split[1]),
                    /* nickname: */ split[2]
                );
            }).toList();
        }
    }
}
