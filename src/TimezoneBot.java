package com.max480.discord.randombots;

import com.google.common.collect.ImmutableMap;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.privileges.CommandPrivilege;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TimezoneBot extends ListenerAdapter implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(TimezoneBot.class);

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

    private static class TimezoneOffsetRole {
        private final long serverId;
        private final int utcOffsetMinutes;
        private final long roleId;

        public TimezoneOffsetRole(long serverId, int utcOffsetMinutes, long roleId) {
            this.serverId = serverId;
            this.utcOffsetMinutes = utcOffsetMinutes;
            this.roleId = roleId;
        }

        @Override
        public String toString() {
            return "TimezoneOffsetRole{" +
                    "serverId=" + serverId +
                    ", utcOffsetMinutes=" + utcOffsetMinutes +
                    ", roleId=" + roleId +
                    '}';
        }
    }

    private static class MemberCache {
        private final long serverId;
        private final long memberId;
        private final List<Long> roleIds;

        public MemberCache(long serverId, long memberId, List<Long> roleIds) {
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

    /**
     * Gets a member from the cache, or retrieve it if they are not in the cache.
     *
     * @param g        The guild the member is part of
     * @param memberId The member to retrieve
     * @return The cached entry that was retrieved from cache or loaded from Discord
     */
    private MemberCache getMemberWithCache(Guild g, long memberId) {
        return membersCached.stream()
                .filter(m -> m.serverId == g.getIdLong() && m.memberId == memberId)
                .findFirst()
                .orElseGet(() -> {
                    try {
                        Member m = g.retrieveMemberById(memberId).complete();
                        MemberCache cached = new MemberCache(g.getIdLong(), memberId, m.getRoles().stream().map(Role::getIdLong).collect(Collectors.toList()));
                        membersCached.add(cached);
                        logger.debug("Cache miss for member {} => adding {} to cache", m, cached);
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
    private Member getMemberForReal(MemberCache member) {
        try {
            logger.debug("Fetching member {} for real", member);
            return jda.getGuildById(member.serverId).retrieveMemberById(member.memberId).complete();
        } catch (ErrorResponseException error) {
            logger.warn("Got error when trying to get member {}", membersCached, error);

            if (error.isServerError()) throw error;

            // user is gone?
            return null;
        }
    }

    private static List<UserTimezone> userTimezones; // user ID > timezone name
    private static Set<Long> serversWithTime; // servers that want times in timezone roles
    private static final List<MemberCache> membersCached = new ArrayList<>();

    private static JDA jda;

    private static final String SAVE_FILE_NAME = "user_timezones.csv";
    private static final String SERVERS_WITH_TIME_FILE_NAME = "servers_with_time.txt";

    public static void main(String[] args) throws Exception {
        // load the saved users' timezones, and the "servers with time" list.
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

        // start up the bot.
        jda = JDABuilder.create(SecretConstants.TIMEZONE_BOT_TOKEN, GatewayIntent.GUILD_MESSAGES)
                .setActivity(Activity.playing("Starting up..."))
                .addEventListeners(new TimezoneBot(),
                        // some code specific to the Strawberry Jam 2021 server, not published and has nothing to do with timezones
                        // but that wasn't really enough to warrant a separate bot
                        new StrawberryJamUpdate())
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

        logger.debug("Users by timezone = {}, servers with time = {}", userTimezones, serversWithTime);

        updateToggleTimesPermsForGuilds(jda.getGuilds());

        // start the background process to update users' roles.
        new Thread(new TimezoneBot()).start();
    }

    // call this only once when the slash commands or their descriptions change.
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
                .addCommands(new CommandData("toggle_times", "Switches on/off whether to show the time it is in timezone roles")
                        .setDefaultEnabled(false))
                .queue(success -> updateToggleTimesPermsForGuilds(jda.getGuilds()));
    }

    private static void updateToggleTimesPermsForGuilds(List<Guild> guilds) {
        jda.retrieveCommands().queue(commands -> {
            Command timezone = commands.stream().filter(c -> c.getName().equals("timezone")).findFirst().orElse(null);
            Command detectTimezone = commands.stream().filter(c -> c.getName().equals("detect_timezone")).findFirst().orElse(null);
            Command removeTimezone = commands.stream().filter(c -> c.getName().equals("remove_timezone")).findFirst().orElse(null);
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

                g.retrieveOwner().queue(owner -> {
                    boolean ownerOverrideRequired = owner.getRoles().stream().noneMatch(rolesWithPerms::contains);
                    if (ownerOverrideRequired) {
                        // the owner has no admin role! but under Discord rules they're still an admin, so they need a privilege
                        privileges.add(new CommandPrivilege(CommandPrivilege.Type.USER, true, g.getOwnerIdLong()));
                    }

                    List<CommandPrivilege> allowEveryone = Collections.singletonList(new CommandPrivilege(CommandPrivilege.Type.ROLE, true, g.getPublicRole().getIdLong()));
                    if (privileges.size() > 10) {
                        logger.debug("{} has too many privileges that qualify for /toggle_times ({} > 10 max), allowing everyone!", g, privileges.size());
                        g.updateCommandPrivileges(ImmutableMap.of(
                                        timezone.getId(), allowEveryone,
                                        detectTimezone.getId(), allowEveryone,
                                        removeTimezone.getId(), allowEveryone,
                                        toggleTimes.getId(), allowEveryone))
                                .queue();
                    } else {
                        logger.debug("The following entities have access to /toggle_times in {}: roles {}{}", g, rolesWithPerms,
                                (ownerOverrideRequired ? ", owner " + owner : ""));
                        g.updateCommandPrivileges(ImmutableMap.of(
                                        timezone.getId(), allowEveryone,
                                        detectTimezone.getId(), allowEveryone,
                                        removeTimezone.getId(), allowEveryone,
                                        toggleTimes.getId(), privileges))
                                .queue();
                    }
                });
            }
        });
    }

    // let the owner know when the bot joins or leaves servers
    @Override
    public void onGuildJoin(@NotNull GuildJoinEvent event) {
        event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                .sendMessage("I just joined a new server: " + event.getGuild().getName()).queue();

        // set up privileges for the new server!
        updateToggleTimesPermsForGuilds(Collections.singletonList(event.getGuild()));
    }

    @Override
    public void onGuildLeave(@NotNull GuildLeaveEvent event) {
        event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                .sendMessage("I just left a server: " + event.getGuild().getName()).queue();
    }

    /**
     * Looks up timezone offset roles for a server by matching them by role name.
     *
     * @param g The server to retrieve offset roles for
     * @return The retrieved offset roles
     */
    private static List<TimezoneOffsetRole> getTimezoneOffsetRolesForGuild(Guild g) {
        Pattern roleName = Pattern.compile("^Timezone UTC([+-][0-9][0-9]):([0-9][0-9])(?: \\([0-2]?[0-9][ap]m\\))?$");
        final long guildId = g.getIdLong();
        return g.getRoles().stream()
                .filter(role -> roleName.matcher(role.getName()).matches())
                .map(role -> {
                    // parse the UTC offset.
                    Matcher nameMatch = roleName.matcher(role.getName());
                    nameMatch.matches();
                    int hours = Integer.parseInt(nameMatch.group(1));
                    int minutes = Integer.parseInt(nameMatch.group(2));
                    if (hours < 0) minutes *= -1;

                    return new TimezoneOffsetRole(guildId, hours * 60 + minutes, role.getIdLong());
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public void onGuildMessageReceived(@Nonnull GuildMessageReceivedEvent event) {
        String message = event.getMessage().getContentRaw().trim();

        if (!event.getAuthor().isBot() && message.startsWith("!")) {
            // for example, !timezone UTC+8 will become command = timezone and timezoneParam = UTC+8.
            // and !remove_timezone will become command = remove_timezone ana timezoneParam = null
            String command = message.substring(1);
            String timezoneParam = null;
            if (command.contains(" ")) {
                String[] split = command.split(" ", 2);
                command = split[0];
                timezoneParam = split[1];
            }

            // handle the command and respond to the same channel.
            processMessage(event.getMember(), command, timezoneParam, response -> event.getChannel().sendMessage(response + "\n\n" +
                    ":warning: Commands starting with `!` are deprecated and may be removed in the future. Use slash commands instead!").queue(), false);
        }
    }

    @Override
    public void onSlashCommand(SlashCommandEvent event) {
        if (!event.isFromGuild()) {
            // wtf??? slash commands are disabled in DMs
            event.reply("This bot is not usable in DMs!").setEphemeral(true).queue();
        } else {
            OptionMapping option = event.getOption("tz_name");
            processMessage(event.getMember(), event.getName(),
                    option == null ? null : option.getAsString(),
                    response -> event.reply(response).setEphemeral(true).queue(), true);
        }
    }

    /**
     * Processes a command, received either by message or by slash command.
     *
     * @param member         The member that sent the command
     * @param command        The command that was sent, without the ! or /
     * @param timezoneParam  The parameter passed (only /timezone takes a parameter so...)
     * @param respond        The method to call to respond to the message (either posting to the channel, or responding to the slash command)
     * @param isSlashCommand Indicates if we are using a slash command (so we should always answer)
     */
    private void processMessage(Member member, String command, String timezoneParam, Consumer<String> respond, boolean isSlashCommand) {
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
                // check that the timezone is valid by passing it to ZoneId.of and discarding the result.
                ZoneId.of(timezoneParam);

                // remove old link if there is any.
                userTimezones.stream()
                        .filter(u -> u.serverId == member.getGuild().getIdLong() && u.userId == member.getIdLong())
                        .findFirst()
                        .map(u -> userTimezones.remove(u));

                // save the link, both in userTimezones and on disk.
                userTimezones.add(new UserTimezone(member.getGuild().getIdLong(), member.getIdLong(), timezoneParam));
                logger.info("User {} now has timezone {}", member.getIdLong(), timezoneParam);
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(SAVE_FILE_NAME))) {
                    for (UserTimezone entry : userTimezones) {
                        writer.write(entry.serverId + ";" + entry.userId + ";" + entry.timezoneName + "\n");
                    }

                    respond.accept(":white_check_mark: Your timezone was saved as **" + timezoneParam + "**.\n" +
                            "It may take some time for the timezone role to show up, as they are updated every 15 minutes.");
                } catch (IOException e) {
                    // I/O error while saving to disk??
                    logger.error("Error while writing file", e);
                    respond.accept(":x: A technical error occurred.");
                }
            } catch (DateTimeException ex) {
                // ZoneId.of blew up so the timezone is probably invalid.
                logger.info("Could not parse timezone " + timezoneParam, ex);
                respond.accept(":x: The given timezone was not recognized.\n" +
                        "To figure out your timezone, visit https://max480-random-stuff.appspot.com/detect-timezone.html");
            }
        }

        if (command.equals("remove_timezone")) {
            UserTimezone userTimezone = userTimezones.stream()
                    .filter(u -> u.serverId == member.getGuild().getIdLong() && u.userId == member.getIdLong())
                    .findFirst().orElse(null);

            if (userTimezone != null) {
                // remove all timezone roles from the user.
                Guild server = member.getGuild();
                for (Role userRole : member.getRoles()) {
                    if (getTimezoneOffsetRolesForGuild(server).stream().anyMatch(l -> l.roleId == userRole.getIdLong())) {
                        logger.info("Removing timezone role {} from {}", userRole, member);
                        membersCached.remove(getMemberWithCache(server, member.getIdLong()));
                        server.removeRoleFromMember(member, userRole).reason("User used /remove_timezone").complete();
                    }
                }

                // forget the user timezone and write it to disk.
                userTimezones.remove(userTimezone);
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(SAVE_FILE_NAME))) {
                    for (UserTimezone entry : userTimezones) {
                        writer.write(entry.serverId + ";" + entry.userId + ";" + entry.timezoneName + "\n");
                    }

                    respond.accept(":white_check_mark: Your timezone role has been removed.");
                } catch (IOException e) {
                    // I/O error while saving to disk??
                    logger.error("Error while writing file", e);
                    respond.accept(":x: A technical error occurred.");
                }
            } else {
                // user asked for their timezone to be forgotten, but doesn't have a timezone to start with :thonk:
                respond.accept(":x: You don't currently have a timezone role!");
            }
        }

        if (command.equals("toggle_times") && (member.hasPermission(Permission.ADMINISTRATOR)
                || member.hasPermission(Permission.MANAGE_SERVER))) {

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
                        "It may take some time for the roles to update, as they are updated every 15 minutes.");
            } catch (IOException e) {
                // I/O error while saving to disk??
                logger.error("Error while writing file", e);
                respond.accept(":x: A technical error occurred.");
            }
        }


        if (command.equals("toggle_times") && isSlashCommand && !member.hasPermission(Permission.ADMINISTRATOR)
                && !member.hasPermission(Permission.MANAGE_SERVER)) {

            // we should always respond to slash commands!
            respond.accept("You must have the Administrator or Manage Server permission to use this!");
        }
    }

    public void run() {
        while (true) {
            try {
                boolean usersDeleted = false;

                for (Guild server : jda.getGuilds()) {
                    logger.info("=== Refreshing timezones for server {}", server);
                    if (!server.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
                        logger.warn("I can't manage roles here! Skipping.");
                        continue;
                    }

                    final long guildId = server.getIdLong();

                    // timezones no one has anymore
                    Set<Integer> obsoleteTimezones = getTimezoneOffsetRolesForGuild(server).stream()
                            .map(t -> t.utcOffsetMinutes).collect(Collectors.toSet());

                    // user-timezone couples for this server
                    Map<Long, String> userTimezonesThisServer = userTimezones.stream()
                            .filter(s -> s.serverId == guildId)
                            .collect(Collectors.toMap(s -> s.userId, s -> s.timezoneName));
                    Map<Integer, Long> timezoneOffsetRolesThisServer = getTimezoneOffsetRolesForGuild(server).stream()
                            .collect(Collectors.toMap(s -> s.utcOffsetMinutes, s -> s.roleId));

                    Set<Long> obsoleteUsers = new HashSet<>(); // users that left the server
                    List<Role> existingRoles = new ArrayList<>(server.getRoles()); // all server roles

                    for (Map.Entry<Long, String> timezone : userTimezonesThisServer.entrySet()) {
                        MemberCache member = getMemberWithCache(server, timezone.getKey());
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
                                    membersCached.remove(member);

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
                                membersCached.remove(member);

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
                            role.getManager().setName(roleName).reason("Time passed").queue();
                            logger.debug("Timezone role renamed for offset {}: {} -> {}", zoneOffset, role, roleName);
                        }
                    }
                }

                List<UserTimezone> toDelete = new ArrayList<>();
                for (UserTimezone userTimezone : userTimezones) {
                    if (jda.getGuilds().stream().noneMatch(g -> g.getIdLong() == userTimezone.serverId)) {
                        logger.info("Removing user {} belonging to non-existing server", userTimezone);
                        toDelete.add(userTimezone);
                        usersDeleted = true;
                    }
                }
                userTimezones.removeAll(toDelete);

                if (usersDeleted) {
                    // save the new list, after users were deleted, to disk.
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(SAVE_FILE_NAME))) {
                        for (UserTimezone entry : userTimezones) {
                            writer.write(entry.serverId + ";" + entry.userId + ";" + entry.timezoneName + "\n");
                        }
                        logger.info("Saved user timezones to disk");
                    }
                }

                jda.getPresence().setActivity(Activity.playing("/timezone | " +
                        jda.getGuilds().stream().mapToInt(g -> getTimezoneOffsetRolesForGuild(g).size()).sum() + " roles | " +
                        userTimezones.stream().map(u -> u.userId).distinct().count() + " users | " +
                        jda.getGuilds().size() + " servers"));

            } catch (Exception e) {
                logger.error("Refresh roles failed", e);

                try {
                    jda.getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                            .sendMessage("An error occurred while refreshing roles: " + e.toString()).queue();
                } catch (Exception e2) {
                    logger.error("Alerting failed", e2);
                }
            }

            try {
                logger.info("Done! Sleeping.");
                // wait until the clock hits a time divisible by 15
                do {
                    // sleep to the start of the next minute
                    Thread.sleep(60000 - (ZonedDateTime.now().getSecond() * 1000
                            + ZonedDateTime.now().getNano() / 1_000_000) + 50);
                } while (ZonedDateTime.now().getMinute() % 15 != 0);
            } catch (InterruptedException e) {
                logger.error("Sleep interrupted(???)", e);
            }
        }
    }
}
