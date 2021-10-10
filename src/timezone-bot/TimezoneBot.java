package com.max480.discord.randombots;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.update.GuildUpdateOwnerEvent;
import net.dv8tion.jda.api.events.interaction.SelectionMenuEvent;
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent;
import net.dv8tion.jda.api.events.role.RoleCreateEvent;
import net.dv8tion.jda.api.events.role.RoleDeleteEvent;
import net.dv8tion.jda.api.events.role.update.RoleUpdatePermissionsEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.privileges.CommandPrivilege;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.selections.SelectionMenu;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TimezoneBot extends ListenerAdapter implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(TimezoneBot.class);

    /**
     * The timezone of a user in a particular server.
     * Timezones are server-specific, since you might want to only show your timezone to some servers.
     */
    private static class UserTimezone {
        private final long serverId;
        private final long userId;
        private final String timezoneName;

        public UserTimezone(long serverId, long userId, String timezoneName) {
            this.serverId = serverId;
            this.userId = userId;
            this.timezoneName = timezoneName;
        }

        @Override
        public String toString() {
            return "UserTimezone{" +
                    "serverId=" + serverId +
                    ", userId=" + userId +
                    ", timezoneName='" + timezoneName + '\'' +
                    '}';
        }
    }

    /**
     * A member in a server, with their current roles.
     * This allows to avoid having to retrieve all members at each time.
     */
    private static class CachedMember implements Serializable {
        private final long serverId;
        private final long memberId;
        private final ArrayList<Long> roleIds;

        public CachedMember(long serverId, long memberId, ArrayList<Long> roleIds) {
            this.serverId = serverId;
            this.memberId = memberId;
            this.roleIds = roleIds;
        }

        @Override
        public String toString() {
            return "MemberCache{" +
                    "serverId=" + serverId +
                    ", memberId=" + memberId +
                    ", roleIds=" + roleIds +
                    '}';
        }
    }

    private static List<UserTimezone> userTimezones; // user ID > timezone name
    private static Set<Long> serversWithTime; // servers that want times in timezone roles
    private static ArrayList<CachedMember> memberCache = new ArrayList<>(); // cache of users retrieved in the past

    private static ZonedDateTime lastRoleUpdateDate = null;

    private static JDA jda;

    // names of files on disk
    private static final String SAVE_FILE_NAME = "user_timezones.csv";
    private static final String SERVERS_WITH_TIME_FILE_NAME = "servers_with_time.txt";
    private static final String MEMBER_CACHE_NAME = "timezone_bot_member_cache.ser";

    public static void main(String[] args) throws Exception {
        // load the saved files (user settings, server settings, member cache).
        userTimezones = new ArrayList<>();
        if (new File(SAVE_FILE_NAME).exists()) {
            try (Stream<String> lines = Files.lines(Paths.get(SAVE_FILE_NAME))) {
                lines.forEach(line -> userTimezones.add(new UserTimezone(
                        Long.parseLong(line.split(";")[0]), Long.parseLong(line.split(";")[1]), line.split(";", 3)[2])));
            }
        }
        serversWithTime = new HashSet<>();
        if (new File(SERVERS_WITH_TIME_FILE_NAME).exists()) {
            try (Stream<String> lines = Files.lines(Paths.get(SERVERS_WITH_TIME_FILE_NAME))) {
                lines.forEach(line -> serversWithTime.add(Long.parseLong(line)));
            }
        }
        if (new File(MEMBER_CACHE_NAME).exists()) {
            try (ObjectInputStream input = new ObjectInputStream(new FileInputStream(MEMBER_CACHE_NAME))) {
                memberCache = (ArrayList<CachedMember>) input.readObject();
            }
        }

        // start up the bot.
        jda = JDABuilder.createLight(SecretConstants.TIMEZONE_BOT_TOKEN, Collections.emptyList())
                .addEventListeners(new TimezoneBot())
                .build().awaitReady();

        // cleanup non-existing servers from servers with time, and save.
        for (Long serverId : new HashSet<>(serversWithTime)) {
            if (jda.getGuildById(serverId) == null) {
                logger.warn("Removing non-existing server {} from servers with time list", serverId);
                serversWithTime.remove(serverId);
            }
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(SERVERS_WITH_TIME_FILE_NAME))) {
            for (Long server : serversWithTime) {
                writer.write(server + "\n");
            }
        }

        logger.debug("Users by timezone = {}, servers with time = {}, member cache = {}", userTimezones.size(), serversWithTime.size(), memberCache.size());

        // also ensure the /toggle_times permissions are appropriate (in case we missed an event while being down).
        logger.info("Updating /toggle_times permissions on all known guilds...");
        updateToggleTimesPermsForGuilds(jda.getGuilds());

        // start the background process to update users' roles.
        new Thread(new TimezoneBot()).start();
    }

    // === BEGIN event handling for /toggle_times

    @Override
    public void onGuildJoin(@NotNull GuildJoinEvent event) {
        event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                .sendMessage("I just joined a new server: " + event.getGuild().getName()).queue();

        // set up privileges for the new server!
        logger.info("Updating /toggle_times permissions on newly joined guild {}", event.getGuild());
        updateToggleTimesPermsForGuilds(Collections.singletonList(event.getGuild()));
    }

    @Override
    public void onGuildLeave(@NotNull GuildLeaveEvent event) {
        event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                .sendMessage("I just left a server: " + event.getGuild().getName()).queue();
    }

    @Override
    public void onGuildUpdateOwner(@NotNull GuildUpdateOwnerEvent event) {
        // this means the privileges should be given to the new owner!
        logger.info("Updating /toggle_times permissions after ownership transfer on {} ({} -> {})", event.getGuild(), event.getOldOwner(), event.getNewOwner());
        updateToggleTimesPermsForGuilds(Collections.singletonList(event.getGuild()));
    }

    @Override
    public void onRoleCreate(@NotNull RoleCreateEvent event) {
        if (event.getRole().hasPermission(Permission.ADMINISTRATOR) || event.getRole().hasPermission(Permission.MANAGE_SERVER)) {
            // the new role has permission to call /toggle_times, so we should give it to it.
            logger.info("Updating /toggle_times permissions after new role was created on {} ({} with permissions {})", event.getGuild(),
                    event.getRole(), event.getRole().getPermissions());
            updateToggleTimesPermsForGuilds(Collections.singletonList(event.getGuild()));
        }
    }

    @Override
    public void onRoleUpdatePermissions(@NotNull RoleUpdatePermissionsEvent event) {
        if (event.getOldPermissions().contains(Permission.ADMINISTRATOR) != event.getNewPermissions().contains(Permission.ADMINISTRATOR)
                || event.getOldPermissions().contains(Permission.MANAGE_SERVER) != event.getNewPermissions().contains(Permission.MANAGE_SERVER)) {

            // the role was just added / removed a permission giving access to /toggle_times, so we need to update!
            logger.info("Updating /toggle_times permissions after role {} was updated on {} ({} -> {})",
                    event.getRole(), event.getGuild(), event.getOldPermissions(), event.getNewPermissions());
            updateToggleTimesPermsForGuilds(Collections.singletonList(event.getGuild()));
        }
    }

    @Override
    public void onRoleDelete(@NotNull RoleDeleteEvent event) {
        if (event.getRole().hasPermission(Permission.ADMINISTRATOR) || event.getRole().hasPermission(Permission.MANAGE_SERVER)) {
            // the role had permission to call /toggle_times, so we should refresh.
            // (this is mainly in case this makes the server go below the 10 overrides limit.)
            logger.info("Updating /toggle_times permissions after role was deleted on {} ({} with permissions {})", event.getGuild(),
                    event.getRole(), event.getRole().getPermissions());
            updateToggleTimesPermsForGuilds(Collections.singletonList(event.getGuild()));
        }
    }

    // === END event handling for /toggle_times

    /**
     * Looks up timezone offset roles for a server by matching them by role name.
     *
     * @param g The server to retrieve offset roles for
     * @return The retrieved offset roles (map for UTC offset -> role ID)
     */
    private static Map<Integer, Long> getTimezoneOffsetRolesForGuild(Guild g) {
        Pattern roleName = Pattern.compile("^Timezone UTC([+-][0-9][0-9]):([0-9][0-9])(?: \\([0-2]?[0-9][ap]m\\))?$");

        List<Long> extraRoles = new ArrayList<>();
        Map<Integer, Long> result = new HashMap<>(g.getRoles().stream()
                .filter(role -> roleName.matcher(role.getName()).matches())
                .map(role -> {
                    // parse the UTC offset.
                    Matcher nameMatch = roleName.matcher(role.getName());
                    nameMatch.matches();
                    int hours = Integer.parseInt(nameMatch.group(1));
                    int minutes = Integer.parseInt(nameMatch.group(2));
                    if (hours < 0) minutes *= -1;

                    return Pair.of(hours * 60 + minutes, role.getIdLong());
                })
                .collect(Collectors.toMap(Pair::getLeft, Pair::getRight, (a, b) -> {
                    // for collisions, we put the first element in the map, and we store the second one in a corner.
                    extraRoles.add(b);
                    return a;
                })));

        // give the duplicate roles fake UTC offsets, that will make them appear unused and get deleted.
        int fakeIndex = 1000000;
        for (long roleId : extraRoles) {
            result.put(fakeIndex++, roleId);
        }
        return result;
    }

    @Override
    public void onSlashCommand(SlashCommandEvent event) {
        if (!event.isFromGuild()) {
            // wtf??? slash commands are disabled in DMs
            event.reply("This bot is not usable in DMs!").setEphemeral(true).queue();
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
        if ("discord_timestamp".equals(event.getComponent().getId())) {
            // the user picked a timestamp format! we should edit the message to that timestamp so that they can copy it easier.
            // we also want the menu to stay the same, so that they can switch.
            event.editMessage(new MessageBuilder(event.getValues().get(0))
                    .setActionRows(ActionRow.of(event.getSelectionMenu().createCopy()
                            .setDefaultValues(event.getValues())
                            .build()))
                    .build()).queue();
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
                    "If you want to get rid of your timezone role, use `!remove_timezone`.");
        }

        if (command.equals("detect_timezone")) {
            respond.accept("To figure out your timezone, visit <https://max480-random-stuff.appspot.com/detect-timezone.html>.");
        }

        if (command.equals("timezone") && timezoneParam != null) {
            try {
                // check that the timezone is valid by passing it to ZoneId.of.
                ZonedDateTime localNow = ZonedDateTime.now(ZoneId.of(timezoneParam));

                // remove old link if there is any.
                userTimezones.stream()
                        .filter(u -> u.serverId == member.getGuild().getIdLong() && u.userId == member.getIdLong())
                        .findFirst()
                        .map(u -> userTimezones.remove(u));

                // save the link, both in userTimezones and on disk.
                userTimezones.add(new UserTimezone(member.getGuild().getIdLong(), member.getIdLong(), timezoneParam));
                logger.info("User {} now has timezone {}", member.getIdLong(), timezoneParam);
                memberCache.remove(getMemberWithCache(member.getGuild(), member.getIdLong()));

                DateTimeFormatter format = DateTimeFormatter.ofPattern("MMM dd, HH:mm", Locale.ENGLISH);
                saveUsersTimezonesToFile(respond, ":white_check_mark: Your timezone was saved as **" + timezoneParam + "**.\n" +
                        "The current time in this timezone is **" + localNow.format(format) + "**. " +
                        "If this does not match your local time, type `/detect_timezone` to find the right one.\n\n" +
                        getRoleUpdateMessage(member.getGuild(), member,
                                "It may take some time for the timezone role to show up, as they are updated every 15 minutes.",
                                "Your role will be assigned within 15 minutes once this is done."));
            } catch (DateTimeException ex) {
                // ZoneId.of blew up so the timezone is probably invalid.
                logger.warn("Could not parse timezone " + timezoneParam, ex);
                respond.accept(":x: The given timezone was not recognized.\n" +
                        "To figure out your timezone, visit https://max480-random-stuff.appspot.com/detect-timezone.html");
            }
        }

        if (command.equals("remove_timezone")) {
            // find the user's timezone.
            UserTimezone userTimezone = userTimezones.stream()
                    .filter(u -> u.serverId == member.getGuild().getIdLong() && u.userId == member.getIdLong())
                    .findFirst().orElse(null);

            if (userTimezone != null) {
                String error = getRoleUpdateMessage(member.getGuild(), member, null, "You will be able to remove your timezone role once this is done.");
                if (error != null) {
                    // since the command involves removing the roles **now**, we can't do it at all if there are permission issues!
                    respond.accept(error.replace(":warning:", ":x:"));
                    return;
                }

                // remove all timezone roles from the user.
                Guild server = member.getGuild();
                for (Role userRole : member.getRoles()) {
                    if (getTimezoneOffsetRolesForGuild(server).containsValue(userRole.getIdLong())) {
                        logger.info("Removing timezone role {} from {}", userRole, member);
                        memberCache.remove(getMemberWithCache(server, member.getIdLong()));
                        server.removeRoleFromMember(member, userRole).reason("User used /remove_timezone").complete();
                    }
                }

                // forget the user timezone and write it to disk.
                userTimezones.remove(userTimezone);
                saveUsersTimezonesToFile(respond, ":white_check_mark: Your timezone role has been removed.");
            } else {
                // user asked for their timezone to be forgotten, but doesn't have a timezone to start with :thonk:
                respond.accept(":x: You don't currently have a timezone role!");
            }
        }

        if (command.equals("toggle_times") && (member.hasPermission(Permission.ADMINISTRATOR)
                || member.hasPermission(Permission.MANAGE_SERVER))) {

            // add or remove the server in the list, depending on the previous status.
            long guildId = member.getGuild().getIdLong();
            boolean newValue = !serversWithTime.contains(guildId);
            if (newValue) {
                serversWithTime.add(guildId);
            } else {
                serversWithTime.remove(guildId);
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(SERVERS_WITH_TIME_FILE_NAME))) {
                for (Long server : serversWithTime) {
                    writer.write(server + "\n");
                }

                respond.accept(":white_check_mark: " + (newValue ?
                        "The timezone roles will now show the time it is in the timezone." :
                        "The timezone roles won't show the time it is in the timezone anymore.") + "\n" +
                        getRoleUpdateMessage(member.getGuild(), member,
                                "It may take some time for the roles to update, as they are updated every 15 minutes.",
                                "The roles will be updated within 15 minutes once this is done."));
            } catch (IOException e) {
                // I/O error while saving to disk??
                logger.error("Error while writing file", e);
                respond.accept(":x: A technical error occurred.");
            }
        }

        if (command.equals("discord_timestamp") && dateTimeParam != null) {
            // find the user's timezone.
            String timezoneName = userTimezones.stream()
                    .filter(u -> u.serverId == member.getGuild().getIdLong() && u.userId == member.getIdLong())
                    .findFirst().map(timezone -> timezone.timezoneName).orElse(null);

            // if the user has no timezone role, we want to use UTC instead!
            String timezoneToUse = timezoneName == null ? "UTC" : timezoneName;

            // take the given date time with the user's timezone (or UTC), then turn it into a timestamp.
            // we are going to attempt 4 different formats.
            Long timestamp = null;
            try {
                // format 1: date time with seconds
                timestamp = LocalDateTime.parse(dateTimeParam, DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss"))
                        .atZone(ZoneId.of(timezoneToUse)).toEpochSecond();
            } catch (DateTimeParseException e1) {
                try {
                    // format 2: date time without seconds
                    timestamp = LocalDateTime.parse(dateTimeParam, DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm")).withSecond(0)
                            .atZone(ZoneId.of(timezoneToUse)).toEpochSecond();
                } catch (DateTimeException e2) {

                    try {
                        // format 3: time only with seconds
                        timestamp = LocalTime.parse(dateTimeParam, DateTimeFormatter.ofPattern("H:mm:ss"))
                                .atDate(LocalDate.now(ZoneId.of(timezoneToUse)))
                                .atZone(ZoneId.of(timezoneToUse)).toEpochSecond();
                    } catch (DateTimeException e3) {
                        try {
                            // format 4: time only without seconds
                            timestamp = LocalTime.parse(dateTimeParam, DateTimeFormatter.ofPattern("H:mm")).withSecond(0)
                                    .atDate(LocalDate.now(ZoneId.of(timezoneToUse)))
                                    .atZone(ZoneId.of(timezoneToUse)).toEpochSecond();
                        } catch (DateTimeException e4) {
                            // none of the 4 formats matched!
                            logger.warn("Could not parse date time {}", dateTimeParam);
                            respond.accept(":x: The date you gave could not be parsed!\nMake sure you followed the format `YYYY-MM-dd hh:mm:ss`. " +
                                    "For example: `2020-10-01 15:42:00`\nYou can omit the date if you want today, and the seconds if you don't need that.");
                        }
                    }
                }
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
                                SelectionMenu.create("discord_timestamp")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH)), "`<t:" + timestamp + ":t>`")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH)), "`<t:" + timestamp + ":T>`")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH)), "`<t:" + timestamp + ":d>`")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)), "`<t:" + timestamp + ":D>`")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("MMMM d, yyyy H:mm a", Locale.ENGLISH)), "`<t:" + timestamp + ":f>`")
                                        .addOption(time.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy H:mm a", Locale.ENGLISH)), "`<t:" + timestamp + ":F>`")
                                        .addOption("Relative to now", "`<t:" + timestamp + ":R>`")
                                        .build()
                        ))
                        .build());
            }
        }

        if (command.equals("time_for") && memberParam != null) {
            // find the target user's timezone.
            String timezoneName = userTimezones.stream()
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

        if (command.equals("toggle_times") && isSlashCommand && !member.hasPermission(Permission.ADMINISTRATOR)
                && !member.hasPermission(Permission.MANAGE_SERVER)) {

            // we should always respond to slash commands!
            respond.accept("You must have the Administrator or Manage Server permission to use this!");
        }
    }

    /**
     * Checks that everything is fine with the server before answering to /timezone and /toggle_times.
     *
     * @param server  The server the slash command was sent in
     * @param caller  The member that sent the command
     * @param success The message to return in case of success
     * @param failure The message to append in case of failure
     * @return The message to send to the user, either the "success" parameter or an explanation of how to solve
     * the issue with the server settings, with "failure" appended
     */
    private static String getRoleUpdateMessage(Guild server, Member caller, String success, String failure) {
        if (!server.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
            // bot can't manage roles
            return "\n:warning: Please " + (caller.hasPermission(Permission.ADMINISTRATOR) ? "" : "tell an admin to ")
                    + "grant the **Manage Roles** permission to the bot, so that it can "
                    + "create and assign timezone roles. " + failure;
        } else if (getTimezoneOffsetRolesForGuild(server).values().stream()
                .anyMatch(roleId -> !server.getSelfMember().canInteract(server.getRoleById(roleId)))) {

            // bot has a lower top role than one of the timezone roles
            return "\n:warning: Please " + (caller.hasPermission(Permission.ADMINISTRATOR) ? "" : "tell an admin to ")
                    + "ensure that the Timezone Bot is higher in the role list than all timezone roles, so that it has "
                    + "the permission to manage and assign them. " + failure;
        }
        return success;
    }

    public void run() {
        while (true) {
            try {
                boolean usersDeleted = false;

                for (Guild server : jda.getGuilds()) {
                    logger.debug("=== Refreshing timezones for server {}", server);
                    if (!server.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
                        logger.debug("I can't manage roles here! Skipping.");
                        continue;
                    }
                    if (getTimezoneOffsetRolesForGuild(server).values().stream()
                            .anyMatch(roleId -> !server.getSelfMember().canInteract(server.getRoleById(roleId)))) {

                        logger.debug("I can't manage all timezone roles here! Skipping.");
                        continue;
                    }

                    usersDeleted = updateTimezoneRolesInServer(server) || usersDeleted;
                }

                housekeep(usersDeleted);

                jda.getPresence().setActivity(Activity.playing("/timezone | " +
                        jda.getGuilds().stream().mapToInt(g -> getTimezoneOffsetRolesForGuild(g).size()).sum() + " roles | " +
                        userTimezones.stream().map(u -> u.userId).distinct().count() + " users | " +
                        jda.getGuilds().size() + " servers"));

                TopGGCommunicator.refresh(jda);
            } catch (Exception e) {
                logger.error("Refresh roles failed", e);

                try {
                    jda.getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                            .sendMessage("An error occurred while refreshing roles: " + e).queue();
                } catch (Exception e2) {
                    logger.error("Alerting failed", e2);
                }
            }

            try {
                logger.debug("Done! Sleeping.");
                // wait until the clock hits a time divisible by 15
                do {
                    // sleep to the start of the next minute
                    Thread.sleep(60000 - (ZonedDateTime.now().getSecond() * 1000
                            + ZonedDateTime.now().getNano() / 1_000_000) + 50);

                    if (ZonedDateTime.now().getMinute() % 15 == 14) {
                        // diagnostics: check how long it took to update all roles
                        logger.debug("Last role was updated on {}", lastRoleUpdateDate);
                    }
                } while (ZonedDateTime.now().getMinute() % 15 != 0);
            } catch (InterruptedException e) {
                logger.error("Sleep interrupted(???)", e);
            }
        }
    }

    /**
     * Updates all timezone roles in the given server:
     * - creating roles if people need a role that doesn't exist yet
     * - deleting roles if nobody has it anymore
     * - adding or deleting roles to users appropriately
     * - renaming roles as time passes
     *
     * @param server The server to update
     * @return whether users were deleted or not
     */
    private boolean updateTimezoneRolesInServer(Guild server) {
        boolean usersDeleted = false;
        final long guildId = server.getIdLong();

        // user-timezone couples for this server
        Map<Long, String> userTimezonesThisServer = userTimezones.stream()
                .filter(s -> s.serverId == guildId)
                .collect(Collectors.toMap(s -> s.userId, s -> s.timezoneName));
        Map<Integer, Long> timezoneOffsetRolesThisServer = getTimezoneOffsetRolesForGuild(server);

        // timezones no one has anymore (existing timezones will be removed from the set as it goes)
        Set<Integer> obsoleteTimezones = new HashSet<>(timezoneOffsetRolesThisServer.keySet());

        Set<Long> obsoleteUsers = new HashSet<>(); // users that left the server
        List<Role> existingRoles = new ArrayList<>(server.getRoles()); // all server roles

        for (Map.Entry<Long, String> timezone : userTimezonesThisServer.entrySet()) {
            CachedMember member = getMemberWithCache(server, timezone.getKey());
            if (member == null) {
                // user was not found, they probably left.
                obsoleteUsers.add(timezone.getKey());
            } else {
                // convert the zone to an UTC offset in minutes.
                ZoneId zone = ZoneId.of(timezone.getValue());
                ZonedDateTime now = ZonedDateTime.now(zone);
                int offset = now.getOffset().getTotalSeconds() / 60;

                // mark this timezone as used.
                obsoleteTimezones.remove(offset);

                Role targetRole;
                if (timezoneOffsetRolesThisServer.containsKey(offset)) {
                    // role already exists!
                    targetRole = existingRoles.stream().filter(role -> role.getIdLong() == timezoneOffsetRolesThisServer.get(offset))
                            .findFirst().orElseThrow(() -> new RuntimeException("Managed role for " + offset + " somehow disappeared, send help"));
                } else {
                    // we need to create a new timezone role for this user.
                    // it will be created with a throw-away name
                    logger.info("Creating role for timezone offset {}", offset);
                    targetRole = server.createRole().setName("timezone role for " + offset).setPermissions(0L)
                            .reason("User has non currently existing timezone " + offset).complete();
                    existingRoles.add(targetRole);
                    timezoneOffsetRolesThisServer.put(offset, targetRole.getIdLong());
                }

                boolean userHasCorrectRole = false;
                for (long roleId : member.roleIds) {
                    if (roleId != targetRole.getIdLong() && timezoneOffsetRolesThisServer.values().stream().anyMatch(l -> l == roleId)) {
                        // the user has a timezone role that doesn't match their timezone!
                        Role serverRole = server.getRoleById(roleId);
                        logger.info("Removing timezone role {} from {}", serverRole, member);
                        memberCache.remove(member);

                        Member memberDiscord = getMemberForReal(member);
                        if (memberDiscord != null && serverRole != null && memberDiscord.getRoles().contains(serverRole)) {
                            server.removeRoleFromMember(memberDiscord, serverRole).reason("Timezone of user changed to " + offset).complete();
                        } else {
                            logger.warn("Member left, does not have the role, or the role is gone!");
                        }
                    } else if (roleId == targetRole.getIdLong()) {
                        // this is the role the user is supposed to have.
                        userHasCorrectRole = true;
                    }
                }

                if (!userHasCorrectRole) {
                    // the user doesn't have the timezone role they're supposed to have!
                    logger.info("Adding timezone role {} to {}", targetRole, member);
                    memberCache.remove(member);

                    Member memberDiscord = getMemberForReal(member);
                    if (memberDiscord != null && !memberDiscord.getRoles().contains(targetRole)) {
                        server.addRoleToMember(memberDiscord, targetRole).reason("Timezone of user changed to " + offset).queue();
                    } else {
                        logger.warn("Member left or already has the role!");
                    }
                }
            }
        }

        // forget timezones for users that left.
        for (long user : obsoleteUsers) {
            logger.info("Removing user {}", user);
            userTimezonesThisServer.remove(user);
            userTimezones.stream()
                    .filter(u -> u.serverId == guildId && u.userId == user)
                    .findFirst().map(u -> userTimezones.remove(u));
            usersDeleted = true;
        }

        // delete timezone roles that are assigned to no-one.
        for (int timezone : obsoleteTimezones) {
            existingRoles.stream().filter(role -> role.getIdLong() == timezoneOffsetRolesThisServer.get(timezone))
                    .findFirst()
                    .map(role -> {
                        logger.info("Removing role {}", role);
                        role.delete().reason("Nobody has this role anymore").queue();
                        return role;
                    })
                    .orElseThrow(() -> new RuntimeException("Managed role for " + timezone + " somehow disappeared, send help"));

            timezoneOffsetRolesThisServer.remove(timezone);
        }

        // update the remaining roles!
        for (Map.Entry<Integer, Long> timezoneRoles : timezoneOffsetRolesThisServer.entrySet()) {
            int zoneOffset = timezoneRoles.getKey();
            Role role = existingRoles.stream().filter(r -> r.getIdLong() == timezoneRoles.getValue())
                    .findFirst().orElseThrow(() -> new RuntimeException("Managed role for " + zoneOffset + " somehow disappeared, send help"));

            // build an offset "timezone" (UTC-06:30 for example)
            int hours = zoneOffset / 60;
            int minutes = Math.abs(zoneOffset) % 60;
            DecimalFormat twoDigits = new DecimalFormat("00");
            String timezoneOffsetFormatted = "UTC" + (hours < 0 ? "-" : "+") + twoDigits.format(Math.abs(hours)) + ":" + twoDigits.format(minutes);

            // get the date at this timezone
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timezoneOffsetFormatted));

            // build the final role name, and update the role if the name doesn't match.
            String roleName = "Timezone " + timezoneOffsetFormatted +
                    (serversWithTime.contains(guildId) ? " (" + now.format(DateTimeFormatter.ofPattern("ha")).toLowerCase(Locale.ROOT) + ")" : "");
            if (!roleName.equals(role.getName())) {
                role.getManager().setName(roleName).reason("Time passed").queue(success -> lastRoleUpdateDate = ZonedDateTime.now());
                logger.debug("Timezone role renamed for offset {}: {} -> {}", zoneOffset, role, roleName);
            }
        }

        return usersDeleted;
    }

    /**
     * Cleans up after every timezone role update:
     * - users that left should be deleted
     * - once a day, part of the cache should be deleted to check if the cached situation is still up-to-date
     *
     * @param usersDeleted whether users were deleted during the role updating part
     * @throws IOException in case an error occurs when writing to disk
     */
    private void housekeep(boolean usersDeleted) throws IOException {
        // remove settings for users that left
        List<UserTimezone> toDelete = new ArrayList<>();
        for (UserTimezone userTimezone : userTimezones) {
            if (jda.getGuilds().stream().noneMatch(g -> g.getIdLong() == userTimezone.serverId)) {
                logger.warn("Removing user {} belonging to non-existing server", userTimezone);
                toDelete.add(userTimezone);
                usersDeleted = true;
            }
        }
        userTimezones.removeAll(toDelete);

        // remove users that left or don't have settings from the cache
        for (CachedMember memberCache : new ArrayList<>(memberCache)) {
            if (jda.getGuilds().stream().noneMatch(g -> g.getIdLong() == memberCache.serverId)) {
                logger.warn("Removing user {} from cache belonging to non-existing server", memberCache);
                TimezoneBot.memberCache.remove(memberCache);
            } else if (userTimezones.stream().noneMatch(u -> u.serverId == memberCache.serverId && u.userId == memberCache.memberId)) {
                logger.warn("Removing user {} from cache because they are not a bot user", memberCache);
                TimezoneBot.memberCache.remove(memberCache);
            }
        }

        if (usersDeleted) {
            // save the new list, after users were deleted, to disk.
            saveUsersTimezonesToFile(null, null);
        }

        // write the member cache to disk
        try (ObjectOutputStream output = new ObjectOutputStream(new FileOutputStream(MEMBER_CACHE_NAME))) {
            output.writeObject(memberCache);
        }

        if (ZonedDateTime.now().getHour() == 0 && ZonedDateTime.now().getMinute() == 0) {
            // midnight housekeeping: remove a chunk of users from the cache, in order to check they're still alive.
            logger.debug("Housekeeping is kicking off!");
            for (int i = 0; i < 1000; i++) {
                if (!memberCache.isEmpty()) {
                    memberCache.remove(0);
                }
            }
        }
    }

    /**
     * Gets a member from the cache, or retrieve it if they are not in the cache.
     *
     * @param g        The guild the member is part of
     * @param memberId The member to retrieve
     * @return The cached entry that was retrieved from cache or loaded from Discord
     */
    private CachedMember getMemberWithCache(Guild g, long memberId) {
        return memberCache.stream()
                .filter(m -> m.serverId == g.getIdLong() && m.memberId == memberId)
                .findFirst()
                .orElseGet(() -> {
                    try {
                        // user is not cached! :a:
                        Map<Integer, Long> timezoneRoles = getTimezoneOffsetRolesForGuild(g);

                        // download the user
                        Member m = g.retrieveMemberById(memberId).complete();

                        // build the cache entry, only keeping roles that correspond to timezones
                        CachedMember cached = new CachedMember(g.getIdLong(), memberId,
                                m.getRoles().stream()
                                        .map(Role::getIdLong)
                                        .filter(timezoneRoles::containsValue)
                                        .collect(Collectors.toCollection(ArrayList::new)));

                        // add it to the cache and return it
                        memberCache.add(cached);
                        return cached;
                    } catch (ErrorResponseException error) {
                        logger.warn("Got error when trying to get member {} in guild {}", memberId, g, error);

                        if (error.isServerError()) throw error;

                        // user is gone?
                        return null;
                    }
                });
    }

    /**
     * Gets the actual Member object from Discord for a MemberCache object.
     *
     * @param member The cached member object
     * @return The actual member object from Discord
     */
    private Member getMemberForReal(CachedMember member) {
        try {
            return jda.getGuildById(member.serverId).retrieveMemberById(member.memberId).complete();
        } catch (ErrorResponseException error) {
            logger.warn("Got error when trying to get member {}", memberCache, error);

            if (error.isServerError()) throw error;

            // user is gone?
            return null;
        }
    }

    /**
     * Saves the user timezones to disk, then sends a message depending on success or failure to the user.
     * Both parameters can be null if no message should be sent.
     *
     * @param respond The method that should be called to respond to the user
     * @param success The message to send in case of success
     */
    private void saveUsersTimezonesToFile(Consumer<Object> respond, String success) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(SAVE_FILE_NAME))) {
            for (UserTimezone entry : userTimezones) {
                writer.write(entry.serverId + ";" + entry.userId + ";" + entry.timezoneName + "\n");
            }

            if (respond != null) respond.accept(success);
        } catch (IOException e) {
            // I/O error while saving to disk??
            logger.error("Error while writing file", e);
            if (respond != null) respond.accept(":x: A technical error occurred.");
        }
    }

    /**
     * Replaces the command list. Usually not called, but the bot can be run once with this method called
     * to add, update or delete commands.
     */
    private static void registerSlashCommands() {
        // register the slash commands, then assign per-guild permissions for /toggle_times.
        // all commands have defaultEnabled = false to disable them in DMs.
        jda.updateCommands()
                .addCommands(new CommandData("timezone", "Configures your timezone role")
                        .addOption(OptionType.STRING, "tz_name", "Timezone name, use /detect_timezone to figure it out", true)
                        .setDefaultEnabled(false))
                .addCommands(new CommandData("detect_timezone", "Sets up or replaces your timezone role")
                        .setDefaultEnabled(false))
                .addCommands(new CommandData("remove_timezone", "Removes your timezone role")
                        .setDefaultEnabled(false))
                .addCommands(new CommandData("discord_timestamp", "Gives a Discord timestamp, to tell a date/time to other people regardless of their timezone")
                        .addOption(OptionType.STRING, "date_time", "Date and time to convert (format: YYYY-MM-DD hh:mm:ss)", true)
                        .setDefaultEnabled(false))
                .addCommands(new CommandData("time_for", "Gives the time it is now for another member of the server")
                        .addOption(OptionType.USER, "member", "The member you want to get the time of", true)
                        .setDefaultEnabled(false))
                .addCommands(new CommandData("toggle_times", "[Admin] Switches on/off whether to show the time it is in timezone roles")
                        .setDefaultEnabled(false))
                .queue(success -> updateToggleTimesPermsForGuilds(jda.getGuilds()));
    }


    /**
     * Makes sure the permissions for /toggle_times are properly set for the given servers:
     * any role with the Admin or Manage Server permission + the owner if they don't have any of the roles should
     * be able to use the command.
     * <p>
     * If more than 10 overrides are necessary for that, the command will be open to everyone since Discord doesn't allow
     * more than 10 overrides, but non-admins will receive an error when calling it.
     *
     * @param guilds The servers to check
     */
    private static void updateToggleTimesPermsForGuilds(List<Guild> guilds) {
        jda.retrieveCommands().queue(commands -> {
            Command timezone = commands.stream().filter(c -> c.getName().equals("timezone")).findFirst().orElse(null);
            Command detectTimezone = commands.stream().filter(c -> c.getName().equals("detect_timezone")).findFirst().orElse(null);
            Command removeTimezone = commands.stream().filter(c -> c.getName().equals("remove_timezone")).findFirst().orElse(null);
            Command discordTimestamp = commands.stream().filter(c -> c.getName().equals("discord_timestamp")).findFirst().orElse(null);
            Command timeFor = commands.stream().filter(c -> c.getName().equals("time_for")).findFirst().orElse(null);
            Command toggleTimes = commands.stream().filter(c -> c.getName().equals("toggle_times")).findFirst().orElse(null);

            for (Guild g : guilds) {
                // figure out which roles in the guild have Admin or Manage Server.
                List<Role> rolesWithPerms = new ArrayList<>();
                for (Role r : g.getRoles()) {
                    if (!r.isManaged() && (r.hasPermission(Permission.ADMINISTRATOR) || r.hasPermission(Permission.MANAGE_SERVER))) {
                        rolesWithPerms.add(r);
                    }
                }

                // turn them into privilege objects.
                List<CommandPrivilege> privileges = rolesWithPerms.stream()
                        .map(r -> new CommandPrivilege(CommandPrivilege.Type.ROLE, true, r.getIdLong()))
                        .collect(Collectors.toCollection(ArrayList::new));

                // add the guild's owner to the list, because under Discord rules they're still an admin even if they
                // don't have any role giving them the admin permission.
                privileges.add(new CommandPrivilege(CommandPrivilege.Type.USER, true, g.getOwnerIdLong()));

                List<CommandPrivilege> allowEveryone = Collections.singletonList(new CommandPrivilege(CommandPrivilege.Type.ROLE, true, g.getPublicRole().getIdLong()));

                Map<String, Collection<? extends CommandPrivilege>> listPrivileges = new HashMap<>();
                listPrivileges.put(timezone.getId(), allowEveryone);
                listPrivileges.put(detectTimezone.getId(), allowEveryone);
                listPrivileges.put(removeTimezone.getId(), allowEveryone);
                listPrivileges.put(discordTimestamp.getId(), allowEveryone);
                listPrivileges.put(timeFor.getId(), allowEveryone);

                if (privileges.size() > 10) {
                    // this is more overrides than Discord allows! so just allow everyone to use the command,
                    // non-admins will get an error message if they try anyway.
                    logger.info("{} has too many privileges that qualify for /toggle_times ({} > 10 max), allowing everyone!", g, privileges.size());
                    listPrivileges.put(toggleTimes.getId(), allowEveryone);
                    g.updateCommandPrivileges(listPrivileges).queue();
                } else {
                    logger.info("The following entities have access to /toggle_times in {}: roles {}, owner with id {}", g, rolesWithPerms, g.getOwnerIdLong());
                    listPrivileges.put(toggleTimes.getId(), privileges);
                    g.updateCommandPrivileges(listPrivileges).queue();
                }
            }
        });
    }
}
