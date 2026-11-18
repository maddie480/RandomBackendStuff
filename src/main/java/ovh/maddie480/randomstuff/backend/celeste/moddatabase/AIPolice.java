package ovh.maddie480.randomstuff.backend.celeste.moddatabase;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ContentWarningRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ModRecord;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ovh.maddie480.randomstuff.backend.celeste.crontabs.GameBananaAutomatedChecks.enhanceYourWebhook;
import static ovh.maddie480.randomstuff.backend.celeste.crontabs.GameBananaAutomatedChecks.getMaskedEnhancedEmbedLink;

public class AIPolice {
    private static final Logger log = LoggerFactory.getLogger(AIPolice.class);

    private record TinyModInfo(String id, String name, String pageUrl, ContentWarningRecord[] aiWarnings) {
    }

    private final Map<String, TinyModInfo> initialState;

    private Map<String, TinyModInfo> patrolAIStatuses(ModDatabase database) {
        return database.allMods.stream()
                .collect(Collectors.toMap(
                        m -> m.id,
                        m -> new TinyModInfo(m.id, m.name, m.pageUrl, m.aiWarnings)));
    }

    public AIPolice(ModDatabase database) {
        initialState = patrolAIStatuses(database);
    }

    public void checkForChanges(ModDatabase database) {
        Map<String, TinyModInfo> newState = patrolAIStatuses(database);

        for (String id : newState.keySet()) {
            TinyModInfo newInfo = newState.get(id);
            ContentWarningRecord[] newAIWarnings = newInfo.aiWarnings;
            if (!initialState.containsKey(id)) {
                if (newAIWarnings.length != 0) {
                    yell(database, ":a: A new mod appeared with AI warnings!", null, newInfo);
                }
                continue;
            }

            ContentWarningRecord[] oldAIWarnings = initialState.get(id).aiWarnings;
            if (Arrays.equals(oldAIWarnings, newAIWarnings)) continue;

            yell(database, ":control_knobs: The AI warnings of a mod were changed.", oldAIWarnings, newInfo);
        }
    }

    private String getEnhancedLink(TinyModInfo info) {
        ModRecord m = new ModRecord();
        m.id = info.id;
        m.pageUrl = info.pageUrl;
        return getMaskedEnhancedEmbedLink(m, null);
    }

    private void yell(ModDatabase database, String message, ContentWarningRecord[] oldAIWarnings, TinyModInfo newModInfo) {
        final String realMessage = message + "\n"
                + "**" + newModInfo.name + "** - " + getEnhancedLink(newModInfo) + "\n"
                + (oldAIWarnings == null ? "" : ("- Old values: " + formatAIWarnings(oldAIWarnings) + "\n"))
                + "- New values: " + formatAIWarnings(newModInfo.aiWarnings);

        try {
            for (String webhook : Arrays.asList(SecretConstants.BANANA_ILLUMINATI_HOOK, SecretConstants.UPDATE_CHECKER_LOGS_HOOK)) {
                ConnectionUtils.runWithRetry(() -> {
                    Pair<String, List<Map<String, Object>>> enhanced = enhanceYourWebhook(database, realMessage);

                    WebhookExecutor.executeWebhook(
                            webhook,
                            "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/red-ai-icon.png",
                            "Ominous Red Icon Alert",
                            enhanced.getLeft(),
                            enhanced.getRight().isEmpty() ? null : enhanced.getRight()
                    );
                    return null;
                });
            }
        } catch (IOException e) {
            log.warn("Couldn't invoke the AI police, skipping", e);
        }
    }

    private String formatAIWarnings(ContentWarningRecord[] aiWarnings) {
        if (aiWarnings.length == 0) return "_None_";

        return Arrays.stream(aiWarnings)
                .map(warning -> {
                    String s = warning.name + " (" + warning.severityLabel + ")";
                    if (warning.severity >= 2) s = "**" + s + "**";
                    if (warning.severity >= 3) s = "__" + s + "__";
                    return s;
                })
                .collect(Collectors.joining(", "));
    }
}
