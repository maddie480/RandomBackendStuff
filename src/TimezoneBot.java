package com.max480.discord.randombots;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.events.message.priv.PrivateMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
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
import java.util.stream.Stream;

public class TimezoneBot extends ListenerAdapter implements Runnable {
    private static Logger logger = LoggerFactory.getLogger(TimezoneBot.class);

    private static Map<Long, String> userTimezones; // user ID > timezone name
    private static Map<Integer, Long> timezoneOffsetRoles; // UTC offset in minutes > role ID
    private static JDA jda;

    private static final Long SERVER_ID = SecretConstants.TIMEZONE_BOT_SERVER;
    private static final String SAVE_FILE_NAME = "user_timezones.csv";

    public static void main(String[] args) throws Exception {
        // load the saved users' timezones.
        userTimezones = new HashMap<>();
        if (new File(SAVE_FILE_NAME).exists()) {
            try (Stream<String> lines = Files.lines(Paths.get(SAVE_FILE_NAME))) {
                lines.forEach(line -> userTimezones.put(Long.parseLong(line.split(";")[0]), line.split(";", 2)[1]));
            }
        }

        // start up the bot.
        jda = JDABuilder.create(SecretConstants.TIMEZONE_BOT_TOKEN,
                GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_MEMBERS)
                .addEventListeners(new TimezoneBot(),
                        // some code specific to the Strawberry Jam 2021 server, not published and has nothing to do with timezones
                        // but that wasn't really enough to warrant a separate bot
                        new StrawberryJamUpdate())
                .build().awaitReady();

        // look up existing timezone roles by regex.
        Pattern roleName = Pattern.compile("^Timezone UTC([+-][0-9][0-9]):([0-9][0-9]) \\([0-2]?[0-9][ap]m\\)$");
        timezoneOffsetRoles = new HashMap<>();
        jda.getGuildById(SERVER_ID).getRoles().stream()
                .filter(role -> roleName.matcher(role.getName()).matches())
                .forEach(role -> {
                    // parse the UTC offset.
                    Matcher nameMatch = roleName.matcher(role.getName());
                    nameMatch.matches();
                    int hours = Integer.parseInt(nameMatch.group(1));
                    int minutes = Integer.parseInt(nameMatch.group(2));
                    if (hours < 0) minutes *= -1;

                    timezoneOffsetRoles.put(hours * 60 + minutes, role.getIdLong());
                });

        logger.debug("Users by timezone = {}, roles by timezone = {}", userTimezones, timezoneOffsetRoles);

        // start the background process to update users' roles.
        new Thread(new TimezoneBot()).start();
    }

    @Override
    public void onGuildMessageReceived(@Nonnull GuildMessageReceivedEvent event) {
        String message = event.getMessage().getContentRaw().trim();
        processMessage(event.getAuthor(), event.getChannel(), message);
    }

    @Override
    public void onPrivateMessageReceived(@Nonnull PrivateMessageReceivedEvent event) {
        String message = event.getMessage().getContentRaw().trim();
        processMessage(event.getAuthor(), event.getChannel(), message);
    }

