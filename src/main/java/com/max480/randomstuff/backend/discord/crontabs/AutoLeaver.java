package com.max480.randomstuff.backend.discord.crontabs;

import com.max480.randomstuff.backend.SecretConstants;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;

/**
 * Since all invites for Games Bot, Custom Slash Commands and Timezone Bot without roles (on maddie480.ovh or top.gg) do not include
 * the "bot" scope, those bots should not be invited in guilds, only their slash commands, unless the users alter the invite URL themselves.
 * <p>
 * To avoid any risk of reaching 76 servers and being asked for verification for a bot that isn't used (even if that's VERY unlikely to happen),
 * said bots come online once a day and leave all servers they're in.
 */
public class AutoLeaver {
    private static final Logger logger = LoggerFactory.getLogger(AutoLeaver.class);

    public static void main(String[] args) throws InterruptedException {
        for (String token : Arrays.asList(SecretConstants.GAMES_BOT_TOKEN, SecretConstants.CUSTOM_SLASH_COMMANDS_TOKEN, SecretConstants.TIMEZONE_BOT_LITE_TOKEN)) {
            final JDA jda = JDABuilder.createLight(token, Collections.emptyList()).build().awaitReady();

            for (Guild guild : jda.getGuilds()) {
                logger.warn("{} is leaving guild {}!", jda.getSelfUser(), guild);
                guild.leave().queue();
            }

            jda.shutdown();
        }
    }
}
