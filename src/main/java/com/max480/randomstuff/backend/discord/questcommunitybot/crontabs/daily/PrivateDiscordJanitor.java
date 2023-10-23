package com.max480.randomstuff.backend.discord.questcommunitybot.crontabs.daily;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.discord.slashcommandbot.SlashCommandBot;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;

public class PrivateDiscordJanitor {
    private static final Logger logger = LoggerFactory.getLogger(PrivateDiscordJanitor.class);

    private JDA botClient;

    public static void runCleanup() throws InterruptedException {
        {
            logger.info("Starting up Quest Community Bot...");
            JDA questBot = JDABuilder.createLight(SecretConstants.QUEST_COMMUNITY_BOT_TOKEN, GatewayIntent.GUILD_MESSAGES)
                    .build().awaitReady();

            logger.info("Running cleanup...");
            PrivateDiscordJanitor j = new PrivateDiscordJanitor();
            j.botClient = questBot;
            j.run();

            logger.info("Waiting for Quest Community Bot shutdown...");
            questBot.shutdown();
            questBot.awaitShutdown();
        }

        {
            logger.info("Starting up Slash Command Bot...");
            JDA slashBot = JDABuilder.createLight(SecretConstants.SLASH_COMMAND_BOT_TOKEN, GatewayIntent.GUILD_MESSAGES)
                    .build().awaitReady();

            logger.info("Running cleanup...");
            SlashCommandBot.deleteOldMessages(slashBot);

            logger.info("Waiting for Slash Command Bot shutdown...");
            slashBot.shutdown();
            slashBot.awaitShutdown();
        }

        logger.info("Finished!");
        System.exit(0);
    }

    private void run() {
        cleanupChannel(498584991194808354L);  // #quest_community_bot
        cleanupChannel(791795741919674388L);  // #update_checker_log
        cleanupChannel(551822297573490749L);  // #notifications
        cleanupChannel(445236692136230943L);  // #poubelle
        cleanupChannel(445631337315958796L);  // #maddies_headspace
    }

    private void cleanupChannel(final long channelId) {
        TextChannel channel = botClient.getGuildById(443390765826179072L).getTextChannelById(channelId);

        logger.debug("Récupération des messages à supprimer dans {}...", channel);

        List<Message> pins = channel.retrievePinnedMessages().complete();

        long[] messagesToDelete = channel.getIterableHistory().stream()
                .filter(message ->
                        // message from a specific user
                        message.getAuthor().getIdLong() == 950840173732724796L ||

                                // message older than a month and not pinned
                                (message.getTimeCreated().isBefore(OffsetDateTime.now().minusMonths(1))
                                        && pins.stream().noneMatch(pin -> pin.getIdLong() == message.getIdLong())))
                .mapToLong(Message::getIdLong)
                .toArray();

        logger.debug("{} messages seront supprimés dans {}", messagesToDelete.length, channel);

        for (long messageId : messagesToDelete) {
            channel.deleteMessageById(messageId).complete();
        }
    }
}
