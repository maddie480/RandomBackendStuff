package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.daily;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.DiscardableJDA;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PrivateDiscordJanitor {
    private static final Logger logger = LoggerFactory.getLogger(PrivateDiscordJanitor.class);

    private JDA botClient;

    private PrivateDiscordJanitor(JDA botClient) {
        this.botClient = botClient;
    }

    public static void runDaily() throws IOException {
        try (DiscardableJDA questBot = new DiscardableJDA(SecretConstants.QUEST_COMMUNITY_BOT_TOKEN, GatewayIntent.GUILD_MESSAGES)) {
            new PrivateDiscordJanitor(questBot).run();
        }
    }

    private void run() {
        cleanupChannel(498584991194808354L, OffsetDateTime.now().minusMonths(1), false); // #quest_community_bot
        cleanupChannel(791795741919674388L, OffsetDateTime.now().minusMonths(1), false); // #update_checker_log
        cleanupChannel(551822297573490749L, OffsetDateTime.now().minusMonths(1), false); // #webhook_hell
        cleanupChannel(445236692136230943L, OffsetDateTime.now().minusMonths(1), false); // #poubelle
        cleanupChannel(445631337315958796L, OffsetDateTime.now().minusMonths(1), false); // #maddies_headspace
    }


    public static void runHourly() throws IOException {
        try (DiscardableJDA questBot = new DiscardableJDA(SecretConstants.QUEST_COMMUNITY_BOT_TOKEN, GatewayIntent.GUILD_MESSAGES)) {
            new PrivateDiscordJanitor(questBot).cleanupChannel(1280617841980080158L, OffsetDateTime.now().minusDays(1), true); // #crontab_logs
        }
    }


    private void cleanupChannel(final long channelId, final OffsetDateTime delay, boolean useBulkDelete) {
        TextChannel channel = botClient.getGuildById(443390765826179072L).getTextChannelById(channelId);

        logger.debug("Récupération des messages à supprimer dans {}...", channel);

        List<Message> pins = channel.retrievePinnedMessages().complete();

        long[] messagesToDelete = channel.getIterableHistory()
                .skipTo(TimeUtil.getDiscordTimestamp(delay.toInstant().toEpochMilli())).stream()
                .filter(message -> // messages older than the specified date and not pinned
                        message.getTimeCreated().isBefore(delay)
                            && pins.stream().noneMatch(pin -> pin.getIdLong() == message.getIdLong()))
                .mapToLong(Message::getIdLong)
                .toArray();

        logger.debug("{} messages seront supprimés dans {}", messagesToDelete.length, channel);

        if (useBulkDelete) {
            List<String> idsToDelete = new ArrayList<>(Arrays.stream(messagesToDelete).mapToObj(Long::toString).toList());
            while (!idsToDelete.isEmpty()) {
                List<String> chunk = idsToDelete.stream().limit(100).toList();
                logger.trace("Deleting messages from {}: {}", channel, chunk);
                (chunk.size() == 1 ? channel.deleteMessageById(chunk.getFirst()) : channel.deleteMessagesByIds(chunk)).complete();
                idsToDelete.removeAll(chunk);
            }
        } else {
            for (long messageId : messagesToDelete) {
                logger.trace("Deleting message from {}: {}", channel, messageId);
                channel.deleteMessageById(messageId).complete();
            }
        }
    }
}
