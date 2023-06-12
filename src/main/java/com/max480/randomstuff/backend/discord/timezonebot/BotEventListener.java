package com.max480.randomstuff.backend.discord.timezonebot;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * This class handles everything that can happen (the bot joins/leaves a server, someone runs a slash command, etc).
 */
public class BotEventListener extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(BotEventListener.class);

    public static BotEventListener INSTANCE;

    // offset timezone database
    private final Map<String, String> TIMEZONE_MAP;
    private final Map<String, String> TIMEZONE_FULL_NAMES;
    private final Map<String, List<String>> TIMEZONE_CONFLICTS;

    private static final AtomicLong lastTimezoneDBRequest = new AtomicLong(0);

    public BotEventListener(Map<String, String> timezoneMap, Map<String, String> timezoneFullNames, Map<String, List<String>> timezoneConflicts) {
        TIMEZONE_MAP = timezoneMap;
        TIMEZONE_FULL_NAMES = timezoneFullNames;
        TIMEZONE_CONFLICTS = timezoneConflicts;

        INSTANCE = this;
    }

    @Override
    public void onGuildJoin(@NotNull GuildJoinEvent event) {
        event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                .sendMessage("I just joined a new server! I am now in **" + event.getJDA().getGuilds().size() + "** servers.").queue();

        logger.info("Just joined guild {}!", event.getGuild());
    }

    @Override
    public void onGuildLeave(@NotNull GuildLeaveEvent event) {
        event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                .sendMessage("I was just kicked from a server. I am now in **" + event.getJDA().getGuilds().size() + "** servers.").queue();

        // if it was a server with time, running the cleanup process will make us delete its ID.
        logger.info("Cleaning servers with time list after leaving guild {}", event.getGuild());
        TimezoneBot.removeNonExistingServersFromServersWithTime();

        // refreshing roles allows us to clean up any user info we had on that server.
        logger.info("Force-refreshing roles after leaving guild {}", event.getGuild());
        TimezoneRoleUpdater.forceUpdate();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        DiscordLocale locale = event.getUserLocale();

        if (!event.isFromGuild()) {
            // wtf??? slash commands are disabled in DMs
            respondPrivately(event, localizeMessage(locale,
                    "This bot is not usable in DMs!",
                    "Ce bot ne peut pas être utilisé en MP !"));
        } else {
            Member member = event.getMember();

            logger.info("New command: /{} by member {}, options={}", event.getName(), member, event.getOptions());

            switch (event.getName()) {
                case "list-timezones" -> {
                    OptionMapping visibility = event.getOption("visibility");
                    OptionMapping names = event.getOption("names");
                    boolean isPublic = visibility != null && "public".equals(visibility.getAsString());
                    listTimezones(event,
                            names == null ? "discord_tags" : names.getAsString(),
                            false,
                            isPublic,
                            isPublic ? event.getGuildLocale() : event.getUserLocale());
                }
                case "timezone-dropdown" -> generateTimezoneDropdown(event);
                case "detect-timezone" -> sendDetectTimezoneLink(event, locale);
                case "timezone" -> defineUserTimezone(event, member, event.getOption("tz_name").getAsString(), locale);
                case "remove-timezone" -> removeUserTimezone(event, member, locale);
                case "toggle-times" -> toggleTimesInTimezoneRoles(event, member, locale);
                case "discord-timestamp" ->
                        giveDiscordTimestamp(event, member, event.getOption("date_time").getAsString(), locale);
                case "time-for" -> giveTimeForOtherUser(event, member, event.getOption("member").getAsLong(), locale);
                case "world-clock" ->
                        giveTimeForOtherPlace(event, event.getMember(), event.getOption("place").getAsString(), locale);
            }
        }
    }


    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        if ("discord-timestamp".equals(event.getComponent().getId())) {
            logger.info("New interaction with discord-timestamp selection menu from member {}, picked {}", event.getMember(), event.getValues().get(0));

            // the user picked a timestamp format! we should edit the message to that timestamp so that they can copy it easier.
            // we also want the menu to stay the same, so that they can switch.
            event.editMessage(new MessageEditBuilder()
                    .setContent(event.getValues().get(0))
                    .setComponents(ActionRow.of(event.getSelectMenu().createCopy()
                            .setDefaultValues(event.getValues())
                            .build()))
                    .build()).queue();
        }

        if ("timezone-dropdown".equals(event.getComponent().getId())) {
            logger.info("New interaction with timezone-dropdown selection menu from member {}, picked {}", event.getMember(), event.getValues().get(0));

            // the user picked a timezone in the dropdown! we should act exactly like the /timezone command, actually.
            defineUserTimezone(event, event.getMember(), event.getValues().get(0), event.getUserLocale());
        }
    }

    @Override
    public void onButtonInteraction(@Nonnull ButtonInteractionEvent event) {
        if (event.getComponentId().startsWith("list-timezones-to-file")) {
            String nameFormat = event.getComponentId().substring("list-timezones-to-file".length());
            logger.info("New interaction with button from member {}, chose to get timezone list as text file with name format '{}'", event.getMember(), nameFormat);

            // list timezones again, but this time force it to go to a text file.
            listTimezones(event, nameFormat, true, false,
                    event.getMessage().isEphemeral() ? event.getUserLocale() : event.getGuildLocale());
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        String request = event.getFocusedOption().getValue();
        logger.info("New autocomplete request for timezone name: requested '{}'", request);
        List<Command.Choice> choices = suggestTimezones(request, event.getUserLocale());
        logger.info("Answered with suggestions: {}", choices);
        event.replyChoices(choices).queue();
    }

    @Override
    public void onUserContextInteraction(@NotNull UserContextInteractionEvent event) {
        logger.info("New user command on user {}: {}", event.getTargetMember(), event.getName());
        giveTimeForOtherUser(event, event.getMember(), event.getTargetMember().getIdLong(), event.getUserLocale());
    }

    public List<Command.Choice> suggestTimezones(String input, DiscordLocale locale) {
        // look up tz database timezones
        List<Command.Choice> matchingTzDatabaseTimezones = ZoneId.getAvailableZoneIds().stream()
                .filter(tz -> {
                    if (tz.toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                    if (!tz.contains("/")) {
                        return false;
                    }
                    // suggest "Europe/Paris" if the user started typing "Paris"
                    return tz.substring(tz.lastIndexOf("/") + 1).toLowerCase(Locale.ROOT)
                            .startsWith(input.toLowerCase(Locale.ROOT));
                })
                .map(tz -> mapToChoice(tz, tz, locale))
                .toList();

        if (input.isEmpty()) {
            // we want to push for tz database timezones, so list them by default!
            return matchingTzDatabaseTimezones.stream()
                    .sorted(Comparator.comparing(tz -> tz.getAsString().toLowerCase(Locale.ROOT)))
                    .limit(25)
                    .collect(Collectors.toList());
        }

        // look up timezone names
        List<Command.Choice> matchingTimezoneNames = TIMEZONE_MAP.entrySet().stream()
                .filter(tz -> tz.getKey().toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT)))
                .map(tz -> {
                    String tzName = tz.getKey();
                    if (TIMEZONE_FULL_NAMES.containsKey(tzName)) {
                        tzName = TIMEZONE_FULL_NAMES.get(tzName) + " (" + tzName + ")";
                    }
                    return mapToChoice(tzName, tz.getValue(), locale);
                })
                .toList();

        // look up conflicting timezone names, showing all possibilities
        List<Command.Choice> matchingTimezoneConflictNames = TIMEZONE_CONFLICTS.entrySet().stream()
                .filter(tz -> tz.getKey().toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT)))
                .flatMap(tz -> tz.getValue().stream()
                        .map(tzValue -> mapToChoice(tzValue + " (" + tz.getKey() + ")", TIMEZONE_MAP.get(tzValue), locale)))
                .toList();

        List<Command.Choice> allChoices = new ArrayList<>(matchingTzDatabaseTimezones);
        allChoices.addAll(matchingTimezoneNames);
        allChoices.addAll(matchingTimezoneConflictNames);

        if (!allChoices.isEmpty()) {
            // send them sorting by alphabetical order and return as many as possible
            return allChoices.stream()
                    .sorted(Comparator.comparing(tz -> tz.getName().toLowerCase(Locale.ROOT)))
                    .limit(25)
                    .collect(Collectors.toList());
        } else {
            try {
                // if the timezone is valid, be sure to allow the user to use it!
                return Collections.singletonList(mapToChoice(input, input, locale));
            } catch (DateTimeException e) {
                // no match :shrug:
                return Collections.emptyList();
            }
        }
    }

    private Command.Choice mapToChoice(String tzName, String zoneId, DiscordLocale locale) {
        String localTime;
        if (locale == DiscordLocale.FRENCH) {
            localTime = ZonedDateTime.now(ZoneId.of(zoneId)).format(DateTimeFormatter.ofPattern("HH:mm"));
        } else {
            localTime = ZonedDateTime.now(ZoneId.of(zoneId)).format(DateTimeFormatter.ofPattern("h:mma")).toLowerCase(Locale.ROOT);
        }
        return new Command.Choice(tzName + " (" + localTime + ")", zoneId);
    }

    /**
     * Handles the /detect-timezone command: sends the link to the detect timezone page.
     */
    private static void sendDetectTimezoneLink(IReplyCallback event, DiscordLocale locale) {
        respondPrivately(event, localizeMessage(locale,
                "To figure out your timezone, visit <https://maddie480.ovh/discord-bots/timezone-bot/detect-timezone>.",
                "Pour déterminer ton fuseau horaire, consulte <https://maddie480.ovh/discord-bots/timezone-bot/detect-timezone>."));
    }

    /**
     * Handles the /timezone command: saves a new timezone for the given user.
     */
    private void defineUserTimezone(IReplyCallback event, Member member, String timezoneParam, DiscordLocale locale) {
        try {
            // if the user passed for example "EST", convert it to "UTC-5".
            String timezoneOffsetFromName = getIgnoreCase(TIMEZONE_MAP, timezoneParam);
            if (timezoneOffsetFromName != null) {
                timezoneParam = timezoneOffsetFromName;
            }

            // check that the timezone is valid by passing it to ZoneId.of.
            ZonedDateTime localNow = ZonedDateTime.now(ZoneId.of(timezoneParam));

            // remove old link if there is any.
            TimezoneBot.userTimezones.stream()
                    .filter(u -> u.serverId == member.getGuild().getIdLong() && u.userId == member.getIdLong())
                    .findFirst()
                    .map(u -> TimezoneBot.userTimezones.remove(u));

            // save the link, both in userTimezones and on disk.
            TimezoneBot.userTimezones.add(new TimezoneBot.UserTimezone(member.getGuild().getIdLong(), member.getIdLong(), timezoneParam));
            logger.info("User {} now has timezone {}", member.getIdLong(), timezoneParam);
            TimezoneBot.memberCache.remove(TimezoneBot.getMemberWithCache(member.getGuild(), member.getIdLong()));

            DateTimeFormatter format = DateTimeFormatter.ofPattern("MMM dd, HH:mm", Locale.ENGLISH);
            DateTimeFormatter formatFr = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.FRENCH);
            TimezoneBot.saveUsersTimezonesToFile(event, localizeMessage(locale,
                    ":white_check_mark: Your timezone was saved as **" + timezoneParam + "**.\n" +
                            "The current time in this timezone is **" + localNow.format(format) + "**. " +
                            "If this does not match your local time, type `/detect-timezone` to find the right one.\n\n" +
                            getRoleUpdateMessage(member.getGuild(), member,
                                    "Your role will be assigned within 15 minutes once this is done.", locale),
                    ":white_check_mark: Ton fuseau horaire a été enregistré : **" + timezoneParam + "**.\n" +
                            "L'heure qu'il est dans ce fuseau horaire est **" + localNow.format(formatFr) + "**. " +
                            "Si cela ne correspond pas à l'heure qu'il est chez toi, tape `/detect-timezone` pour trouver le bon fuseau horaire.\n\n" +
                            getRoleUpdateMessage(member.getGuild(), member,
                                    "Ton rôle sera attribué dans les 15 minutes une fois que ce sera fait.", locale))
            );

            TimezoneRoleUpdater.forceUpdate();
        } catch (DateTimeException ex) {
            // ZoneId.of blew up so the timezone is probably invalid.
            logger.warn("Could not parse timezone " + timezoneParam, ex);

            List<String> conflictingTimezones = getIgnoreCase(TIMEZONE_CONFLICTS, timezoneParam);
            if (conflictingTimezones != null) {
                respondPrivately(event, localizeMessage(locale,
                        ":x: The timezone **" + timezoneParam + "** is ambiguous! It could mean one of those: _"
                                + String.join("_, _", conflictingTimezones) + "_.\n" +
                                "Repeat the command with the timezone full name!",
                        ":x: Le fuseau horaire **" + timezoneParam + "** est ambigu ! Il peut désigner _"
                                + String.join("_, _", conflictingTimezones) + "_.\n" +
                                "Relance la commande avec le nom complet du fuseau horaire !"));
            } else {
                respondPrivately(event, localizeMessage(locale,
                        ":x: The given timezone was not recognized.\n" +
                                "To figure out your timezone, visit <https://maddie480.ovh/discord-bots/timezone-bot/detect-timezone>.",
                        ":x: Le fuseau horaire que tu as donné n'a pas été reconnu.\n" +
                                "Pour déterminer ton fuseau horaire, consulte <https://maddie480.ovh/discord-bots/timezone-bot/detect-timezone>."));
            }
        }
    }

    /**
     * Handles the /remove-timezone command: takes off the timezone role and forgets about the user.
     */
    private static void removeUserTimezone(IReplyCallback event, Member member, DiscordLocale locale) {
        // find the user's timezone.
        TimezoneBot.UserTimezone userTimezone = TimezoneBot.userTimezones.stream()
                .filter(u -> u.serverId == member.getGuild().getIdLong() && u.userId == member.getIdLong())
                .findFirst().orElse(null);

        if (userTimezone != null) {
            String error = getRoleUpdateMessage(member.getGuild(), member, localizeMessage(locale,
                    "You will be able to remove your timezone role once this is done.",
                    "Tu pourras supprimer ton rôle de fuseau horaire une fois que ce sera fait."
            ), locale);

            if (!error.isEmpty()) {
                // since the command involves removing the roles **now**, we can't do it at all if there are permission issues!
                respondPrivately(event, error.replace(":warning:", ":x:"));
                return;
            }

            // remove all timezone roles from the user.
            Guild server = member.getGuild();
            for (Role userRole : member.getRoles()) {
                if (TimezoneBot.getTimezoneOffsetRolesForGuild(server).containsValue(userRole.getIdLong())) {
                    logger.info("Removing timezone role {} from {}", userRole, member);
                    TimezoneBot.memberCache.remove(TimezoneBot.getMemberWithCache(server, member.getIdLong()));
                    server.removeRoleFromMember(member, userRole).reason("User used /remove-timezone").complete();
                }
            }

            // forget the user timezone and write it to disk.
            TimezoneBot.userTimezones.remove(userTimezone);
            TimezoneBot.saveUsersTimezonesToFile(event, localizeMessage(locale,
                    ":white_check_mark: Your timezone role has been removed.",
                    ":white_check_mark: Ton rôle de fuseau horaire a été supprimé."));
        } else {
            // user asked for their timezone to be forgotten, but doesn't have a timezone to start with :thonk:
            respondPrivately(event, localizeMessage(locale,
                    ":x: You don't currently have a timezone role!",
                    ":x: Tu n'as pas de rôle de fuseau horaire !"));
        }
    }

    /**
     * Handles the /toggle-times command: saves the new setting.
     */
    private static void toggleTimesInTimezoneRoles(IReplyCallback event, Member member, DiscordLocale locale) {
        // add or remove the server in the list, depending on the previous status.
        long guildId = member.getGuild().getIdLong();
        boolean newValue = !TimezoneBot.serversWithTime.contains(guildId);
        if (newValue) {
            TimezoneBot.serversWithTime.add(guildId);
        } else {
            TimezoneBot.serversWithTime.remove(guildId);
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(TimezoneBot.SERVERS_WITH_TIME_FILE_NAME))) {
            for (Long server : TimezoneBot.serversWithTime) {
                writer.write(server + "\n");
            }

            respondPrivately(event, localizeMessage(locale,
                    ":white_check_mark: " + (newValue ?
                            "The timezone roles will now show the time it is in the timezone." :
                            "The timezone roles won't show the time it is in the timezone anymore.") + "\n" +
                            getRoleUpdateMessage(member.getGuild(), member,
                                    "The roles will be updated within 15 minutes once this is done.", locale),
                    ":white_check_mark: " + (newValue ?
                            "Les rôles de fuseau horaire montrent maintenant l'heure qu'il est dans le fuseau horaire correspondant." :
                            "Les rôles de fuseau horaire ne montrent plus l'heure qu'il est dans le fuseau horaire correspondant.") + "\n" +
                            getRoleUpdateMessage(member.getGuild(), member,
                                    "Les rôles seront mis à jour dans les 15 minutes une fois que ce sera fait.", locale)));

            TimezoneRoleUpdater.forceUpdate();
        } catch (IOException e) {
            // I/O error while saving to disk??
            logger.error("Error while writing file", e);
            respondPrivately(event, localizeMessage(locale,
                    ":x: A technical error occurred.",
                    ":x: Une erreur technique est survenue."));
        }
    }

    /**
     * Handles the /discord-timestamp command: gives the Discord timestamp for the given date.
     */
    private static void giveDiscordTimestamp(IReplyCallback event, Member member, String dateTimeParam, DiscordLocale locale) {
        // find the user's timezone.
        String timezoneName = TimezoneBot.userTimezones.stream()
                .filter(u -> u.serverId == member.getGuild().getIdLong() && u.userId == member.getIdLong())
                .findFirst().map(timezone -> timezone.timezoneName).orElse(null);

        // if the user has no timezone role, we want to use UTC instead!
        String timezoneToUse = timezoneName == null ? "UTC" : timezoneName;

        // take the given date time with the user's timezone (or UTC), then turn it into a timestamp.
        LocalDateTime parsedDateTime = tryParseSuccessively(Arrays.asList(
                // full date
                () -> LocalDateTime.parse(dateTimeParam, DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss")),
                () -> LocalDateTime.parse(dateTimeParam, DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm")).withSecond(0),

                // year omitted
                () -> LocalDateTime.parse(LocalDate.now(ZoneId.of(timezoneToUse)).getYear() + "-" + dateTimeParam,
                        DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss")),
                () -> LocalDateTime.parse(LocalDate.now(ZoneId.of(timezoneToUse)).getYear() + "-" + dateTimeParam,
                        DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm")).withSecond(0),

                // month omitted
                () -> LocalDateTime.parse(LocalDate.now(ZoneId.of(timezoneToUse)).getYear()
                                + "-" + LocalDate.now(ZoneId.of(timezoneToUse)).getMonthValue()
                                + "-" + dateTimeParam,
                        DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss")),
                () -> LocalDateTime.parse(LocalDate.now(ZoneId.of(timezoneToUse)).getYear()
                                + "-" + LocalDate.now(ZoneId.of(timezoneToUse)).getMonthValue()
                                + "-" + dateTimeParam,
                        DateTimeFormatter.ofPattern("yyyy-M-d H:mm")).withSecond(0),

                // date omitted
                () -> LocalTime.parse(dateTimeParam, DateTimeFormatter.ofPattern("H:mm:ss"))
                        .atDate(LocalDate.now(ZoneId.of(timezoneToUse))),
                () -> LocalTime.parse(dateTimeParam, DateTimeFormatter.ofPattern("H:mm")).withSecond(0)
                        .atDate(LocalDate.now(ZoneId.of(timezoneToUse)))
        ));

        Long timestamp = null;
        if (parsedDateTime == null) {
            respondPrivately(event, localizeMessage(locale,
                    """
                            :x: The date you gave could not be parsed!
                            Make sure you followed the format `YYYY-MM-dd hh:mm:ss`. For example: `2020-10-01 15:42:00`
                            You can omit part of the date (or omit it entirely if you want today), and the seconds if you don't need that.""",
                    """
                            :x: Je n'ai pas compris la date que tu as donnée !
                            Assure-toi que tu as suivi le format `YYYY-MM-dd hh:mm:ss`. Par exemple : `2020-10-01 15:42:00`
                            Tu peux enlever une partie de la date (ou l'enlever complètement pour obtenir le _timestamp_ d'aujourd'hui) et les secondes si tu n'en as pas besoin."""));
        } else {
            timestamp = parsedDateTime.atZone(ZoneId.of(timezoneToUse)).toEpochSecond();
        }

        if (timestamp != null) {
            StringBuilder b = new StringBuilder();
            if (timezoneName == null) {
                // warn the user that we used UTC.
                b.append(localizeMessage(locale,
                        ":warning: You did not grab a timezone role with `/timezone`, so **UTC** was used instead.\n\n",
                        ":warning: Tu n'as pas pris de rôle de fuseau horaire avec `/timezone`, donc le fuseau horaire **UTC** sera utilisé à la place.\n\n"));
            }

            // print `<t:timestamp:format>` => <t:timestamp:format> for all available formats.
            b.append(localizeMessage(locale,
                            "Copy-paste one of those tags in your message, and others will see **",
                            "Copie-colle l'un de ces tags dans ton message, et les autres verront **"))
                    .append(dateTimeParam)
                    .append(localizeMessage(locale,
                            "** in their timezone:\n",
                            "** dans leur fuseau horaire :\n"));
            for (char format : new char[]{'t', 'T', 'd', 'D', 'f', 'F', 'R'}) {
                b.append("`<t:").append(timestamp).append(':').append(format)
                        .append(">` :arrow_right: <t:").append(timestamp).append(':').append(format).append(">\n");
            }
            b.append(localizeMessage(locale,
                    "\n\nIf you are on mobile, pick a format for easier copy-pasting:",
                    "\n\nSi tu es sur mobile, choisis un format pour pouvoir le copier-coller plus facilement :"));

            // we want to show a selection menu for the user to pick a format.
            // the idea is that they can get a message with only the tag to copy in it and can copy it on mobile,
            // which is way more handy than selecting part of the full message on mobile.
            ZonedDateTime time = Instant.ofEpochSecond(timestamp).atZone(ZoneId.of(timezoneToUse));

            if (locale == DiscordLocale.FRENCH) {
                respondPrivately(event, new MessageCreateBuilder().setContent(b.toString().trim())
                        .setComponents(ActionRow.of(
                                StringSelectMenu.create("discord-timestamp")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)), "<t:" + timestamp + ":t>")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.FRENCH)), "<t:" + timestamp + ":T>")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRENCH)), "<t:" + timestamp + ":d>")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)), "<t:" + timestamp + ":D>")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("d MMMM yyyy H:mm", Locale.FRENCH)), "<t:" + timestamp + ":f>")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy H:mm", Locale.FRENCH)), "<t:" + timestamp + ":F>")
                                        .addOption("Différence par rapport à maintenant", "<t:" + timestamp + ":R>")
                                        .build()
                        ))
                        .build());
            } else {
                respondPrivately(event, new MessageCreateBuilder().setContent(b.toString().trim())
                        .setComponents(ActionRow.of(
                                StringSelectMenu.create("discord-timestamp")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH)), "<t:" + timestamp + ":t>")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH)), "<t:" + timestamp + ":T>")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH)), "<t:" + timestamp + ":d>")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)), "<t:" + timestamp + ":D>")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("MMMM d, yyyy h:mm a", Locale.ENGLISH)), "<t:" + timestamp + ":f>")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy h:mm a", Locale.ENGLISH)), "<t:" + timestamp + ":F>")
                                        .addOption("Relative to now", "<t:" + timestamp + ":R>")
                                        .build()
                        ))
                        .build());
            }
        }
    }

    /**
     * Handles the /time-for command: gives the time it is for another server member.
     */
    private static void giveTimeForOtherUser(IReplyCallback event, Member member, Long memberParam, DiscordLocale locale) {
        // find the target user's timezone.
        String timezoneName = TimezoneBot.userTimezones.stream()
                .filter(u -> u.serverId == member.getGuild().getIdLong() && u.userId == memberParam)
                .findFirst().map(timezone -> timezone.timezoneName).orElse(null);

        // find the calling user's timezone.
        String userTimezone = TimezoneBot.userTimezones.stream()
                .filter(u -> u.serverId == member.getGuild().getIdLong() && u.userId == member.getIdLong())
                .findFirst().map(timezone -> timezone.timezoneName).orElse(null);

        if (timezoneName == null) {
            // the user is not in the database.
            respondPrivately(event, localizeMessage(locale,
                    ":x: <@" + memberParam + "> does not have a timezone role.",
                    ":x: <@" + memberParam + "> n'a pas de rôle de fuseau horaire."));
        } else {
            // format the time and display it!
            ZonedDateTime nowForUser = ZonedDateTime.now(ZoneId.of(timezoneName));
            DateTimeFormatter format = DateTimeFormatter.ofPattern("MMM dd, HH:mm", Locale.ENGLISH);
            DateTimeFormatter formatFr = DateTimeFormatter.ofPattern("dd MMM, HH:mm", Locale.FRENCH);

            respondPrivately(event, localizeMessage(locale,
                    "The current time for <@" + memberParam + "> is **" + nowForUser.format(format) + "** " +
                            "(" + getOffsetAndDifference(timezoneName, userTimezone, locale) + ").",
                    "Pour <@" + memberParam + ">, l'horloge affiche **" + nowForUser.format(formatFr) + "** " +
                            "(" + getOffsetAndDifference(timezoneName, userTimezone, locale) + ")."));
        }
    }

    public static void giveTimeForOtherPlace(IReplyCallback event, Member member, String place, DiscordLocale locale) {
        // find the calling user's timezone.
        String userTimezone = null;
        if (member != null) {
            userTimezone = TimezoneBot.userTimezones.stream()
                    .filter(u -> u.serverId == member.getGuild().getIdLong() && u.userId == member.getIdLong())
                    .findFirst().map(timezone -> timezone.timezoneName).orElse(null);
        }

        try {
            // rate limit: 1 request per second
            synchronized (lastTimezoneDBRequest) {
                long timeToWait = lastTimezoneDBRequest.get() - System.currentTimeMillis() + 1000;
                if (timeToWait > 0) {
                    Thread.sleep(timeToWait);
                }
                lastTimezoneDBRequest.set(System.currentTimeMillis());
            }

            // query OpenStreetMap
            HttpURLConnection osm = ConnectionUtils.openConnectionWithTimeout("https://nominatim.openstreetmap.org/search.php?" +
                    "q=" + URLEncoder.encode(place, StandardCharsets.UTF_8) +
                    "&accept-language=" + (locale == DiscordLocale.FRENCH ? "fr" : "en") +
                    "&limit=1&format=jsonv2");
            osm.setRequestProperty("User-Agent", "TimezoneBot/1.0 (+https://maddie480.ovh/discord-bots#timezone-bot)");

            JSONArray osmResults;
            try (InputStream is = ConnectionUtils.connectionToInputStream(osm)) {
                osmResults = new JSONArray(IOUtils.toString(is, StandardCharsets.UTF_8));
            }

            if (osmResults.isEmpty()) {
                logger.info("Place '{}' was not found by OpenStreetMap!", place);
                respondPrivately(event, localizeMessage(locale,
                        ":x: This place was not found!",
                        ":x: Ce lieu n'a pas été trouvé !"));
            } else {
                double latitude = osmResults.getJSONObject(0).getFloat("lat");
                double longitude = osmResults.getJSONObject(0).getFloat("lon");
                String name = osmResults.getJSONObject(0).getString("display_name");

                logger.debug("Result for place '{}': '{}', latitude {}, longitude {}", place, name, latitude, longitude);

                JSONObject timezoneDBResult;
                try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://api.timezonedb.com/v2.1/get-time-zone?key="
                        + SecretConstants.TIMEZONEDB_API_KEY + "&format=json&by=position&lat=" + latitude + "&lng=" + longitude)) {

                    timezoneDBResult = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                }

                if (timezoneDBResult.getString("status").equals("OK")) {
                    ZoneId zoneId;
                    try {
                        zoneId = ZoneId.of(timezoneDBResult.getString("zoneName"));
                    } catch (DateTimeException e) {
                        logger.info("Zone ID '{}' was not recognized! Falling back to UTC offset.", timezoneDBResult.getString("zoneName"));
                        zoneId = ZoneId.ofOffset("UTC", ZoneOffset.ofTotalSeconds(timezoneDBResult.getInt("gmtOffset")));
                    }
                    logger.debug("Timezone of '{}' is: {}", name, zoneId);

                    ZonedDateTime nowAtPlace = ZonedDateTime.now(zoneId);
                    DateTimeFormatter format = DateTimeFormatter.ofPattern("MMM dd, HH:mm", Locale.ENGLISH);
                    DateTimeFormatter formatFr = DateTimeFormatter.ofPattern("dd MMM, HH:mm", Locale.FRENCH);

                    respondPrivately(event, localizeMessage(locale,
                            "The current time in **" + name + "** is **" + nowAtPlace.format(format) + "** " +
                                    "(" + getOffsetAndDifference(zoneId.toString(), userTimezone, locale) + ").",
                            "A **" + name + "**, l'horloge affiche **" + nowAtPlace.format(formatFr) + "** " +
                                    "(" + getOffsetAndDifference(zoneId.toString(), userTimezone, locale) + ")."));
                } else {
                    logger.info("Coordinates ({}, {}) were not found by TimeZoneDB!", latitude, longitude);
                    respondPrivately(event, localizeMessage(locale,
                            ":x: This place was not found!",
                            ":x: Ce lieu n'a pas été trouvé !"));
                }
            }
        } catch (IOException | JSONException | InterruptedException e) {
            logger.error("Error while querying timezone for {}", place, e);
            respondPrivately(event, localizeMessage(locale,
                    ":x: A technical error occurred.",
                    ":x: Une erreur technique est survenue."));
        }
    }

    /**
     * Checks that everything is fine with the server before answering to /timezone and /toggle-times.
     *
     * @param server  The server the slash command was sent in
     * @param caller  The member that sent the command
     * @param failure The message to append in case of failure
     * @return The message to send to the user, either an empty string or an explanation of how to solve
     * the issue with the server settings, with "failure" appended
     */
    private static String getRoleUpdateMessage(Guild server, Member caller, String failure, DiscordLocale locale) {
        if (!server.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
            // bot can't manage roles
            return localizeMessage(locale,
                    "\n:warning: Please " + (caller.hasPermission(Permission.ADMINISTRATOR) ? "" : "tell an admin to ")
                            + "grant the **Manage Roles** permission to the bot, so that it can "
                            + "create and assign timezone roles. " + failure,
                    "\n:warning: " + (caller.hasPermission(Permission.ADMINISTRATOR) ? "Accorde " : "Demande à un admin d'accorder ")
                            + "la permission **Gérer les rôles** au bot pour qu'il puisse créer et assigner les rôles de fuseaux horaires. " + failure);
        } else if (TimezoneBot.getTimezoneOffsetRolesForGuild(server).values().stream()
                .anyMatch(roleId -> !server.getSelfMember().canInteract(server.getRoleById(roleId)))) {

            // bot has a lower top role than one of the timezone roles
            return localizeMessage(locale,
                    "\n:warning: Please " + (caller.hasPermission(Permission.ADMINISTRATOR) ? "" : "tell an admin to ")
                            + "ensure that the Timezone Bot is higher in the role list than all timezone roles, so that it has "
                            + "the permission to manage and assign them. " + failure,
                    "\n:warning: " + (caller.hasPermission(Permission.ADMINISTRATOR) ? "Assure-toi " : "Demande à un admin de s'assurer ")
                            + "que Timezone Bot est plus haut que tous les rôles de fuseau horaire dans la liste des rôles, pour qu'il "
                            + "ait la permission de les gérer et de les assigner. " + failure);
        }
        return "";
    }

    /**
     * Tries to parse a date with multiple formats.
     * The given methods should either return the parsed LocalDateTime, or throw a DateTimeParseException.
     *
     * @param formatsToAttempt The methods to call in order to try parsing the date
     * @return The result of the first format in the list that could parse the given date, or null if no format matched
     */
    private static LocalDateTime tryParseSuccessively(List<Supplier<LocalDateTime>> formatsToAttempt) {
        // try all the formats one to one.
        for (Supplier<LocalDateTime> formatToAttempt : formatsToAttempt) {
            try {
                return formatToAttempt.get();
            } catch (DateTimeParseException e) {
                // continue!
            }
        }

        // no format matched!
        return null;
    }

    /**
     * Handles the /list-timezones command and the "get as text file" button.
     *
     * @param event                 The event triggering the command (either a SlashCommandEvent or a ButtonClickEvent)
     * @param asTextFile            Whether we should generate a text file even if the list would have fitted in a message
     * @param shouldRespondInPublic Whether the response should be public or private (aka "ephemeral").
     *                              Only matters if the given event is a SlashCommandEvent.
     * @param locale                The locale to use for the response
     */
    private void listTimezones(IReplyCallback event, String namesToUse, boolean asTextFile, boolean shouldRespondInPublic, DiscordLocale locale) {
        // list all members from the server
        Map<TimezoneBot.UserTimezone, TimezoneBot.CachedMember> members = TimezoneBot.userTimezones.stream()
                .filter(user -> user.serverId == event.getGuild().getIdLong())
                .collect(Collectors.toMap(user -> user, user -> TimezoneBot.getMemberWithCache(event.getGuild(), user.userId)));

        if (members.isEmpty()) {
            event.reply(localizeMessage(locale,
                            ":x: Nobody grabbed a timezone role with `/timezone` on this server!",
                            ":x: Personne n'a pris de rôle de fuseau horaire avec `/timezone` sur ce serveur !"))
                    .setEphemeral(!shouldRespondInPublic)
                    .queue();
            return;
        }

        // group them by UTC offset
        Map<Integer, Set<String>> peopleByUtcOffset = new TreeMap<>();
        for (Map.Entry<TimezoneBot.UserTimezone, TimezoneBot.CachedMember> member : members.entrySet()) {
            ZoneId zone = ZoneId.of(member.getKey().timezoneName);
            ZonedDateTime now = ZonedDateTime.now(zone);
            int offset = now.getOffset().getTotalSeconds() / 60;

            Set<String> peopleInUtcOffset = peopleByUtcOffset.get(offset);
            if (peopleInUtcOffset == null) {
                peopleInUtcOffset = new TreeSet<>(Comparator.comparing(s -> s.toLowerCase(Locale.ROOT)));
                peopleByUtcOffset.put(offset, peopleInUtcOffset);
            }
            peopleInUtcOffset.add(getUserName(member.getValue(), namesToUse));
        }

        // turn it into text
        String timezonesList = generateTimezonesList(peopleByUtcOffset, false, locale);
        if (asTextFile || timezonesList.length() > 2000) {
            timezonesList = generateTimezonesList(peopleByUtcOffset, true, locale);
            asTextFile = true;
        }

        String message = asTextFile ? localizeMessage(locale,
                "Here is a list of people's timezones on the server:",
                "Voici une liste des fuseaux horaires des membres de ce serveur :") : timezonesList;

        // if that was a button click, we want to edit the message. Otherwise, that was a slash command, and we want to respond to it.
        if (event instanceof ButtonInteractionEvent) {
            ((ButtonInteractionEvent) event)
                    .editMessage(message)
                    .setComponents() // I want NO action row
                    .setFiles(FileUpload.fromData(timezonesList.getBytes(StandardCharsets.UTF_8), "timezone_list.txt"))
                    .queue();
        } else {
            ReplyCallbackAction reply = event.reply(message).setEphemeral(!shouldRespondInPublic);
            if (asTextFile) {
                reply = reply.addFiles(FileUpload.fromData(timezonesList.getBytes(StandardCharsets.UTF_8), "timezone_list.txt"));
            } else {
                reply = reply.addActionRow(Button.of(ButtonStyle.SECONDARY, "list-timezones-to-file" + namesToUse,
                        localizeMessage(locale, "Get as text file", "Convertir en fichier texte"), Emoji.fromUnicode("\uD83D\uDCC4")));
            }
            reply.queue();
        }
    }

    private String getUserName(TimezoneBot.CachedMember member, String nameToUse) {
        return switch (nameToUse) {
            case "nicknames" -> member.nickname;
            case "both" -> member.nickname + " (" + member.discordTag + ")";
            default -> member.discordTag;
        };
    }

    private String generateTimezonesList(Map<Integer, Set<String>> people, boolean forTextFile, DiscordLocale locale) {
        StringBuilder list = new StringBuilder(forTextFile ? "" : localizeMessage(locale,
                "Here is a list of people's timezones on the server:\n\n",
                "Voici une liste des fuseaux horaires des membres de ce serveur :\n\n"));
        for (Map.Entry<Integer, Set<String>> peopleInTimezone : people.entrySet()) {
            int offsetMinutes = peopleInTimezone.getKey();

            OffsetDateTime now = OffsetDateTime.now().withOffsetSameInstant(ZoneOffset.ofTotalSeconds(offsetMinutes * 60));

            if (forTextFile) {
                list.append("== ");
            } else {
                // Discord has nice emoji for every 30 minutes, so let's use them :p
                int hour = now.getHour();
                if (hour == 0) hour = 12;
                if (hour > 12) hour -= 12;

                list.append("**:clock").append(hour);

                if (now.getMinute() >= 30) {
                    list.append("30");
                }

                list.append(": ");
            }

            list.append(TimezoneBot.formatTimezoneName(offsetMinutes))
                    .append(" (").append(now.format(DateTimeFormatter.ofPattern(locale == DiscordLocale.FRENCH ? "H:mm" : "h:mma")).toLowerCase(Locale.ROOT)).append(")");

            if (!forTextFile) {
                list.append("**");
            }
            list.append("\n");

            for (String member : peopleInTimezone.getValue()) {
                list.append(member).append("\n");
            }

            list.append("\n");
        }

        return list.toString();
    }

    /**
     * Equivalent to Map.get(key), except the key is case-insensitive.
     *
     * @param map The map to get the element from
     * @param key The element to get
     * @param <T> The type of the values in the map
     * @return The value that was found, or null if no value was found
     */
    private static <T> T getIgnoreCase(Map<String, T> map, String key) {
        return map.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    private static String getOffsetAndDifference(String consideredTimezone, String userTimezone, DiscordLocale locale) {
        ZoneId zone = ZoneId.of(consideredTimezone);
        ZonedDateTime now = ZonedDateTime.now(zone);
        int consideredTimezoneOffset = now.getOffset().getTotalSeconds() / 60;

        if (userTimezone == null) {
            return TimezoneBot.formatTimezoneName(consideredTimezoneOffset);
        } else {
            zone = ZoneId.of(userTimezone);
            now = ZonedDateTime.now(zone);
            int userTimezoneOffset = now.getOffset().getTotalSeconds() / 60;

            int timeDifference = consideredTimezoneOffset - userTimezoneOffset;

            if (timeDifference == 0) {
                return TimezoneBot.formatTimezoneName(consideredTimezoneOffset) + ", " + localizeMessage(locale, "same time as you", "même heure que toi");
            } else {
                String comment;
                int hours = Math.abs(timeDifference / 60);
                int minutes = Math.abs(timeDifference % 60);

                if (minutes == 0) {
                    comment = hours + " " + localizeMessage(locale, "hour", "heure") + (hours == 1 ? "" : "s");
                } else {
                    comment = hours + ":" + new DecimalFormat("00").format(minutes);
                }

                if (timeDifference < 0) {
                    comment += " " + localizeMessage(locale, "behind you", "de retard sur toi");
                } else {
                    comment += " " + localizeMessage(locale, "ahead of you", "d'avance sur toi");
                }

                return TimezoneBot.formatTimezoneName(consideredTimezoneOffset) + ", " + comment;
            }
        }
    }


    private void generateTimezoneDropdown(SlashCommandInteractionEvent slashCommandEvent) {
        DiscordLocale locale = slashCommandEvent.getUserLocale();

        OptionMapping optionsParam = slashCommandEvent.getOption("options");
        OptionMapping messageParam = slashCommandEvent.getOption("message");

        final String help = localizeMessage(locale,
                "\n\nIf you need help, check this page for syntax and examples: <https://maddie480.ovh/discord-bots/timezone-bot/timezone-dropdown-help>",
                "\n\nSi tu as besoin d'aide, consulte cette page pour avoir des explications et des exemples : <https://maddie480.ovh/discord-bots/timezone-bot/timezone-dropdown-help>");

        if (optionsParam != null) {
            if (optionsParam.getAsString().toLowerCase(Locale.ROOT).equals("help")) {
                respondPrivately(slashCommandEvent, localizeMessage(locale,
                        "Check this page for syntax and examples: <https://maddie480.ovh/discord-bots/timezone-bot/timezone-dropdown-help>",
                        "Consulte cette page pour avoir des explications et des exemples : <https://maddie480.ovh/discord-bots/timezone-bot/timezone-dropdown-help>"));
                return;
            }

            List<SelectOption> options = new ArrayList<>();
            for (String choice : optionsParam.getAsString().split(",")) {
                String label, timezone;

                String[] split = choice.split("\\|", 2);
                if (split.length == 2) {
                    label = split[0];
                    timezone = split[1];
                } else {
                    label = choice;
                    timezone = choice;
                }

                if (label.isEmpty() || label.length() > 32) {
                    logger.warn("Label {} was too long or too short!", label);

                    respondPrivately(slashCommandEvent, localizeMessage(locale,
                            ":x: Labels should have between 1 and 32 characters!" + help,
                            ":x: Les libellés doivent faire entre 1 et 32 caractères !" + help));

                    return;
                }

                try {
                    // check for one of the recognized formats like "EST".
                    String timezoneOffsetFromName = getIgnoreCase(TIMEZONE_MAP, timezone);
                    if (timezoneOffsetFromName == null) {
                        // check the timezone can be parsed by Java if this is not one of the known formats.
                        ZoneId.of(timezone);
                    }

                    // generate the dropdown option for it.
                    SelectOption option = SelectOption.of(label, timezone);
                    if (!label.equals(timezone)) {
                        option = option.withDescription(timezone);
                    }
                    options.add(option);

                } catch (DateTimeException ex) {
                    logger.warn("Could not parse timezone {}", timezone);

                    List<String> conflictingTimezones = getIgnoreCase(TIMEZONE_CONFLICTS, timezone);
                    if (conflictingTimezones != null) {
                        respondPrivately(slashCommandEvent, localizeMessage(locale,
                                ":x: The timezone **" + timezone + "** is ambiguous! It could mean one of those: _"
                                        + String.join("_, _", conflictingTimezones) + "_.\n" +
                                        "Repeat the command with the timezone full name!" + help,
                                ":x: Le fuseau horaire **" + timezone + "** est ambigu ! Il peut désigner _"
                                        + String.join("_, _", conflictingTimezones) + "_.\n" +
                                        "Relance la commande avec le nom complet du fuseau horaire !" + help));
                    } else {
                        respondPrivately(slashCommandEvent, localizeMessage(locale,
                                ":x: The timezone `" + timezone + "` was not recognized." + help,
                                ":x: Le fuseau horaire `" + timezone + "` n'a pas été reconnu." + help));
                    }

                    return;
                }
            }

            if (options.size() > 25) {
                logger.warn("Too many options ({}): {}", options.size(), options);
                respondPrivately(slashCommandEvent, localizeMessage(locale,
                        ":x: You cannot have more than 25 options! You gave " + options.size() + " of them." + help,
                        ":x: Il est impossible d'avoir plus de 25 options ! Tu en as donné " + options.size() + "." + help));
            } else {
                logger.debug("Posting options: {}", options);
                slashCommandEvent.reply(messageParam != null ? messageParam.getAsString() : """
                                **Pick a timezone role here!**
                                If your timezone does not match any of those, run the `/timezone [tz_name]` command.
                                To remove your timezone role, run the `/remove-timezone` command.""")
                        .addActionRow(StringSelectMenu.create("timezone-dropdown").addOptions(options).build())
                        .queue();
            }
        }
    }

    private static String localizeMessage(DiscordLocale locale, String english, String french) {
        if (locale == DiscordLocale.FRENCH) {
            return french;
        }

        return english;
    }

    private static void respondPrivately(IReplyCallback event, String response) {
        event.reply(response).setEphemeral(true).queue();
    }

    private static void respondPrivately(IReplyCallback event, MessageCreateData response) {
        event.reply(response).setEphemeral(true).queue();
    }
}
