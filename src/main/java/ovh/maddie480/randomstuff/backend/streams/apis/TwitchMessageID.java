package ovh.maddie480.randomstuff.backend.streams.apis;


import java.util.Optional;

public record TwitchMessageID(Optional<String> messageId, String nonce) {
}