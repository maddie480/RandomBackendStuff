package com.max480.randomstuff.backend.discord.supportserverbot;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.discord.modstructureverifier.ModStructureVerifier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class ServerManagerBot extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(ServerManagerBot.class);

    private static JDA jda;

    public static void main(String[] args) throws InterruptedException {
        jda = JDABuilder.create(SecretConstants.SERVER_MANAGER_TOKEN, GatewayIntent.GUILD_MESSAGES)
                .addEventListeners(new ServerManagerBot())
                .build().awaitReady();
    }

    // called every hour from a "master loop" across multiple bots
    public static void hourlyCleanup() {
        for (TextChannel channel : jda.getGuildById(SecretConstants.SUPPORT_SERVER_ID)
                .getCategoryById(SecretConstants.SUPPORT_SERVER_PRIVATE_CATEGORY_ID)
                .getTextChannels()) {

            log.debug("Checking if we should delete channel {}...", channel);

            if (channel.getTimeCreated().isBefore(OffsetDateTime.now().minusDays(7))) {
                // channel is more than 7 days old
                channel.getIterableHistory()
                        .takeAsync(1)
                        .thenAccept(message -> {
                            if (message.isEmpty() || message.get(0).getTimeCreated().isBefore(OffsetDateTime.now().minusDays(7))) {
                                // latest message is more than 7 days old or there is no message => this is inactive, let's delete this
                                log.info("Deleting channel {}", channel);
                                channel.delete().queue();
                            }
                        });
            }
        }

        int channelCount = getChannelAssociations().size();
        jda.getPresence().setActivity(Activity.watching(channelCount == 1 ? "1 channel" : channelCount + " channels"));
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        if ("createchannel".equals(event.getButton().getId())) {
            if (getChannelAssociations().containsValue(event.getMember().getIdLong())) {
                event.reply(":x: You already have your own channel!").setEphemeral(true).queue();
            } else {
                // go create a channel!
                event.getGuild()
                        .getCategoryById(SecretConstants.SUPPORT_SERVER_PRIVATE_CATEGORY_ID)
                        .createTextChannel("channel-for-" + event.getUser().getName().replaceAll("[^A-Za-z0-9-]", "-").toLowerCase(Locale.ROOT))
                        .syncPermissionOverrides()
                        .addPermissionOverride(event.getMember(), Collections.singletonList(Permission.VIEW_CHANNEL), Collections.emptyList())
                        .addPermissionOverride(event.getGuild().getRoleById(SecretConstants.MOD_STRUCTURE_VERIFIER_ROLE_ID),
                                Collections.singletonList(Permission.VIEW_CHANNEL), Collections.emptyList())
                        .queue(channel -> {
                            event.reply(":white_check_mark: Your channel is created, you can now use it!").setEphemeral(true).queue();
                            ModStructureVerifier.registerChannelFromSupportServer(channel.getIdLong());
                            channel.sendMessage("You can use the **Mod Structure Verifier** by dropping a zip or a Google Drive link here.\n" +
                                    "Alternatively, you can use `--verify [assets folder name] [maps folder name]` if you also want to check for folders.\n" +
                                    "You can also try out the **Timezone Bot** or the **Games Bot** here.\n" +
                                    "If you need support, you can ping max480!\n\n" +
                                    "The channel will be deleted after 7 days of inactivity.").queue();
                        });
            }
        }
    }

    private static Map<Long, Long> getChannelAssociations() {
        return jda.getGuildById(SecretConstants.SUPPORT_SERVER_ID)
                .getCategoryById(SecretConstants.SUPPORT_SERVER_PRIVATE_CATEGORY_ID)
                .getTextChannels()
                .stream()
                .collect(Collectors.toMap(ISnowflake::getIdLong, t -> {
                    if (t.getMemberPermissionOverrides().isEmpty()) {
                        return 0L;
                    }
                    return t.getMemberPermissionOverrides().get(0).getIdLong();
                }));
    }
}
