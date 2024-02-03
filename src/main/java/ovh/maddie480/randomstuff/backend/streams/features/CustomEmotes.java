package ovh.maddie480.randomstuff.backend.streams.features;

import ovh.maddie480.randomstuff.backend.streams.apis.ChatMessage;
import ovh.maddie480.randomstuff.backend.streams.apis.Emote;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Twitch won't let us, so here we are!
 */
public class CustomEmotes {
    private final Map<String, String> allEmotes;

    public CustomEmotes() {
        allEmotes = Arrays.stream("""
                1000tipla;854303924365688832
                bigrigs;852891851002609716
                taxi2;854315889351327754
                verajones;852891183494856744
                assassinscreedunity;854308657097080853
                yourewinner;589132274138873856
                boyard1;852956563005374523
                boyard2;852956805007933440
                burgerking;852894749413212190
                cheetahmen;854307636581629992
                chirac;852892791704649790
                chirac2;852886742494216242
                danyboon;854310353893326858
                davilex;589135269219926016
                davilex1;854312377101320192
                davilex2;854312419173335041
                passepartout;852896613592596520
                hatoful;852957087607816232
                homer;852974507744690217
                lesvisiteurs;854317578763501588
                lesvisiteurs2;854317610518446131
                ljn;649739143844462593
                lnj;649738827488952320
                multipla;854304375941890068
                navet;587332261817483283
                navet2;811236063636619294
                phoenixgames;854311078191038464
                pizzadude;852893957053743157
                psychokiller;852895322057605120
                samantha;852974896216670208
                slevy;854306615951622144
                tanner;854309585753735168""".split("\n")
        ).collect(Collectors.toMap(
                line -> ":" + line.split(";")[0] + ":",
                line -> "https://cdn.discordapp.com/emojis/" + line.split(";")[1].trim() + ".webp?size=24&quality=lossless"
        ));
    }

    public <T> ChatMessage<T> fillWithCustomEmotes(ChatMessage<T> message) {
        List<Emote> resolvedEmotes = findEmotes(message.messageContents().toLowerCase(Locale.ROOT), allEmotes);
        if (resolvedEmotes.isEmpty()) return message;

        List<Emote> allEmotes = Stream.concat(
                        message.emotesInMessage().stream(),
                        resolvedEmotes.stream().filter(emote1 -> message.emotesInMessage().stream().noneMatch(emote2 ->
                                emote1.startIndex() < emote2.endIndex() && emote1.endIndex() > emote2.startIndex()
                        ))
                )
                .toList();

        return new ChatMessage<>(
                message.messageSenderId(), message.messageSenderName(), message.messageId(),
                message.messageContents(), message.isAdmin(), message.badgeUrls(),
                allEmotes, message.provider()
        );
    }

    /**
     * Finds all emotes in the given message.
     * This logic works for both YouTube and custom emotes.
     */
    public static List<Emote> findEmotes(String message, Map<String, String> allEmotes) {
        List<Emote> result = new LinkedList<>();

        for (Map.Entry<String, String> emote : allEmotes.entrySet()) {
            String truncatedMessage = message;
            int index = 0;

            while (truncatedMessage.contains(emote.getKey())) {
                result.add(new Emote(
                        emote.getValue(),
                        truncatedMessage.indexOf(emote.getKey()) + index,
                        truncatedMessage.indexOf(emote.getKey()) + index + emote.getKey().length()
                ));

                int cutoffAt = truncatedMessage.indexOf(emote.getKey()) + emote.getKey().length();
                index += cutoffAt;
                truncatedMessage = truncatedMessage.substring(cutoffAt);
            }
        }

        return result;
    }
}
