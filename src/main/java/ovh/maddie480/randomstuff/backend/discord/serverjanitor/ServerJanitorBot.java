package ovh.maddie480.randomstuff.backend.discord.serverjanitor;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.DiscardableJDA;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * This bot is run once a day to clean up old messages (> 1 month) in a few private channels on the support server.
 */
public class ServerJanitorBot {
    private static final Logger log = LoggerFactory.getLogger(ServerJanitorBot.class);

    public static void main(String[] args) throws IOException {
        try (DiscardableJDA jda = new DiscardableJDA(SecretConstants.SERVER_JANITOR_TOKEN, GatewayIntent.GUILD_MESSAGES)) {
            for (String serverAndChannelIdRaw : SecretConstants.SUPPORT_SERVER_CHANNELS_TO_CLEAN_UP) {
                String[] serverAndChannelId = serverAndChannelIdRaw.split(";");
                TextChannel channel = jda.getGuildById(serverAndChannelId[0]).getTextChannelById(serverAndChannelId[1]);

                log.debug("Checking for messages to delete in {}...", channel);

                List<Message> pins = channel.retrievePinnedMessages().complete();

                // delete all messages that are not pinned and older than a month
                long[] messagesToDelete = channel.getIterableHistory().stream()
                        .filter(message -> (message.getTimeCreated().isBefore(OffsetDateTime.now().minusMonths(1))
                                && pins.stream().noneMatch(pin -> pin.getIdLong() == message.getIdLong())))
                        .mapToLong(Message::getIdLong)
                        .toArray();

                log.debug("Deleting {} message(s) from {}...", messagesToDelete.length, channel);

                for (long messageId : messagesToDelete) {
                    channel.deleteMessageById(messageId).complete();
                }
            }
        }
    }
}
