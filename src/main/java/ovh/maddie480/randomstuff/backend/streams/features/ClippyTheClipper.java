package ovh.maddie480.randomstuff.backend.streams.features;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.streams.apis.ChatMessage;
import ovh.maddie480.randomstuff.backend.streams.apis.TwitchChatProvider;

/**
 * Small utility class that makes clips on Twitch with a rate limit.
 */
public class ClippyTheClipper {
    private static final Logger logger = LoggerFactory.getLogger(ClippyTheClipper.class);

    private long lastClipAt = 0;
    private boolean rateLimitMessageSent = false;

    private final TwitchChatProvider twitchChatProvider;

    public ClippyTheClipper(TwitchChatProvider twitchChatProvider) {
        this.twitchChatProvider = twitchChatProvider;
    }

    public void makeClip(ChatMessage<?> triggeredByMessage) {
        if (System.currentTimeMillis() - lastClipAt > 30000) {
            logger.debug("Time to create a clip!");
            twitchChatProvider.makeClip(triggeredByMessage);
            lastClipAt = System.currentTimeMillis();
            rateLimitMessageSent = false;
        } else if (!rateLimitMessageSent) {
            logger.debug("The user was rate limited with a message");
            triggeredByMessage.respond("Du calme ! Le dernier clip a été pris il y a moins de 30 secondes.");
            rateLimitMessageSent = true;
        } else {
            logger.debug("The user was rate limited without a message");
        }
    }
}
