package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.misc;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * This is a service that reposts stuff prefixed with # to a webhook.
 */
public class WebhookReposter {
    private static final long ACTIVE_IN_CHANNEL_ID = 445631337315958796L;
    private static final long AVATAR_CHANNEL_ID = 445236692136230943L;
    private static final long AVATAR_MESSAGE_ID = 1238389991416266793L;

    public static boolean onMessageReceived(MessageReceivedEvent event) throws IOException {
        if (event.getChannel().getIdLong() != ACTIVE_IN_CHANNEL_ID
                || event.getAuthor().isBot()
                || !event.getMessage().getContentRaw().startsWith("#")) {

            return false;
        }

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
                .retrieveMessageById(AVATAR_MESSAGE_ID).complete()
                .getAttachments().get(0)
                .getUrl();

        // repost the message
        WebhookExecutor.executeWebhook(
                SecretConstants.REPOST_WEBHOOK_URL,
                avatarUrl,
                SecretConstants.REPOST_WEBHOOK_NAME,
                event.getMessage().getContentRaw().substring(1).trim(),
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
}
