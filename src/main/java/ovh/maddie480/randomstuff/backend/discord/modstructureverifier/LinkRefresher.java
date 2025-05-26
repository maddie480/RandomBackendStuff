package ovh.maddie480.randomstuff.backend.discord.modstructureverifier;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public class LinkRefresher {
    private static final Logger log = LoggerFactory.getLogger(LinkRefresher.class);

    static void start(JDA jda, List<MessageEmbedPair> messages, Runnable onUpdateFinish) {
        new Thread("Mod Structure Verifier Link Refresher") {
            @Override
            public void run() {
                while (true) {
                    try {
                        refreshLinks(jda, messages);
                        onUpdateFinish.run();
                    } catch (Exception e) {
                        log.error("Uncaught exception during link refreshing", e);
                    }

                    try {
                        Thread.sleep(3600000 - (ZonedDateTime.now().getMinute() * 60000
                                + ZonedDateTime.now().getSecond() * 1000
                                + ZonedDateTime.now().getNano() / 1_000_000) + 50);
                    } catch (InterruptedException e) {
                        log.error("Sleep interrupted", e);
                    }
                }
            }
        }.start();
    }

    private static void refreshLinks(JDA jda, List<MessageEmbedPair> messages) throws Exception {
        List<MessageEmbedPair> newMessages = new ArrayList<>();

        for (MessageEmbedPair message : messages) {
            Guild guild = jda.getGuildById(message.guildId());
            if (guild == null) {
                log.warn("Forgetting message {} because its associated guild is unknown: {}", message.messageId(), message.guildId());
                continue;
            }

            TextChannel channel = guild.getTextChannelById(message.channelId());
            if (channel == null) {
                log.warn("Forgetting message {} because its associated text channel in guild {} is unknown: {}", message.messageId(), guild, message.channelId());
                continue;
            }

            ZonedDateTime messageDate = Instant.ofEpochMilli((message.messageId() >> 22) + 1420070400000L).atZone(ZoneId.systemDefault());
            if (messageDate.isBefore(ZonedDateTime.now().minusMonths(6))) {
                log.warn("Forgetting message {} because its date is {}", message.messageId(), messageDate);
                continue;
            }

            ZonedDateTime linkExpiresAt = Instant.ofEpochSecond(message.expiresAt()).atZone(ZoneId.systemDefault());
            if (linkExpiresAt.isAfter(ZonedDateTime.now())) {
                // link didn't expire yet!
                newMessages.add(message);
                continue;
            }

            Message origMessage;
            Message embedMessage;

            try {
                origMessage = channel.retrieveMessageById(message.messageId()).complete();
                embedMessage = channel.retrieveMessageById(message.embedId()).complete();
            } catch (ErrorResponseException e) {
                if (e.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) {
                    log.warn("Forgetting message {} because it wasn't found in channel {} of guild {}", message.messageId(), channel, guild);
                    continue;
                }

                throw e;

            } catch (InsufficientPermissionException e) {
                log.warn("Forgetting message {} because we don't have access to it", message.messageId(), e);
                continue;
            }

            if (origMessage.getAttachments().isEmpty()) {
                log.warn("Forgetting message {} because the attachment was deleted", message.messageId());
                continue;
            }
            if (embedMessage.getEmbeds().isEmpty()) {
                log.warn("Forgetting message {} because the embed was deleted", message.messageId());
                continue;
            }

            log.info("Refreshing link of message {} that expired on {}", message.embedId(), linkExpiresAt);

            String url = origMessage.getAttachments().get(0).getUrl();
            linkExpiresAt = Instant.ofEpochSecond(getLinkExpirationTimestamp(url)).atZone(ZoneId.systemDefault());

            log.info("New link expires on {}: {}", linkExpiresAt, url);
            if (linkExpiresAt.isBefore(ZonedDateTime.now())) {
                throw new Exception("New link is still expired!");
            }

            MessageEmbed originalEmbed = embedMessage.getEmbeds().get(0);
            MessageEmbed newEmbed = new EmbedBuilder()
                    .setTitle(originalEmbed.getTitle(), "https://0x0a.de/twoclick?" + url)
                    .setDescription(originalEmbed.getDescription())
                    .setTimestamp(originalEmbed.getTimestamp())
                    .build();
            embedMessage.editMessageEmbeds(newEmbed).queue();

            message = message.setExpiresAt(getLinkExpirationTimestamp(url));
            newMessages.add(message);
        }

        messages.clear();
        messages.addAll(newMessages);
    }

    static long getLinkExpirationTimestamp(String link) {
        if (link.contains("ex=")) {
            link = link.substring(link.indexOf("ex=") + 3);
            link = link.substring(0, link.indexOf("&"));
            return Long.parseLong(link.toUpperCase(), 16);
        } else {
            return 0;
        }
    }
}
