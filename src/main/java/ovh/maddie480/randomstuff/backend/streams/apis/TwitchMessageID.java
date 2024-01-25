package ovh.maddie480.randomstuff.backend.streams.apis;


import java.util.Optional;

/**
 * A simple object holding the 2 fields needed to respond to a message on Twitch.
 */
public record TwitchMessageID(Optional<String> messageId, String nonce) {
}