package com.max480.randomstuff.backend.discord.questcommunitybot.reminders;

import com.max480.randomstuff.backend.SecretConstants;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.max480.quest.modmanagerbot.imported.ReminderEngine.RappelV3;

public class ReminderEngine {
    private static final Logger logger = LoggerFactory.getLogger(ReminderEngine.class);

    private final List<RappelV3> tousLesRappelsV3;

    public void run(Guild guild) throws IOException {
        new Thread(() -> {
            while (true) {
                try {
                    checkForReminders(guild.getJDA());
                } catch (Exception e) {
                    logger.error("Uncaught exception during reminders refresh", e);
                }

                try {
                    Thread.sleep(60000 - (ZonedDateTime.now().getSecond() * 1000
                            + ZonedDateTime.now().getNano() / 1_000_000) + 50);
                } catch (InterruptedException e) {
                    logger.error("Sleep interrupted", e);
                }
            }
        }).start();
    }

    public ReminderEngine(Guild guild) throws IOException {
        try (ObjectInputStream objectInput = new ObjectInputStream(new FileInputStream("rappels.ser"))) {
            tousLesRappelsV3 = (List<RappelV3>) objectInput.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(e);
        }

        for (RappelV3 rappel : new ArrayList<>(tousLesRappelsV3)) {
            if (guild.getMemberById(rappel.userId) == null) {
                logger.warn("On supprime le rappel de {} parce qu'il n'existe plus", rappel.userId);
                tousLesRappelsV3.remove(rappel);
            }
        }
        saveRappels();
    }

    private void checkForReminders(JDA jda) throws IOException {
        for (int i = 0; i < tousLesRappelsV3.size(); i++) {
            RappelV3 rappel = tousLesRappelsV3.get(i);

            if (rappel.nextOccurence.isBefore(ZonedDateTime.now())) {
                logger.info("Le rappel {} est passé", rappel);

                User user = jda.getUserById(rappel.userId);
                if (user == null) {
                    logger.warn("L'utilisateur n'existe plus, je supprime le rappel");
                    tousLesRappelsV3.remove(i);
                    i--;
                    saveRappels();
                } else {
                    if (rappel.interval != null) {
                        while (rappel.nextOccurence.isBefore(ZonedDateTime.now())) {
                            rappel.nextOccurence = rappel.nextOccurence.plus(rappel.interval);
                        }
                    } else {
                        tousLesRappelsV3.remove(i);
                        i--;
                    }

                    saveRappels();

                    user.openPrivateChannel().queue(channel -> {
                        String message = "Tu m'avais demandé de te rappeler de \"" + rappel.message + "\". C'est fait.";

                        if (rappel.interval != null) {
                            message += "\n(Je te le rappellerai à nouveau le " + new Date(rappel.nextOccurence.toInstant().toEpochMilli()).toLocaleString()
                                    + ", comme convenu.)";
                        }

                        final String finalMessage = message;
                        channel.sendMessage(finalMessage).queue(
                                success -> {
                                },
                                failure -> jda.getTextChannelById(SecretConstants.LEVELING_NOTIFICATION_CHANNEL)
                                        .sendMessage("<@" + user.getIdLong() + "> " + finalMessage)
                                        .queue());
                    });
                }
            }
        }
    }

    boolean isReminderValid(String parameter) {
        try {
            RappelV3 result = parseRappel("rappelle-moi " + parameter);
            return result != null;
        } catch (Exception e) {
            logger.error("Le parsing du rappel a mal tourné ", e);
            return false;
        }
    }

    void addReminder(MessageChannel incomingChannel, User author, String parameter) throws IOException {
        RappelV3 parsedReminder = parseRappel("rappelle-moi " + parameter);

        if (parsedReminder.interval != null && parsedReminder.interval.minusMillis(3_600_000).isNegative()) {
            incomingChannel.sendMessage("Désolé, les rappels récurrents sont autorisés toutes les heures au maximum !").queue();
            return;
        }

        parsedReminder.nextOccurence = parsedReminder.nextOccurence.truncatedTo(ChronoUnit.MINUTES);
        if (parsedReminder.userId != null) {
            incomingChannel.sendMessage("Bien reçu ! Je vais rappeler à <@" + parsedReminder.userId + "> de " + parsedReminder + ".").queue();
        } else {
            incomingChannel.sendMessage("Bien reçu ! Je vais te rappeler de " + parsedReminder + ".").queue();
            parsedReminder.userId = author.getIdLong();
        }
        tousLesRappelsV3.add(parsedReminder);
        saveRappels();
    }

