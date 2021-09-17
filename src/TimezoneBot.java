package com.max480.discord.randombots;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
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

    private static List<UserTimezone> userTimezones; // user ID > timezone name
    private static List<TimezoneOffsetRole> timezoneOffsetRoles; // UTC offset in minutes > role ID
    private static Set<Long> serversWithTime; // servers that want times in timezone roles
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
        jda = JDABuilder.create(SecretConstants.TIMEZONE_BOT_TOKEN, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MEMBERS)
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

        // look up existing timezone roles by regex.
        Pattern roleName = Pattern.compile("^Timezone UTC([+-][0-9][0-9]):([0-9][0-9])(?: \\([0-2]?[0-9][ap]m\\))?$");
        timezoneOffsetRoles = new ArrayList<>();
        for (Guild g : jda.getGuilds()) {
            final long guildId = g.getIdLong();
            g.getRoles().stream()
                    .filter(role -> roleName.matcher(role.getName()).matches())
                    .forEach(role -> {
                        // parse the UTC offset.
                        Matcher nameMatch = roleName.matcher(role.getName());
                        nameMatch.matches();
                        int hours = Integer.parseInt(nameMatch.group(1));
                        int minutes = Integer.parseInt(nameMatch.group(2));
                        if (hours < 0) minutes *= -1;

                        timezoneOffsetRoles.add(new TimezoneOffsetRole(guildId, hours * 60 + minutes, role.getIdLong()));
                    });
        }

        logger.debug("Users by timezone = {}, roles by timezone = {}, servers with time = {}", userTimezones, timezoneOffsetRoles, serversWithTime);

        // start the background process to update users' roles.
        new Thread(new TimezoneBot()).start();
    }

    // let the owner know when the bot joins or leaves servers
    @Override
    public void onGuildJoin(@NotNull GuildJoinEvent event) {
        event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                .sendMessage("I just joined a new server: " + event.getGuild().getName()).queue();
    }

    @Override
    public void onGuildLeave(@NotNull GuildLeaveEvent event) {
        event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                .sendMessage("I just left a server: " + event.getGuild().getName()).queue();
    }

    @Override
    public void onGuildMessageReceived(@Nonnull GuildMessageReceivedEvent event) {
        String message = event.getMessage().getContentRaw().trim();
        processMessage(event.getAuthor(), event.getChannel(), event.getMember(), message);
    }

    private void processMessage(User user, TextChannel channel, Member member, String message) {
        if (!user.isBot() && message.equals("!timezone")) {
            // print help
            channel.sendMessage("Usage: `!timezone [tzdata timezone name]` (example: `!timezone Europe/Paris`)\n" +
                    "To figure out your timezone, visit https://max480-random-stuff.appspot.com/detect-timezone.html\n" +
                    "If you don't want to share your timezone name, you can use an offset like UTC+8" +
                    " (it won't automatically adjust with daylight saving though).\n\n" +
                    "If you want to get rid of your timezone role, use `!remove_timezone`.").queue();
        }

        if (!user.isBot() && message.startsWith("!timezone ")) {
            try {
                // check that the timezone is valid by passing it to ZoneId.of and discarding the result.
                String zoneName = message.substring(10).trim();
                ZoneId.of(zoneName);

                // remove old link if there is any.
                userTimezones.stream()
                        .filter(u -> u.serverId == channel.getGuild().getIdLong() && u.userId == user.getIdLong())
                        .findFirst()
                        .map(u -> userTimezones.remove(u));

                // save the link, both in userTimezones and on disk.
                userTimezones.add(new UserTimezone(channel.getGuild().getIdLong(), user.getIdLong(), zoneName));
                logger.info("User {} now has timezone {}", user.getIdLong(), zoneName);
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(SAVE_FILE_NAME))) {
                    for (UserTimezone entry : userTimezones) {
                        writer.write(entry.serverId + ";" + entry.userId + ";" + entry.timezoneName + "\n");
                    }

                    channel.sendMessage(":white_check_mark: Your timezone was saved as **" + zoneName + "**.\n" +
                            "It may take some time for the timezone role to show up, as they are updated every 15 minutes.").queue();
                } catch (IOException e) {
                    // I/O error while saving to disk??
                    logger.error("Error while writing file", e);
                    channel.sendMessage(":x: A technical error occurred.").queue();
                }
            } catch (DateTimeException ex) {
                // ZoneId.of blew up so the timezone is probably invalid.
                logger.info("Could not parse timezone from command " + message, ex);
                channel.sendMessage(":x: The given timezone was not recognized.\n" +
                        "To figure out your timezone, visit https://max480-random-stuff.appspot.com/detect-timezone.html").queue();
            }
        }

        if (!user.isBot() && message.equals("!remove_timezone")) {
            UserTimezone userTimezone = userTimezones.stream()
                    .filter(u -> u.serverId == channel.getGuild().getIdLong() && u.userId == user.getIdLong())
                    .findFirst().orElse(null);

            if (userTimezone != null) {
                // remove all timezone roles from the user.
                Guild server = channel.getGuild();
                for (Role userRole : member.getRoles()) {
                    if (timezoneOffsetRoles.stream().anyMatch(l -> l.serverId == server.getIdLong() && l.roleId == userRole.getIdLong())) {
                        logger.info("Removing timezone role {} from {}", userRole, member);
                        server.removeRoleFromMember(member, userRole).reason("User used !remove_timezone").complete();
                    }
                }

                // forget the user timezone and write it to disk.
                userTimezones.remove(userTimezone);
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(SAVE_FILE_NAME))) {
                    for (UserTimezone entry : userTimezones) {
                        writer.write(entry.serverId + ";" + entry.userId + ";" + entry.timezoneName + "\n");
                    }

                    channel.sendMessage(":white_check_mark: Your timezone role has been removed.").queue();
                } catch (IOException e) {
                    // I/O error while saving to disk??
                    logger.error("Error while writing file", e);
                    channel.sendMessage(":x: A technical error occurred.").queue();
                }
            } else {
                // user asked for their timezone to be forgotten, but doesn't have a timezone to start with :thonk:
                channel.sendMessage(":x: You don't currently have a timezone role!").queue();
            }
        }

        if (!user.isBot() && message.equals("!toggle_times") && (member.hasPermission(Permission.ADMINISTRATOR)
                || member.hasPermission(Permission.MANAGE_SERVER))) {

            long guildId = channel.getGuild().getIdLong();
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

                channel.sendMessage(":white_check_mark: " + (newValue ?
                        "The timezone roles will now show the time it is in the timezone." :
                        "The timezone roles won't show the time it is in the timezone anymore.") + "\n" +
                        "It may take some time for the roles to update, as they are updated every 15 minutes.").queue();
            } catch (IOException e) {
                // I/O error while saving to disk??
                logger.error("Error while writing file", e);
                channel.sendMessage(":x: A technical error occurred.").queue();
            }
        }

    }

    public void run() {
        while (true) {
            try {
                boolean usersDeleted = false;

                for (Guild server : jda.getGuilds()) {
                    logger.info("=== Refreshing timezones for server {}", server);
                    final long guildId = server.getIdLong();

                    // timezones no one has anymore
                    Set<Integer> obsoleteTimezones = timezoneOffsetRoles.stream()
                            .filter(s -> s.serverId == guildId)
                            .map(s -> s.utcOffsetMinutes).collect(Collectors.toSet());

                    // user-timezone couples for this server
                    Map<Long, String> userTimezonesThisServer = userTimezones.stream()
                            .filter(s -> s.serverId == guildId)
                            .collect(Collectors.toMap(s -> s.userId, s -> s.timezoneName));
                    Map<Integer, Long> timezoneOffsetRolesThisServer = timezoneOffsetRoles.stream()
                            .filter(s -> s.serverId == guildId)
                            .collect(Collectors.toMap(s -> s.utcOffsetMinutes, s -> s.roleId));

                    Set<Long> obsoleteUsers = new HashSet<>(); // users that left the server
                    List<Role> existingRoles = new ArrayList<>(server.getRoles()); // all server roles

                    for (Map.Entry<Long, String> timezone : userTimezonesThisServer.entrySet()) {
                        Member member = server.getMemberById(timezone.getKey());
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
                                timezoneOffsetRoles.add(new TimezoneOffsetRole(guildId, offset, targetRole.getIdLong()));
                            }

                            boolean userHasCorrectRole = false;
                            for (Role userRole : member.getRoles()) {
                                if (userRole.getIdLong() != targetRole.getIdLong() && timezoneOffsetRolesThisServer.values().stream().anyMatch(l -> l == userRole.getIdLong())) {
                                    // the user has a timezone role that doesn't match their timezone!
                                    logger.info("Removing timezone role {} from {}", userRole, member);
                                    server.removeRoleFromMember(member, userRole).reason("Timezone of user changed to " + offset).complete();
                                } else if (userRole.getIdLong() == targetRole.getIdLong()) {
                                    // this is the role the user is supposed to have.
                                    userHasCorrectRole = true;
                                }
                            }

                            if (!userHasCorrectRole) {
                                // the user doesn't have the timezone role they're supposed to have!
                                logger.info("Adding timezone role {} to {}", targetRole, member);
                                server.addRoleToMember(member, targetRole).reason("Timezone of user changed to " + offset).queue();
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
                        timezoneOffsetRoles.stream()
                                .filter(t -> t.serverId == guildId && t.utcOffsetMinutes == timezone)
                                .findFirst().map(t -> timezoneOffsetRoles.remove(t));
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

                jda.getPresence().setActivity(Activity.playing("!timezone | " + timezoneOffsetRoles.size() + " roles | " +
                        userTimezones.stream().map(u -> u.userId).distinct().count() + " users | " + jda.getGuilds().size() + " servers"));

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