    private void processMessage(User user, MessageChannel channel, String message) {
        if (!user.isBot() && message.equals("!timezone")) {
            // print help
            channel.sendMessage("Usage: `!timezone [tzdata timezone name]` (example: `!timezone Europe/Paris`)\n" +
                    "To figure out your timezone, visit https://max480-random-stuff.appspot.com/detect-timezone.html\n" +
                    "If you don't want to share your timezone name, you can either DM the bot, or use an offset like UTC+8" +
                    " (it won't automatically adjust with daylight saving though).\n\n" +
                    "If you want to get rid of your timezone role, use `!remove_timezone`.").queue();
        }

        if (!user.isBot() && message.startsWith("!timezone ")) {
            try {
                // check that the timezone is valid by passing it to ZoneId.of and discarding the result.
                String zoneName = message.substring(10).trim();
                ZoneId.of(zoneName);

                // save the link, both in userTimezones and on disk.
                userTimezones.put(user.getIdLong(), zoneName);
                logger.info("User {} now has timezone {}", user.getIdLong(), zoneName);
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(SAVE_FILE_NAME))) {
                    for (Map.Entry<Long, String> entry : userTimezones.entrySet()) {
                        writer.write(entry.getKey() + ";" + entry.getValue() + "\n");
                    }

                    channel.sendMessage(":white_check_mark: Your timezone was saved as **" + zoneName + "**.\n" +
                            "It may take some time for the timezone role to show up, as they are updated every 15 minutes.").queue();
                } catch (IOException e) {
                    // I/O error while saving to disk??
                    logger.error("Error while writing file", e);
                    channel.sendMessage(":x: A technical error occurred. <@" + SecretConstants.OWNER_ID + "> :a:").queue();
                }
            } catch (DateTimeException ex) {
                // ZoneId.of blew up so the timezone is probably invalid.
                logger.info("Could not parse timezone from command " + message, ex);
                channel.sendMessage(":x: The given timezone was not recognized.\n" +
                        "To figure out your timezone, visit https://max480-random-stuff.appspot.com/detect-timezone.html").queue();
            }
        }

        if (!user.isBot() && message.equals("!remove_timezone")) {
            if (userTimezones.containsKey(user.getIdLong())) {
                // remove all timezone roles from the user.
                Guild server = jda.getGuildById(SERVER_ID);
                Member member = server.getMemberById(user.getIdLong());
                for (Role userRole : member.getRoles()) {
                    if (timezoneOffsetRoles.values().stream().anyMatch(l -> l == userRole.getIdLong())) {
                        logger.info("Removing timezone role {} from {}", userRole, member);
                        server.removeRoleFromMember(member, userRole).reason("User used !remove_timezone").complete();
                    }
                }

                // forget the user timezone and write it to disk.
                userTimezones.remove(user.getIdLong());
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(SAVE_FILE_NAME))) {
                    for (Map.Entry<Long, String> entry : userTimezones.entrySet()) {
                        writer.write(entry.getKey() + ";" + entry.getValue() + "\n");
                    }

                    channel.sendMessage(":white_check_mark: Your timezone role has been removed.").queue();
                } catch (IOException e) {
                    // I/O error while saving to disk??
                    logger.error("Error while writing file", e);
                    channel.sendMessage(":x: A technical error occurred. <@" + SecretConstants.OWNER_ID + "> :a:").queue();
                }
            } else {
                // user asked for their timezone to be forgotten, but doesn't have a timezone to start with :thonk:
                channel.sendMessage(":x: You don't currently have a timezone role!").queue();
            }
        }
    }

    public void run() {
        while (true) {
            try {
                Guild server = jda.getGuildById(SERVER_ID);

                Set<Integer> obsoleteTimezones = new HashSet<>(timezoneOffsetRoles.keySet()); // timezones no one has anymore
                Set<Long> obsoleteUsers = new HashSet<>(); // users that left the server
                List<Role> existingRoles = new ArrayList<>(server.getRoles()); // all server roles

                for (Map.Entry<Long, String> timezone : userTimezones.entrySet()) {
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
                        if (timezoneOffsetRoles.containsKey(offset)) {
                            // role already exists!
                            targetRole = existingRoles.stream().filter(role -> role.getIdLong() == timezoneOffsetRoles.get(offset))
                                    .findFirst().orElseThrow(() -> new RuntimeException("Managed role for " + offset + " somehow disappeared, send help"));
                        } else {
                            // we need to create a new timezone role for this user.
                            // it will be created with a throw-away name
                            logger.info("Creating role for timezone offset {}", offset);
                            targetRole = server.createRole().setName("timezone role for " + offset).setPermissions(0L)
                                    .reason("User has non currently existing timezone " + offset).complete();
                            existingRoles.add(targetRole);
                            timezoneOffsetRoles.put(offset, targetRole.getIdLong());
                        }

                        boolean userHasCorrectRole = false;
                        for (Role userRole : member.getRoles()) {
                            if (userRole.getIdLong() != targetRole.getIdLong() && timezoneOffsetRoles.values().stream().anyMatch(l -> l == userRole.getIdLong())) {
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
                    userTimezones.remove(user);
                }

                // delete timezone roles that are assigned to no-one.
                for (int timezone : obsoleteTimezones) {
                    existingRoles.stream().filter(role -> role.getIdLong() == timezoneOffsetRoles.get(timezone))
                            .findFirst()
                            .map(role -> {
                                logger.info("Removing role {}", role);
                                role.delete().reason("Nobody has this role anymore").queue();
                                return role;
                            })
                            .orElseThrow(() -> new RuntimeException("Managed role for " + timezone + " somehow disappeared, send help"));
                    timezoneOffsetRoles.remove(timezone);
                }

                // update the remaining roles!
                for (Map.Entry<Integer, Long> timezoneRoles : timezoneOffsetRoles.entrySet()) {
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
                    String roleName = "Timezone " + timezoneOffsetFormatted + " (" + now.format(DateTimeFormatter.ofPattern("ha")).toLowerCase(Locale.ROOT) + ")";
                    if (!roleName.equals(role.getName())) {
                        role.getManager().setName(roleName).reason("Time passed").queue();
                    }
                }

                jda.getPresence().setActivity(Activity.playing("!timezone | " + timezoneOffsetRoles.size() + " roles | " + userTimezones.size() + " users"));
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
                // wait until the clock hits a time divisible by 15
                long sleepTime = 900_000L - (ZonedDateTime.now().getMinute() % 15) * 60_000L;
                logger.debug("Next update will happen in {} minutes", sleepTime / 60_000L);
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                logger.error("Sleep interrupted(???)", e);
            }
        }
    }
}