    void listReminders(MessageChannel incomingChannel, User author) {
        if (tousLesRappelsV3.isEmpty()) {
            incomingChannel.sendMessage("Tu n'as pas de rappel !").queue();
        } else {
            StringBuilder reponse = new StringBuilder("Voici la liste de tes rappels :");
            for (RappelV3 rapp : tousLesRappelsV3) {
                if (rapp.userId == author.getIdLong())
                    reponse.append("\n- ").append(rapp);
            }
            if (reponse.toString().equals("Voici la liste de tes rappels :")) {
                incomingChannel.sendMessage("Tu n'as pas de rappel !").queue();
            } else {
                incomingChannel.sendMessage(reponse.toString()).queue();
            }
        }
    }

    void removeReminder(MessageChannel incomingChannel, User author, String reminder) throws IOException {
        reminder = inversionDesPronoms(reminder);
        boolean trouve = false;
        for (int i = 0; i < tousLesRappelsV3.size(); i++) {
            if (reminder.equals(tousLesRappelsV3.get(i).message)
                    && tousLesRappelsV3.get(i).userId == author.getIdLong()) {
                tousLesRappelsV3.remove(i);
                trouve = true;
                i--;
            }
        }

        if (trouve) {
            incomingChannel.sendMessage("OK ! Il n'y a plus de rappel \"" + reminder + "\".").queue();
            saveRappels();
        } else {
            incomingChannel.sendMessage("Y a pas de rappel qui s'appelle \"" + reminder + "\". Qu'est-ce que tu racontes ?").queue();
        }
    }

    private void saveRappels() throws IOException {
        logger.debug("Saving reminders");
        try (ObjectOutputStream objects = new ObjectOutputStream(new FileOutputStream("rappels.ser"))) {
            objects.writeObject(tousLesRappelsV3);
        }
    }

    private static RappelV3 parseRappel(String rappel) {
        Long reflectedUserId = null;

        final String reflexiveRegex = "rappelle à <@!?([0-9]+)> .*";
        Pattern reflexiveRegexPattern = Pattern.compile(reflexiveRegex, Pattern.CASE_INSENSITIVE);
        Matcher reflexiveRegexMatcher = reflexiveRegexPattern.matcher(rappel);
        if (reflexiveRegexMatcher.matches()) {
            reflectedUserId = Long.parseLong(reflexiveRegexMatcher.group(1));
            rappel = "rappelle-moi" + rappel.substring(rappel.indexOf(">") + 1);
        }

        final String onceFromNow = "rappelle-moi d(?:e )?'?(.*) dans ([0-9]+) ((?:semaines?)?(?:jours?)?(?:heures?)?(?:minutes?)?)";
        final String timestamp = "((?:lundi)?(?:mardi)?(?:mercredi)?(?:jeudi)?(?:vendredi)?(?:samedi)?(?:dimanche)?)((?:demain)?(?:après-demain)?)(?:le (\\d{1,2})(?:er)? ?((?:janvier)?(?:février)?(?:mars)?(?:avril)?(?:mai)?(?:juin)?(?:juillet)?(?:août)?(?:septembre)?(?:octobre)?(?:novembre)?(?:décembre)?)?)? ?à? ?(\\d{1,2})[:h]?(?: heures?)? ?(\\d{1,2})?";
        final String recurring = "rappelle-moi d(?:e )?'?(.*) tou(?:te)?s les ([0-9]+)? ?((?:semaines?)?(?:jours?)?(?:heures?)?(?:minutes?)?)(?: à partir (.*))?";

        Pattern onceFromNowPattern = Pattern.compile(onceFromNow, Pattern.CASE_INSENSITIVE);
        Pattern timestampPattern = Pattern.compile(timestamp, Pattern.CASE_INSENSITIVE);
        Pattern recurringPattern = Pattern.compile(recurring, Pattern.CASE_INSENSITIVE);

        ZonedDateTime nextOccurence = null;
        Duration interval = null;
        String message = null;

        Matcher onceFromNowMatcher = onceFromNowPattern.matcher(rappel);
        if (onceFromNowMatcher.matches()) {
            message = onceFromNowMatcher.group(1);
            int fromNow = Integer.parseInt(onceFromNowMatcher.group(2));
            String intervalType = onceFromNowMatcher.group(3).toLowerCase();
            if (intervalType.startsWith("jour")) {
                nextOccurence = ZonedDateTime.now().plusDays(fromNow);
            } else if (intervalType.startsWith("semaine")) {
                nextOccurence = ZonedDateTime.now().plusWeeks(fromNow);
            } else if (intervalType.startsWith("heure")) {
                nextOccurence = ZonedDateTime.now().plusHours(fromNow);
            } else if (intervalType.startsWith("minute")) {
                nextOccurence = ZonedDateTime.now().plusMinutes(fromNow);
            }
        } else {
            Matcher recurringMatcher = recurringPattern.matcher(rappel);
            if (recurringMatcher.matches()) {
                message = recurringMatcher.group(1);

                int howMany = recurringMatcher.group(2) == null ? 1 : Integer.parseInt(recurringMatcher.group(2));
                String intervalType = recurringMatcher.group(3).toLowerCase();
                if (intervalType.startsWith("jour")) {
                    interval = Duration.ofDays(howMany);
                } else if (intervalType.startsWith("semaine")) {
                    interval = Duration.ofDays(howMany * 7L);
                } else if (intervalType.startsWith("heure")) {
                    interval = Duration.ofHours(howMany);
                } else if (intervalType.startsWith("minute")) {
                    interval = Duration.ofMinutes(howMany);
                }

                String maybeTimestamp = recurringMatcher.group(4);
                if (maybeTimestamp == null) {
                    nextOccurence = ZonedDateTime.now().plus(interval);
                } else {
                    maybeTimestamp = maybeTimestamp.toLowerCase();
                    if (maybeTimestamp.startsWith("de ")) {
                        maybeTimestamp = maybeTimestamp.substring(3);
                    } else if (maybeTimestamp.startsWith("du ")) {
                        maybeTimestamp = "le " + maybeTimestamp.substring(3);
                    }

                    nextOccurence = matchesToTime(timestampPattern.matcher(maybeTimestamp));
                }
            } else if (rappel.toLowerCase().startsWith("rappelle-moi d")) {
                rappel = rappel.substring(rappel.toLowerCase().startsWith("rappelle-moi de ") ? "rappelle-moi de ".length() : "rappelle-moi d'".length());
                // essayer en boucle de matcher la fin
                String[] words = rappel.split(" ");
                String timeCandidate = null;
                String lastMatchedTimeCandidate = null;
                for (int i = words.length - 1; i >= 0; i--) {
                    timeCandidate = words[i] + (timeCandidate == null ? "" : " " + timeCandidate);
                    if (timestampPattern.matcher(timeCandidate).matches()) {
                        lastMatchedTimeCandidate = timeCandidate;
                    }
                }

                if (lastMatchedTimeCandidate != null) {
                    nextOccurence = matchesToTime(timestampPattern.matcher(lastMatchedTimeCandidate));
                    message = rappel.substring(0, rappel.length() - lastMatchedTimeCandidate.length()).trim();
                }
            }
        }

        if (message == null || nextOccurence == null) {
            logger.debug("J'ai rien compris.");
            return null;
        } else {
            message = message.trim();
            logger.debug("J'ai compris message = " + message + ", intervalle de récurrence = " + interval + ", prochaine occurence = " + nextOccurence + ", utilisateur = " + reflectedUserId);

            message = inversionDesPronoms(message);
            logger.debug("Message après inversion des pronoms : {}.", message);

            RappelV3 result = new RappelV3();
            result.nextOccurence = nextOccurence;
            result.message = message;
            result.interval = interval;
            result.userId = reflectedUserId;
            return result;
        }
    }

