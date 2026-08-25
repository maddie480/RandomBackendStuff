package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import java.io.IOException;

/**
 * Notifies other platforms about updates to the mod updater database or Everest versions.
 * Run at the end of the update loop.
 */
public class UpdateOutgoingWebhooks {
    public static void notifyUpdate() throws IOException {
        ConnectionUtils.runWithRetry(() -> {
            OtobotMirror.getInstance().update();
            return null; // method signature
        });

        GitHubMirror.main(null);

        for (String webhook : SecretConstants.UPDATE_CHECKER_HOOKS) {
            WebhookExecutor.executeWebhook(
                    webhook,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/update-checker.png",
                    "Everest Update Checker",
                    ":tada: Update Checker data was refreshed.",
                    ImmutableMap.of("X-Everest-Log", "true"));
        }
    }
}
