package ovh.maddie480.randomstuff.backend.streams.apis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.function.Consumer;

public abstract class AbstractChatProvider<MessageIDType> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractChatProvider.class);

    private long lastClipAt = 0;
    private boolean rateLimitMessageSent = false;

    public abstract void connect(Consumer<ChatMessage<MessageIDType>> messageListener) throws IOException;

    public abstract void sendMessage(String contents);

    public void makeClip(ChatMessage<MessageIDType> triggeredByMessage) {
        if (System.currentTimeMillis() - lastClipAt > 30000) {
            logger.debug("Time to create a clip!");
            actuallyMakeClip(triggeredByMessage);
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

    protected abstract void actuallyMakeClip(ChatMessage<MessageIDType> triggeredByMessage);

    public abstract void respondTo(ChatMessage<MessageIDType> message, String response);
}