    private static ZonedDateTime matchesToTime(Matcher matches) {
        if (matches.matches()) {
            String dayOfWeek = matches.group(1);
            String dayRelative = matches.group(2);
            String dayFixed = matches.group(3);
            String monthFixed = matches.group(4);
            String hour = matches.group(5);
            String minute = matches.group(6);

            ZonedDateTime result = ZonedDateTime.now().withSecond(0).withNano(0);
            if (minute != null) {
                if (minute.startsWith("0")) minute = minute.substring(1);
                result = result.withMinute(Integer.parseInt(minute));
            } else {
                result = result.withMinute(0);
            }
            if (hour.startsWith("0")) hour = hour.substring(1);
            result = result.withHour(Integer.parseInt(hour));

            if (dayOfWeek != null && !dayOfWeek.isEmpty()) {
                dayOfWeek = dayOfWeek.toLowerCase();
                DayOfWeek targetDayOfWeek = null;
                if (dayOfWeek.equals("lundi")) targetDayOfWeek = DayOfWeek.MONDAY;
                if (dayOfWeek.equals("mardi")) targetDayOfWeek = DayOfWeek.TUESDAY;
                if (dayOfWeek.equals("mercredi")) targetDayOfWeek = DayOfWeek.WEDNESDAY;
                if (dayOfWeek.equals("jeudi")) targetDayOfWeek = DayOfWeek.THURSDAY;
                if (dayOfWeek.equals("vendredi")) targetDayOfWeek = DayOfWeek.FRIDAY;
                if (dayOfWeek.equals("samedi")) targetDayOfWeek = DayOfWeek.SATURDAY;
                if (dayOfWeek.equals("dimanche")) targetDayOfWeek = DayOfWeek.SUNDAY;

                while (result.isBefore(ZonedDateTime.now()) || result.getDayOfWeek() != targetDayOfWeek) {
                    result = result.plusDays(1);
                }
            } else if (dayRelative != null && !dayRelative.isEmpty()) {
                dayRelative = dayRelative.toLowerCase();
                if (dayRelative.equals("demain")) result = result.plusDays(1);
                if (dayRelative.equals("après-demain")) result = result.plusDays(2);
            } else if (dayFixed != null && !dayFixed.isEmpty()) {
                result = result.withDayOfMonth(Integer.parseInt(dayFixed));
                if ((monthFixed == null || monthFixed.isEmpty())) {
                    if (result.isBefore(ZonedDateTime.now())) {
                        result = result.plusMonths(1);
                    }
                } else {
                    monthFixed = monthFixed.toLowerCase();
                    int monthNumber = 0;
                    if (monthFixed.equals("janvier")) monthNumber = 1;
                    if (monthFixed.equals("février")) monthNumber = 2;
                    if (monthFixed.equals("mars")) monthNumber = 3;
                    if (monthFixed.equals("avril")) monthNumber = 4;
                    if (monthFixed.equals("mai")) monthNumber = 5;
                    if (monthFixed.equals("juin")) monthNumber = 6;
                    if (monthFixed.equals("juillet")) monthNumber = 7;
                    if (monthFixed.equals("août")) monthNumber = 8;
                    if (monthFixed.equals("septembre")) monthNumber = 9;
                    if (monthFixed.equals("octobre")) monthNumber = 10;
                    if (monthFixed.equals("novembre")) monthNumber = 11;
                    if (monthFixed.equals("décembre")) monthNumber = 12;

                    result = result.withMonth(monthNumber);
                    if (result.isBefore(ZonedDateTime.now())) {
                        result = result.plusYears(1);
                    }
                }
            } else {
                if (result.isBefore(ZonedDateTime.now())) {
                    result = result.plusDays(1);
                }
            }
            return result;
        }
        return null;
    }

