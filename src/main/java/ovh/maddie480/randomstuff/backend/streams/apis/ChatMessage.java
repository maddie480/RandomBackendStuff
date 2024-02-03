package ovh.maddie480.randomstuff.backend.streams.apis;

import java.util.List;

/**
 * Represents a chat message that was posted on a live stream.
 */
public record ChatMessage<IDType>(String messageSenderId, String messageSenderName, IDType messageId,
                                  String messageContents, boolean isAdmin, List<String> badgeUrls,
                                  List<Emote> emotesInMessage, IChatProvider<IDType> provider) {

    public void respond(String response) {
        provider.respondTo(this, response);
    }
}
