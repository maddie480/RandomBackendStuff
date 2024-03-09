package ovh.maddie480.randomstuff.backend.discord.modstructureverifier;

/**
 * The association of a message with its embed.
 */
public record MessageEmbedPair(long guildId, long channelId, long messageId, long embedId, long expiresAt) {
    public static MessageEmbedPair fromCSV(String csvLine) {
        String[] split = csvLine.split(";");
        return new MessageEmbedPair(Long.parseLong(split[0]), Long.parseLong(split[1]),
                Long.parseLong(split[2]), Long.parseLong(split[3]), Long.parseLong(split[4]));
    }

    public String toCSV() {
        return guildId + ";" + channelId + ";" + messageId + ";" + embedId + ";" + expiresAt;
    }

    public MessageEmbedPair setExpiresAt(long expiresAt) {
        return new MessageEmbedPair(guildId, channelId, messageId, embedId, expiresAt);
    }
}
