package ovh.maddie480.randomstuff.backend.discord.questcommunitybot;

import ovh.maddie480.randomstuff.backend.SecretConstants;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class Utils {
    private static final Logger log = LoggerFactory.getLogger(Utils.class);

    /**
     * Finds a member from a string (either a mention, or part of their name).
     * If the user was not found, sends a message in the given channel and returns null.
     */
    public static Member findMemberFromString(MessageChannel channel, String string) {
        Matcher mentionRegex = Pattern.compile("<@!?([0-9]+)>").matcher(string);
        String id = string;
        if (mentionRegex.matches()) {
            id = mentionRegex.group(1);
        }

        Guild guild = getQuestGuild(channel.getJDA());

        try {
            Member member = guild.getMemberById(id);
            if (member != null) return member;
        } catch (NumberFormatException ignored) {
        }

        List<Member> approxMatches = guild.getMembers().stream()
                .filter(member -> member.getUser().getName().toLowerCase().contains(string.toLowerCase())
                        || member.getEffectiveName().toLowerCase().contains(string.toLowerCase()))
                .toList();

        List<Member> exactMatches = guild.getMembers().stream()
                .filter(member -> member.getUser().getName().equalsIgnoreCase(string.toLowerCase())
                        || member.getEffectiveName().equalsIgnoreCase(string.toLowerCase()))
                .toList();

        log.debug("Matches for {} : exact = {}, approx = {}", string, exactMatches, approxMatches);

        if (exactMatches.size() == 1) {
            return exactMatches.get(0);
        } else if (exactMatches.size() > 1) {
            channel.sendMessage("\"" + string + "\" n'est pas assez précis et correspond à plusieurs personnes. Essaie encore !").queue();
        } else if (approxMatches.size() == 1) {
            return approxMatches.get(0);
        } else if (approxMatches.size() > 1) {
            channel.sendMessage("\"" + string + "\" n'est pas assez précis et correspond à plusieurs personnes. Essaie encore !").queue();
        } else {
            channel.sendMessage("Je n'ai pas trouvé d'utilisateur qui s'appelle \"" + string + "\". Essaie encore !").queue();
        }

        return null;
    }

    /**
     * Returns an Emoji object from a Unicode hex string.
     */
    public static Emoji getEmojiFromUnicodeHex(String hex) {
        try {
            return Emoji.fromUnicode(new String(Hex.decodeHex(hex.toCharArray()), StandardCharsets.UTF_8));
        } catch (DecoderException e) {
            log.error("Erreur de décodage de l'emoji {}", hex, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns a Unicode hex string from an emoji.
     */
    public static String getUnicodeHexFromEmoji(String arg) {
        return String.format("%x", new BigInteger(1, arg.getBytes(UTF_8)));
    }

    /**
     * Returns the Quest community guild, which should not be null (otherwise that means Quest Community Bot
     * got fired from the very guild it was originally made for).
     */
    @NotNull
    public static Guild getQuestGuild(JDA jda) {
        return jda.getGuildById(SecretConstants.QUEST_COMMUNITY_SERVER_ID);
    }
}
