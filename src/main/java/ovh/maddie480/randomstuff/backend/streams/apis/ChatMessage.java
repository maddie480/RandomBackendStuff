package ovh.maddie480.randomstuff.backend.streams.apis;

public record ChatMessage<IDType>(String messageSenderId, String messageSenderName, IDType messageId,
                                  String messageContents, boolean isAdmin,
                                  AbstractChatProvider<IDType> provider) {

    public void respond(String response) {
        provider.respondTo(this, response);
    }

    public void makeClip() {
        provider.makeClip(this);
    }
}
