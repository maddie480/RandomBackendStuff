package ovh.maddie480.randomstuff.backend.discord.crontabs;

import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.DiscardableJDA;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Since all invites for Games Bot, Custom Slash Commands and Timezone Bot without roles (on maddie480.ovh or top.gg) do not include
 * the "bot" scope, those bots should not be invited in guilds, only their slash commands, unless the users alter the invite URL themselves.
 * This class reports servers on a private channel to track how many servers did this...
 */
public class MembershipNotifier {
    public static void main(String[] args) throws IOException {
        for (String token : Arrays.asList(SecretConstants.GAMES_BOT_TOKEN, SecretConstants.CUSTOM_SLASH_COMMANDS_TOKEN,
                SecretConstants.TIMEZONE_BOT_LITE_TOKEN, SecretConstants.BANANABOT_TOKEN)) {

            try (DiscardableJDA jda = new DiscardableJDA(token)) {
                String message;
                if (jda.getGuilds().isEmpty()) {
                    message = "**" + jda.getSelfUser().getEffectiveName() + "** is not a member of any server.";
                } else {
                    message = "**" + jda.getSelfUser().getEffectiveName() + "** is a member of the following servers:\n- "
                            + jda.getGuilds().stream()
                            .map(guild -> "**" + guild.getName() + "** (`" + guild.getId() + "`), as **" + guild.getSelfMember().getEffectiveName() + "**")
                            .collect(Collectors.joining("\n- "));
                }

                WebhookExecutor.executeWebhook(
                        SecretConstants.PERSONAL_NOTIFICATION_WEBHOOK_URL,
                        "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/compute-engine.png",
                        "Bot Membership Notifier",
                        message);

            }
        }
    }
}
