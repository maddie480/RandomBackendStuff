package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.daily;

import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.IOException;

public class StonkUpdateChecker {
    public static void postTo(MessageChannel target) throws IOException {
        for (String url : SecretConstants.STONK_URL.split(",")) {
            String stonk = ConnectionUtils.jsoupGetWithRetry(url)
                    .select(".digest-header .header-devise")
                    .text().trim();

            target.sendMessage(":chart: " + stonk).queue();
        }
    }
}
