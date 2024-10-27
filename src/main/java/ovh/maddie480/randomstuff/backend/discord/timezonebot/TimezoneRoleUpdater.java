package ovh.maddie480.randomstuff.backend.discord.timezonebot;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A perpetual loop running every 15 minutes to update timezone roles, either swapping them on daylight saving,
 * or just renaming them every hour if /toggle-times is enabled.
 */
public class TimezoneRoleUpdater implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(TimezoneRoleUpdater.class);

    private static long lastRunDate = System.currentTimeMillis();

    private static ZonedDateTime lastRoleUpdateDate = null;
    private static boolean forceUpdate = false;

    /**
     * Force the timezone role updater to run within a second, instead of when the clock next hits a number
     * of minutes divisible by 15.
     */
    static void forceUpdate() {
        forceUpdate = true;
    }

    public static long getLastRunDate() {
        return lastRunDate;
    }

    /**
     * This is the main method of the thread, a perpetual loop updating roles on each server whenever the clock
     * hits a number of minutes divisible by 15.
     */
    public void run() {
        while (true) {
            try {
                if (TimezoneBot.memberCache.isEmpty()) {
                    // if there is no cache, updating timezone roles is going to take a while... so, tell users about it.
                    TimezoneBot.jda.getPresence().setActivity(Activity.playing("Updating timezone roles..."));
                }

                boolean usersDeleted = false;

                for (Guild server : TimezoneBot.jda.getGuilds()) {
                    logger.debug("=== Refreshing timezones for server {}", server);
                    if (!server.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
                        logger.debug("I can't manage roles here! I will only check for gone members.");
                        usersDeleted = cleanUpUsersFromServer(server) || usersDeleted;
                        continue;
                    }
                    if (TimezoneBot.getTimezoneOffsetRolesForGuild(server).values().stream()
                            .anyMatch(roleId -> !server.getSelfMember().canInteract(server.getRoleById(roleId)))) {

                        logger.debug("I can't manage all timezone roles here! I will only check for gone members.");
                        usersDeleted = cleanUpUsersFromServer(server) || usersDeleted;
                        continue;
                    }

                    usersDeleted = updateTimezoneRolesInServer(server) || usersDeleted;
                }

                housekeep(usersDeleted);

                TimezoneBot.jda.getPresence().setActivity(Activity.playing("/timezone | " +
                        TimezoneBot.jda.getGuilds().stream().mapToInt(g -> TimezoneBot.getTimezoneOffsetRolesForGuild(g).size()).sum() + " roles | " +
                        TimezoneBot.userTimezones.stream().map(u -> u.userId).distinct().count() + " users | " +
                        TimezoneBot.jda.getGuilds().size() + " servers"));
            } catch (Exception e) {
                logger.error("Refresh roles failed", e);
            }

            lastRunDate = System.currentTimeMillis();

            try {
                logger.debug("Done! Sleeping.");
                // wait until the clock hits a time divisible by 15
                do {
                    Thread.sleep(1050 - (ZonedDateTime.now().getNano() / 1_000_000));

                    if (ZonedDateTime.now().getMinute() % 15 == 14 && ZonedDateTime.now().getSecond() == 59) {
                        // diagnostics: check how long it took to update all roles
                        logger.debug("Last role was updated on {}", lastRoleUpdateDate);
                    }
                } while (!forceUpdate && (ZonedDateTime.now().getMinute() % 15 != 0 || ZonedDateTime.now().getSecond() != 0));
                forceUpdate = false;
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
        Map<Long, String> userTimezonesThisServer = TimezoneBot.userTimezones.stream()
                .filter(s -> s.serverId == guildId)
                .collect(Collectors.toMap(s -> s.userId, s -> s.timezoneName));
        Map<Integer, Long> timezoneOffsetRolesThisServer = TimezoneBot.getTimezoneOffsetRolesForGuild(server);

        // timezones no one has anymore (existing timezones will be removed from the set as it goes)
        Set<Integer> obsoleteTimezones = new HashSet<>(timezoneOffsetRolesThisServer.keySet());

        Set<Long> obsoleteUsers = new HashSet<>(); // users that left the server
        List<Role> existingRoles = new ArrayList<>(server.getRoles()); // all server roles

        for (Map.Entry<Long, String> timezone : userTimezonesThisServer.entrySet()) {
            TimezoneBot.CachedMember member = TimezoneBot.getMemberWithCache(server, timezone.getKey());
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
                        TimezoneBot.memberCache.remove(member);

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
                    TimezoneBot.memberCache.remove(member);

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
            TimezoneBot.userTimezones.stream()
                    .filter(u -> u.serverId == guildId && u.userId == user)
                    .findFirst().map(u -> TimezoneBot.userTimezones.remove(u));
            usersDeleted = true;
        }

        // delete timezone roles that are assigned to no-one.
        for (int timezone : obsoleteTimezones) {
            existingRoles.stream().filter(role -> role.getIdLong() == timezoneOffsetRolesThisServer.get(timezone))
                    .findFirst()
                    .map(role -> {
                        if (role.getIdLong() == 1077139996504506469L) {
                            // this seems to be a case of ghost role, Discord reports it's there but only during session setup
                            // so JDA is left extremely confused... just ignore it
                            return role;
                        }

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
            String timezoneOffsetFormatted = TimezoneBot.formatTimezoneName(zoneOffset);

            // get the date at this timezone
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timezoneOffsetFormatted));

            // build the final role name, and update the role if the name doesn't match.
            String roleName = "Timezone " + timezoneOffsetFormatted +
                    (TimezoneBot.serversWithTime.contains(guildId) ? " (" + now.format(DateTimeFormatter.ofPattern("ha")).toLowerCase(Locale.ROOT) + ")" : "");
            if (!roleName.equals(role.getName())) {
                role.getManager().setName(roleName).reason("Time passed").queue(success -> lastRoleUpdateDate = ZonedDateTime.now());
                logger.debug("Timezone role renamed for offset {}: {} -> {}", zoneOffset, role, roleName);
            }
        }

        return usersDeleted;
    }

    /**
     * Goes through all users that have a timezone configured in a server,
     * and deletes those who do not exist anymore.
     * <p>
     * {@link #updateTimezoneRolesInServer(Guild)} already does this, this method is intended for
     * servers where the bot has insufficient permissions and thus cannot update timezone roles.
     *
     * @param server The server to check
     * @return whether users were deleted or not
     */
    private boolean cleanUpUsersFromServer(Guild server) {
        List<Long> serverUsers = TimezoneBot.userTimezones.stream()
                .filter(s -> s.serverId == server.getIdLong())
                .map(s -> s.userId)
                .toList();

        boolean usersDeleted = false;

        for (long user : serverUsers) {
            if (TimezoneBot.getMemberWithCache(server, user) == null) {
                logger.info("Removing user {}", user);
                TimezoneBot.userTimezones.stream()
                        .filter(u -> u.serverId == server.getIdLong() && u.userId == user)
                        .findFirst().map(u -> TimezoneBot.userTimezones.remove(u));
                usersDeleted = true;
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
     */
    private void housekeep(boolean usersDeleted) {
        // remove settings for users that left
        List<TimezoneBot.UserTimezone> toDelete = new ArrayList<>();
        for (TimezoneBot.UserTimezone userTimezone : TimezoneBot.userTimezones) {
            if (TimezoneBot.jda.getGuilds().stream().noneMatch(g -> g.getIdLong() == userTimezone.serverId)) {
                logger.warn("Removing user {} belonging to non-existing server", userTimezone);
                toDelete.add(userTimezone);
                usersDeleted = true;
            }
        }
        TimezoneBot.userTimezones.removeAll(toDelete);

        // remove users that left or don't have settings from the cache
        for (TimezoneBot.CachedMember memberCache : new ArrayList<>(TimezoneBot.memberCache)) {
            if (TimezoneBot.jda.getGuilds().stream().noneMatch(g -> g.getIdLong() == memberCache.serverId)) {
                logger.warn("Removing user {} from cache belonging to non-existing server", memberCache);
                TimezoneBot.memberCache.remove(memberCache);
            } else if (TimezoneBot.userTimezones.stream().noneMatch(u -> u.serverId == memberCache.serverId && u.userId == memberCache.memberId)) {
                logger.warn("Removing user {} from cache because they are not a bot user", memberCache);
                TimezoneBot.memberCache.remove(memberCache);
            }
        }

        if (usersDeleted) {
            // save the new list, after users were deleted, to disk.
            TimezoneBot.saveUsersTimezonesToFile(null, null);
        }

        if (ZonedDateTime.now().getHour() == 18 && ZonedDateTime.now().getMinute() == 0) {
            // daily housekeeping: clear the cache, to make sure all users still exist.
            logger.debug("Clearing member cache!");
            TimezoneBot.memberCache.clear();
        }
    }

    /**
     * Gets the actual Member object from Discord for a MemberCache object.
     *
     * @param member The cached member object
     * @return The actual member object from Discord
     */
    private Member getMemberForReal(TimezoneBot.CachedMember member) {
        try {
            return TimezoneBot.jda.getGuildById(member.serverId).retrieveMemberById(member.memberId).complete();
        } catch (ErrorResponseException error) {
            if (error.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER) {
                // Unknown Member error: this is to be expected if a member left the server.
                logger.warn("Got Unknown Member error when trying to get cached member {}!", member);
                return null;
            }

            // unexpected error
            throw error;
        }
    }
}