    private static String inversionDesPronoms(String phrase) {
        phrase = inversionDunPronom(phrase, "moi", "toi");
        phrase = inversionDunPronom(phrase, "me", "te");
        phrase = inversionDunPronom(phrase, "mon", "ton");
        phrase = inversionDunPronom(phrase, "ma", "ta");
        phrase = inversionDunPronom(phrase, "mes", "tes");
        phrase = inversionDunPronom(phrase, "mien", "tien");
        phrase = inversionDunPronom(phrase, "m'", "t'", true);
        phrase = inversionDunPronom(phrase, "je", "tu");

        return phrase;
    }

    private static String inversionDunPronom(String phrase, String moi, String toi) {
        return inversionDunPronom(phrase, moi, toi, false);
    }

    private static String inversionDunPronom(String phrase, String moi, String toi, boolean wordAcceptedAfter) {
        String temp = "¤" + toi.substring(1);

        for (Map.Entry<String, String> cases : getAllPossibleCases(moi, toi).entrySet()) {
            phrase = phrase
                    .replaceAll(toWholeWordRegex(cases.getKey(), wordAcceptedAfter), temp)
                    .replaceAll(toWholeWordRegex(cases.getValue(), wordAcceptedAfter), cases.getKey())
                    .replaceAll(toWholeWordRegex(temp, wordAcceptedAfter), cases.getValue());
        }

        return phrase;
    }

    private static Map<String, String> getAllPossibleCases(String s1, String s2) {
        assert s1.length() == s2.length();

        boolean[] bits = new boolean[s1.length()];

        Map<String, String> all = new HashMap<>();
        for (int i = 0; i < Math.pow(2, s1.length()); i++) {
            String capS1 = s1;
            String capS2 = s2;

            boolean capitalizesSpecialChar = false;
            for (int pos = 0; pos < bits.length; pos++) {
                if (bits[pos]) {
                    if (!Character.isLetter(capS1.charAt(pos)) || !Character.isLetter(capS2.charAt(pos))) {
                        capitalizesSpecialChar = true;
                        break;
                    }

                    capS1 = capS1.substring(0, pos) + capS1.substring(pos, pos + 1).toUpperCase() + capS1.substring(pos + 1);
                    capS2 = capS2.substring(0, pos) + capS2.substring(pos, pos + 1).toUpperCase() + capS2.substring(pos + 1);
                }
            }
            if (!capitalizesSpecialChar) {
                all.put(capS1, capS2);
            }

            for (int l = bits.length - 1; l >= 0; l--) {
                if (!bits[l]) {
                    bits[l] = true;
                    break;
                } else {
                    bits[l] = false;
                    // propagation
                }
            }
        }

        return all;
    }

    private static String toWholeWordRegex(String st, boolean wordAcceptedAfter) {
        // pas de lettre avant
        return "(?<![a-zA-ZÀ-ſ])" + st + (wordAcceptedAfter ? "" : "(?![a-zA-ZÀ-ſ])");
    }
}
