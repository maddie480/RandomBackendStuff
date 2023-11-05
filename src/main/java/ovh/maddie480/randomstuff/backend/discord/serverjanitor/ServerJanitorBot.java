package ovh.maddie480.randomstuff.backend.discord.serverjanitor;

import ovh.maddie480.randomstuff.backend.SecretConstants;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * This bot is run once a day to clean up old messages (> 1 month) in a few private channels on the support server.
 */
public class ServerJanitorBot extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(ServerJanitorBot.class);

    public static void main(String[] args) throws InterruptedException {
        JDA jda = JDABuilder.create(SecretConstants.SERVER_JANITOR_TOKEN, GatewayIntent.GUILD_MESSAGES)
                .addEventListeners(new ServerJanitorBot())
                .build().awaitReady();

        for (long channelId : SecretConstants.SUPPORT_SERVER_CHANNELS_TO_CLEAN_UP) {
            TextChannel channel = jda.getGuildById(SecretConstants.SUPPORT_SERVER_ID).getTextChannelById(channelId);

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

        jda.shutdown();
        jda.awaitShutdown();
    }
}
