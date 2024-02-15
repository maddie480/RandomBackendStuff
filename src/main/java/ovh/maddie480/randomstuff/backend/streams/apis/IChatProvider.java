package ovh.maddie480.randomstuff.backend.streams.apis;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Interface that all chat providers (Twitch and YouTube at the moment) should implement to support LNJ Bot.
 *
 * @param <MessageIDType> The type of the IDs used by messages
 */
public interface IChatProvider<MessageIDType> {
    void connect(Consumer<ChatMessage<MessageIDType>> messageListener) throws IOException;

    void disconnect();

    void sendMessage(String contents);

    void respondTo(ChatMessage<MessageIDType> message, String response);
}
