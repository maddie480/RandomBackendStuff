package com.max480.discord.randombots;

import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.update.GuildUpdateOwnerEvent;
import net.dv8tion.jda.api.events.interaction.ButtonClickEvent;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.SelectionMenuEvent;
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent;
import net.dv8tion.jda.api.events.role.RoleCreateEvent;
import net.dv8tion.jda.api.events.role.RoleDeleteEvent;
import net.dv8tion.jda.api.events.role.update.RoleUpdatePermissionsEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.Button;
import net.dv8tion.jda.api.interactions.components.ButtonStyle;
import net.dv8tion.jda.api.interactions.components.selections.SelectionMenu;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyAction;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * This class handles everything that can happen (the bot joins/leaves a server, someone runs a slash command, etc).
 */
public class BotEventListener extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(BotEventListener.class);

    // offset timezone database
    private final Map<String, String> TIMEZONE_MAP;
    private final Map<String, List<String>> TIMEZONE_CONFLICTS;

    public BotEventListener(Map<String, String> timezoneMap, Map<String, List<String>> timezoneConflicts) {
        TIMEZONE_MAP = timezoneMap;
        TIMEZONE_CONFLICTS = timezoneConflicts;
    }

    // === BEGIN event handling for /toggle-times

    @Override
    public void onGuildJoin(@NotNull GuildJoinEvent event) {
        event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                .sendMessage("I just joined a new server! I am now in **" + event.getJDA().getGuilds().size() + "** servers.").queue();

        // set up privileges for the new server!
        logger.info("Updating /toggle-times permissions on newly joined guild {}", event.getGuild());
        TimezoneBot.updateToggleTimesPermsForGuilds(Collections.singletonList(event.getGuild()));
    }

    @Override
    public void onGuildLeave(@NotNull GuildLeaveEvent event) {
        event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                .sendMessage("I was just kicked from a server. I am now in **" + event.getJDA().getGuilds().size() + "** servers.").queue();

        // refreshing roles allows us to clean up any user info we had on that server.
        logger.info("Force-refreshing roles after leaving guild {}", event.getGuild());
        TimezoneRoleUpdater.forceUpdate();
    }

    @Override
    public void onGuildUpdateOwner(@NotNull GuildUpdateOwnerEvent event) {
        // this means the privileges should be given to the new owner!
        logger.info("Updating /toggle-times permissions after ownership transfer on {} ({} -> {})", event.getGuild(), event.getOldOwner(), event.getNewOwner());
        TimezoneBot.updateToggleTimesPermsForGuilds(Collections.singletonList(event.getGuild()));
    }

    @Override
    public void onRoleCreate(@NotNull RoleCreateEvent event) {
        if (event.getRole().hasPermission(Permission.ADMINISTRATOR) || event.getRole().hasPermission(Permission.MANAGE_SERVER)) {
            // the new role has permission to call /toggle-times, so we should give it to it.
            logger.info("Updating /toggle-times permissions after new role was created on {} ({} with permissions {})", event.getGuild(),
                    event.getRole(), event.getRole().getPermissions());
            TimezoneBot.updateToggleTimesPermsForGuilds(Collections.singletonList(event.getGuild()));
        }
    }

    @Override
    public void onRoleUpdatePermissions(@NotNull RoleUpdatePermissionsEvent event) {
        if (event.getOldPermissions().contains(Permission.ADMINISTRATOR) != event.getNewPermissions().contains(Permission.ADMINISTRATOR)
                || event.getOldPermissions().contains(Permission.MANAGE_SERVER) != event.getNewPermissions().contains(Permission.MANAGE_SERVER)) {

            // the role was just added / removed a permission giving access to /toggle-times, so we need to update!
            logger.info("Updating /toggle-times permissions after role {} was updated on {} ({} -> {})",
                    event.getRole(), event.getGuild(), event.getOldPermissions(), event.getNewPermissions());
            TimezoneBot.updateToggleTimesPermsForGuilds(Collections.singletonList(event.getGuild()));
        }
    }

    @Override
    public void onRoleDelete(@NotNull RoleDeleteEvent event) {
        if (event.getRole().hasPermission(Permission.ADMINISTRATOR) || event.getRole().hasPermission(Permission.MANAGE_SERVER)) {
            // the role had permission to call /toggle-times, so we should refresh.
            // (this is mainly in case this makes the server go below the 10 overrides limit.)
            logger.info("Updating /toggle-times permissions after role was deleted on {} ({} with permissions {})", event.getGuild(),
                    event.getRole(), event.getRole().getPermissions());
            TimezoneBot.updateToggleTimesPermsForGuilds(Collections.singletonList(event.getGuild()));
        }
    }

    // === END event handling for /toggle-times

    @Override
    public void onSlashCommand(SlashCommandEvent event) {
        if (!event.isFromGuild()) {
            // wtf??? slash commands are disabled in DMs
            event.reply("This bot is not usable in DMs!").setEphemeral(true).queue();
        } else if ("list-timezones".equals(event.getName())) {
            // list-timezones needs the raw event in order to reply with attachments and/or action rows.
            OptionMapping visibility = event.getOption("visibility");
            OptionMapping names = event.getOption("names");
            logger.info("New command: /list-timezones by member {}, params=[{}, {}]", event.getMember(), visibility, names);
            listTimezones(event,
                    names == null ? "discord_tags" : names.getAsString(),
                    false,
                    visibility != null && "public".equals(visibility.getAsString()));
        } else {
            OptionMapping optionTimezone = event.getOption("tz_name");
            OptionMapping optionDateTime = event.getOption("date_time");
            OptionMapping optionMember = event.getOption("member");
            processMessage(event.getMember(), event.getName(),
                    optionTimezone == null ? null : optionTimezone.getAsString(),
                    optionDateTime == null ? null : optionDateTime.getAsString(),
                    optionMember == null ? null : optionMember.getAsLong(),
                    response -> {
                        if (response instanceof Message) {
                            event.reply((Message) response).setEphemeral(true).queue();
                        } else {
                            event.reply(response.toString()).setEphemeral(true).queue();
                        }
                    }, true);
        }
    }

    @Override
    public void onSelectionMenu(@NotNull SelectionMenuEvent event) {
        if ("discord-timestamp".equals(event.getComponent().getId())) {
            logger.info("New interaction with selection menu from member {}, picked {}", event.getMember(), event.getValues().get(0));

            // the user picked a timestamp format! we should edit the message to that timestamp so that they can copy it easier.
            // we also want the menu to stay the same, so that they can switch.
            event.editMessage(new MessageBuilder(event.getValues().get(0))
                    .setActionRows(ActionRow.of(event.getSelectionMenu().createCopy()
                            .setDefaultValues(event.getValues())
                            .build()))
                    .build()).queue();
        }
    }

    @Override
    public void onButtonClick(@Nonnull ButtonClickEvent event) {
        if (event.getComponentId().startsWith("list-timezones-to-file")) {
            String nameFormat = event.getComponentId().substring("list-timezones-to-file".length());
            logger.info("New interaction with button from member {}, chose to get timezone list as text file with name format '{}'", event.getMember(), nameFormat);

            // list timezones again, but this time force it to go to a text file.
            listTimezones(event, nameFormat, true, false);
        }
    }

    /**
     * Processes a command, received either by message or by slash command.
     *
     * @param member         The member that sent the command
     * @param command        The command that was sent, without the ! or /
     * @param timezoneParam  The tz_name parameter passed
     * @param dateTimeParam  The date_time parameter passed
     * @param memberParam    The member parameter passed
     * @param respond        The method to call to respond to the message (either posting to the channel, or responding to the slash command)
     * @param isSlashCommand Indicates if we are using a slash command (so we should always answer)
     */
    private void processMessage(Member member, String command, String timezoneParam, String dateTimeParam, Long memberParam,
                                Consumer<Object> respond, boolean isSlashCommand) {

        logger.info("New command: /{} by member {}, params=[tz_name='{}', date_time='{}', member='{}']", command, member, timezoneParam, dateTimeParam, memberParam);

        if (command.equals("timezone") && timezoneParam == null) {
            // print help
            respond.accept("Usage: `!timezone [tzdata timezone name]` (example: `!timezone Europe/Paris`)\n" +
                    "To figure out your timezone, visit https://max480-random-stuff.appspot.com/detect-timezone.html\n" +
                    "If you don't want to share your timezone name, you can use the slash command (it will only be visible by you)" +
                    " or use an offset like UTC+8 (it won't automatically adjust with daylight saving though).\n\n" +
                    "If you want to get rid of your timezone role, use `!remove-timezone`.");
        }

        if (command.equals("detect-timezone")) {
            respond.accept("To figure out your timezone, visit <https://max480-random-stuff.appspot.com/detect-timezone.html>.");
        }

        if (command.equals("timezone") && timezoneParam != null) {
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
                TimezoneBot.saveUsersTimezonesToFile(respond, ":white_check_mark: Your timezone was saved as **" + timezoneParam + "**.\n" +
                        "The current time in this timezone is **" + localNow.format(format) + "**. " +
                        "If this does not match your local time, type `/detect-timezone` to find the right one.\n\n" +
                        getRoleUpdateMessage(member.getGuild(), member,
                                "Your role will be assigned within 15 minutes once this is done."));

                TimezoneRoleUpdater.forceUpdate();
            } catch (DateTimeException ex) {
                // ZoneId.of blew up so the timezone is probably invalid.
                logger.warn("Could not parse timezone " + timezoneParam, ex);

                List<String> conflictingTimezones = getIgnoreCase(TIMEZONE_CONFLICTS, timezoneParam);
                if (conflictingTimezones != null) {
                    respond.accept(":x: The timezone **" + timezoneParam + "** is ambiguous! It could mean one of those: _"
                            + String.join("_, _", conflictingTimezones) + "_.\n" +
                            "Repeat the command with the timezone full name!");
                } else {
                    respond.accept(":x: The given timezone was not recognized.\n" +
                            "To figure out your timezone, visit https://max480-random-stuff.appspot.com/detect-timezone.html");
                }
            }
        }

        if (command.equals("remove-timezone")) {
            // find the user's timezone.
            TimezoneBot.UserTimezone userTimezone = TimezoneBot.userTimezones.stream()
                    .filter(u -> u.serverId == member.getGuild().getIdLong() && u.userId == member.getIdLong())
                    .findFirst().orElse(null);

            if (userTimezone != null) {
                String error = getRoleUpdateMessage(member.getGuild(), member, "You will be able to remove your timezone role once this is done.");
                if (!error.isEmpty()) {
                    // since the command involves removing the roles **now**, we can't do it at all if there are permission issues!
                    respond.accept(error.replace(":warning:", ":x:"));
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
                TimezoneBot.saveUsersTimezonesToFile(respond, ":white_check_mark: Your timezone role has been removed.");
            } else {
                // user asked for their timezone to be forgotten, but doesn't have a timezone to start with :thonk:
                respond.accept(":x: You don't currently have a timezone role!");
            }
        }

        if (command.equals("toggle-times") && (member.hasPermission(Permission.ADMINISTRATOR)
                || member.hasPermission(Permission.MANAGE_SERVER))) {

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

                respond.accept(":white_check_mark: " + (newValue ?
                        "The timezone roles will now show the time it is in the timezone." :
                        "The timezone roles won't show the time it is in the timezone anymore.") + "\n" +
                        getRoleUpdateMessage(member.getGuild(), member,
                                "The roles will be updated within 15 minutes once this is done."));

                TimezoneRoleUpdater.forceUpdate();
            } catch (IOException e) {
                // I/O error while saving to disk??
                logger.error("Error while writing file", e);
                respond.accept(":x: A technical error occurred.");
            }
        }

        if (command.equals("discord-timestamp") && dateTimeParam != null) {
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
                respond.accept(":x: The date you gave could not be parsed!\nMake sure you followed the format `YYYY-MM-dd hh:mm:ss`. " +
                        "For example: `2020-10-01 15:42:00`\nYou can omit part of the date (or omit it entirely if you want today), and the seconds if you don't need that.");
            } else {
                timestamp = parsedDateTime.atZone(ZoneId.of(timezoneToUse)).toEpochSecond();
            }

            if (timestamp != null) {
                StringBuilder b = new StringBuilder();
                if (timezoneName == null) {
                    // warn the user that we used UTC.
                    b.append(":warning: You did not grab a timezone role, so **UTC** was used instead.\n\n");
                }

                // print `<t:timestamp:format>` => <t:timestamp:format> for all available formats.
                b.append("Copy-paste one of those tags in your message, and others will see **" + dateTimeParam + "** in their timezone:\n");
                for (char format : new char[]{'t', 'T', 'd', 'D', 'f', 'F', 'R'}) {
                    b.append("`<t:").append(timestamp).append(':').append(format)
                            .append(">` :arrow_right: <t:").append(timestamp).append(':').append(format).append(">\n");
                }
                b.append("\n\nIf you are on mobile, pick a format for easier copy-pasting:");

                // we want to show a selection menu for the user to pick a format.
                // the idea is that they can get a message with only the tag to copy in it and can copy it on mobile,
                // which is way more handy than selecting part of the full message on mobile.
                ZonedDateTime time = Instant.ofEpochSecond(timestamp).atZone(ZoneId.of(timezoneToUse));
                respond.accept(new MessageBuilder(b.toString().trim())
                        .setActionRows(ActionRow.of(
                                SelectionMenu.create("discord-timestamp")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH)), "`<t:" + timestamp + ":t>`")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH)), "`<t:" + timestamp + ":T>`")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH)), "`<t:" + timestamp + ":d>`")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)), "`<t:" + timestamp + ":D>`")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("MMMM d, yyyy h:mm a", Locale.ENGLISH)), "`<t:" + timestamp + ":f>`")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy h:mm a", Locale.ENGLISH)), "`<t:" + timestamp + ":F>`")
                                        .addOption("Relative to now", "`<t:" + timestamp + ":R>`")
                                        .build()
                        ))
                        .build());
            }
        }

        if (command.equals("time-for") && memberParam != null) {
            // find the target user's timezone.
            String timezoneName = TimezoneBot.userTimezones.stream()
                    .filter(u -> u.serverId == member.getGuild().getIdLong() && u.userId == memberParam)
                    .findFirst().map(timezone -> timezone.timezoneName).orElse(null);

            if (timezoneName == null) {
                // the user is not in the database.
                respond.accept(":x: <@" + memberParam + "> does not have a timezone role.");
            } else {
                // format the time and display it!
                ZonedDateTime nowForUser = ZonedDateTime.now(ZoneId.of(timezoneName));
                DateTimeFormatter format = DateTimeFormatter.ofPattern("MMM dd, HH:mm", Locale.ENGLISH);
                respond.accept("The current time for <@" + memberParam + "> is **" + nowForUser.format(format) + "**.");
            }
        }

        if (command.equals("toggle-times") && isSlashCommand && !member.hasPermission(Permission.ADMINISTRATOR)
                && !member.hasPermission(Permission.MANAGE_SERVER)) {

            // we should always respond to slash commands!
            respond.accept("You must have the Administrator or Manage Server permission to use this!");
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
    private static String getRoleUpdateMessage(Guild server, Member caller, String failure) {
        if (!server.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
            // bot can't manage roles
            return "\n:warning: Please " + (caller.hasPermission(Permission.ADMINISTRATOR) ? "" : "tell an admin to ")
                    + "grant the **Manage Roles** permission to the bot, so that it can "
                    + "create and assign timezone roles. " + failure;
        } else if (TimezoneBot.getTimezoneOffsetRolesForGuild(server).values().stream()
                .anyMatch(roleId -> !server.getSelfMember().canInteract(server.getRoleById(roleId)))) {

            // bot has a lower top role than one of the timezone roles
            return "\n:warning: Please " + (caller.hasPermission(Permission.ADMINISTRATOR) ? "" : "tell an admin to ")
                    + "ensure that the Timezone Bot is higher in the role list than all timezone roles, so that it has "
                    + "the permission to manage and assign them. " + failure;
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
     */
    private void listTimezones(GenericInteractionCreateEvent event, String namesToUse, boolean asTextFile, boolean shouldRespondInPublic) {
        // list all members from the server
        Map<TimezoneBot.UserTimezone, TimezoneBot.CachedMember> members = TimezoneBot.userTimezones.stream()
                .filter(user -> user.serverId == event.getGuild().getIdLong())
                .collect(Collectors.toMap(user -> user, user -> TimezoneBot.getMemberWithCache(event.getGuild(), user.userId)));

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
        String timezonesList = generateTimezonesList(peopleByUtcOffset, false);
        if (asTextFile || timezonesList.length() > 2000) {
            timezonesList = generateTimezonesList(peopleByUtcOffset, true);
            asTextFile = true;
        }

        String message = asTextFile ? "Here is a list of people's timezones on the server:" : timezonesList;

        // if that was a button click, we want to edit the message. Otherwise, that was a slash command, and we want to respond to it.
        if (event instanceof ButtonClickEvent) {
            ((ButtonClickEvent) event)
                    .editMessage(message)
                    .setActionRows() // I want NO action row
                    .addFile(timezonesList.getBytes(StandardCharsets.UTF_8), "timezone_list.txt")
                    .queue();
        } else {
            ReplyAction reply = event.reply(message).setEphemeral(!shouldRespondInPublic);
            if (asTextFile) {
                reply.addFile(timezonesList.getBytes(StandardCharsets.UTF_8), "timezone_list.txt");
            } else {
                reply.addActionRow(Button.of(ButtonStyle.SECONDARY, "list-timezones-to-file" + namesToUse, "Get as text file", Emoji.fromUnicode("\uD83D\uDCC4")));
            }
            reply.queue();
        }
    }

    private String getUserName(TimezoneBot.CachedMember member, String nameToUse) {
        switch (nameToUse) {
            case "nicknames":
                return member.nickname;
            case "both":
                return member.nickname + " (" + member.discordTag + ")";
            default:
                return member.discordTag;
        }
    }

    private String generateTimezonesList(Map<Integer, Set<String>> people, boolean forTextFile) {
        StringBuilder list = new StringBuilder(forTextFile ? "" : "Here is a list of people's timezones on the server:\n\n");
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
                    .append(" (").append(now.format(DateTimeFormatter.ofPattern("h:mma")).toLowerCase(Locale.ROOT)).append(")");

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
}
