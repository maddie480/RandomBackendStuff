package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import java.io.IOException;

/**
 * Notifies other platforms about updates to the mod updater database or Everest versions.
 * Run at the end of the update loop.
 */
public class UpdateOutgoingWebhooks {
    private static final Logger log = LoggerFactory.getLogger(UpdateOutgoingWebhooks.class);

    private static boolean changesHappened = false;
    private static boolean alreadyDone = false;

    public static void changesHappened() {
        changesHappened = true;
    }

    public static void notifyUpdate() throws IOException {
        if (!changesHappened) {
            return;
        }

        if (!alreadyDone) {
            alreadyDone = true;
        ConnectionUtils.runWithRetry(() -> {
            OtobotMirror.getInstance().update();
            return null; // method signature
        });
        }

        WebhookExecutor.executeWebhook(
                SecretConstants.UPDATE_CHECKER_LOGS_HOOK,
                "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/update-checker.png",
                "Everest Update Checker",
                ":tada: Update Checker data was refreshed.");

        changesHappened = false;
    }
}
