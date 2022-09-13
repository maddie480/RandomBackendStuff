package com.max480.quest.modmanagerbot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;

/**
 * This is a dummy file to tell the pipeline that BotClient.getInstance() indeed exists.
 * It leads to a private bot.
 */
public class BotClient {
    public static JDA getInstance() {
        return JDABuilder.create("", GatewayIntent.GUILD_MESSAGES).build();
    }
}
