package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.daily;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.discord.slashcommandbot.SlashCommandBot;
import ovh.maddie480.randomstuff.backend.utils.DiscardableJDA;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

public class PrivateDiscordJanitor {
    private static final Logger logger = LoggerFactory.getLogger(PrivateDiscordJanitor.class);

    private JDA botClient;

    public static void runCleanup() throws IOException {
        try (DiscardableJDA questBot = new DiscardableJDA(SecretConstants.QUEST_COMMUNITY_BOT_TOKEN, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)) {
            logger.info("Running cleanup...");
            PrivateDiscordJanitor j = new PrivateDiscordJanitor();
            j.botClient = questBot;
            j.run();
        }

        try (DiscardableJDA slashBot = new DiscardableJDA(SecretConstants.SLASH_COMMAND_BOT_TOKEN, GatewayIntent.GUILD_MESSAGES)) {
            logger.info("Running cleanup...");
            SlashCommandBot.deleteOldMessages(slashBot);
        }

        logger.info("Finished!");
    }

    private void run() {
        cleanupChannel(498584991194808354L, OffsetDateTime.now().minusMonths(1)); // #quest_community_bot
        cleanupChannel(791795741919674388L, OffsetDateTime.now().minusMonths(1)); // #update_checker_log
        cleanupChannel(551822297573490749L, OffsetDateTime.now().minusMonths(1)); // #webhook_hell
        cleanupChannel(1280617841980080158L, OffsetDateTime.now().minusDays(1));  // #crontab_logs
        cleanupChannel(445236692136230943L, OffsetDateTime.now().minusMonths(1)); // #poubelle
        cleanupChannel(445631337315958796L, OffsetDateTime.now().minusMonths(1)); // #maddies_headspace
    }

    private void cleanupChannel(final long channelId, final OffsetDateTime delay) {
        TextChannel channel = botClient.getGuildById(443390765826179072L).getTextChannelById(channelId);

        logger.debug("Récupération des messages à supprimer dans {}...", channel);

        List<Message> pins = channel.retrievePinnedMessages().complete();

        long[] messagesToDelete = channel.getIterableHistory().stream()
                .filter(message -> // messages older than a month and not pinned
                        message.getTimeCreated().isBefore(delay)
                            && pins.stream().noneMatch(pin -> pin.getIdLong() == message.getIdLong()))
                .mapToLong(Message::getIdLong)
                .toArray();

        logger.debug("{} messages seront supprimés dans {}", messagesToDelete.length, channel);

        for (long messageId : messagesToDelete) {
            channel.deleteMessageById(messageId).complete();
        }
    }
}
