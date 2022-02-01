package com.max480.discord.randombots;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.privileges.CommandPrivilege;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This file is the entrypoint, it sets up the bot and contains some shared methods.
 */
public class TimezoneBot {
    private static final Logger logger = LoggerFactory.getLogger(TimezoneBot.class);

    /**
     * The timezone of a user in a particular server.
     * Timezones are server-specific, since you might want to only show your timezone to some servers.
     */
    static class UserTimezone {
        final long serverId;
        final long userId;
        final String timezoneName;

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
    static class CachedMember implements Serializable {
        public static final long serialVersionUID = -2324191448907830722L;

        final long serverId;
        final long memberId;
        final String memberName;
        final ArrayList<Long> roleIds;

        public CachedMember(long serverId, long memberId, String memberName, ArrayList<Long> roleIds) {
            this.serverId = serverId;
            this.memberId = memberId;
            this.memberName = memberName;
            this.roleIds = roleIds;
        }

        @Override
        public String toString() {
            return "MemberCache{" +
                    "serverId=" + serverId +
                    ", memberId=" + memberId +
                    ", memberName=" + memberName +
                    ", roleIds=" + roleIds +
                    '}';
        }
    }

    static List<UserTimezone> userTimezones;
    static Set<Long> serversWithTime; // servers that want times in timezone roles
    static ArrayList<CachedMember> memberCache = new ArrayList<>(); // cache of users retrieved in the past

    static JDA jda;

    // names of files on disk
    static final String SERVERS_WITH_TIME_FILE_NAME = "servers_with_time.txt";
    static final String MEMBER_CACHE_NAME = "timezone_bot_member_cache.ser";
    private static final String SAVE_FILE_NAME = "user_timezones.csv";

    public static void main(String[] args) throws Exception {
        Map<String, String> timezoneMap = new HashMap<>();
        Map<String, List<String>> timezoneConflicts = new HashMap<>();

        // populate the timezones!
        for (Element elt : Jsoup.connect("https://www.timeanddate.com/time/zones/").get().select("#tz-abb tbody tr")) {
            String name = elt.select("td:first-child").text().trim();
            String fullName = elt.select("td:nth-child(2)").first().ownText().trim();
            String offset = elt.select("td:last-child").text().trim().replace(" ", "");

            // UTC+8:45 => UTC+08:45
            if (offset.matches("UTC[+-][0-9]:[0-9]{2}")) {
                offset = offset.replace("+", "+0").replace("-", "-0");
            }

            try {
                ZoneId.of(offset);
            } catch (DateTimeException e) {
                // the timezone ended up invalid: skip it.
                logger.info("Time zone offset {} is invalid", offset);
                continue;
            }

            timezoneMap.put(fullName, offset);
            if (!timezoneConflicts.containsKey(name)) {
                // there is no conflict (yet): add to the valid timezone map.
                timezoneMap.put(name, offset);
                timezoneConflicts.put(name, new ArrayList<>(Collections.singletonList(fullName)));
            } else {
                timezoneConflicts.get(name).add(fullName);

                if (timezoneMap.containsKey(name) && !timezoneMap.get(name).equals(offset)) {
                    // there is a conflict and the offsets are different! remove it.
                    timezoneMap.remove(name);
                }
            }
        }

        // filter out conflicts that aren't conflicts.
        Map<String, List<String>> actualConflicts = new HashMap<>();
        for (Map.Entry<String, List<String>> conflict : timezoneConflicts.entrySet()) {
            if (!timezoneMap.containsKey(conflict.getKey())) {
                actualConflicts.put(conflict.getKey(), conflict.getValue());
            }
        }
        timezoneConflicts = actualConflicts;

        logger.info("Time zone offsets: {}, zone conflicts: {}", timezoneMap, timezoneConflicts);

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
                .addEventListeners(new BotEventListener(timezoneMap, timezoneConflicts))
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

        // also ensure the /toggle-times permissions are appropriate (in case we missed an event while being down).
        logger.info("Updating /toggle-times permissions on all known guilds...");
        updateToggleTimesPermsForGuilds(jda.getGuilds());

        // start the background process to update users' roles.
        new Thread(new TimezoneRoleUpdater()).start();
    }

    public static int getServerCount() {
        return jda.getGuilds().size();
    }

    /**
     * Looks up timezone offset roles for a server by matching them by role name.
     *
     * @param g The server to retrieve offset roles for
     * @return The retrieved offset roles (map for UTC offset -> role ID)
     */
    static Map<Integer, Long> getTimezoneOffsetRolesForGuild(Guild g) {
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

    /**
     * Turns an offset into a formatted timezone name, used in roles and in the /list-timezones result.
     *
     * @param zoneOffset An offset in minutes, like 120
     * @return An offset timezone name, like "UTC+02:00"
     */
    @NotNull
    static String formatTimezoneName(int zoneOffset) {
        int hours = zoneOffset / 60;
        int minutes = Math.abs(zoneOffset) % 60;
        DecimalFormat twoDigits = new DecimalFormat("00");
        return "UTC" + (hours < 0 ? "-" : "+") + twoDigits.format(Math.abs(hours)) + ":" + twoDigits.format(minutes);
    }

    /**
     * Gets a member from the cache, or retrieve it if they are not in the cache.
     *
     * @param g        The guild the member is part of
     * @param memberId The member to retrieve
     * @return The cached entry that was retrieved from cache or loaded from Discord
     */
    static CachedMember getMemberWithCache(Guild g, long memberId) {
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
                        CachedMember cached = new CachedMember(g.getIdLong(), memberId, m.getUser().getName() + "#" + m.getUser().getDiscriminator(),
                                m.getRoles().stream()
                                        .map(Role::getIdLong)
                                        .filter(timezoneRoles::containsValue)
                                        .collect(Collectors.toCollection(ArrayList::new)));

                        // add it to the cache and return it
                        memberCache.add(cached);
                        return cached;
                    } catch (ErrorResponseException error) {
                        if (error.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER) {
                            // Unknown Member error: this is to be expected if a member left the server.
                            logger.warn("Got Unknown Member error when trying to get member {} in guild {}!", memberId, g);
                            return null;
                        }

                        // unexpected error
                        throw error;
                    }
                });
    }

    /**
     * Saves the user timezones to disk, then sends a message depending on success or failure to the user.
     * Both parameters can be null if no message should be sent.
     *
     * @param respond The method that should be called to respond to the user
     * @param success The message to send in case of success
     */
    static void saveUsersTimezonesToFile(Consumer<Object> respond, String success) {
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
     * Makes sure the permissions for /toggle-times are properly set for the given servers:
     * any role with the Admin or Manage Server permission + the owner if they don't have any of the roles should
     * be able to use the command.
     * <p>
     * If more than 10 overrides are necessary for that, the command will be open to everyone since Discord doesn't allow
     * more than 10 overrides, but non-admins will receive an error when calling it.
     *
     * @param guilds The servers to check
     */
    static void updateToggleTimesPermsForGuilds(List<Guild> guilds) {
        jda.retrieveCommands().queue(commands -> {
            Command timezone = commands.stream().filter(c -> c.getName().equals("timezone")).findFirst().orElse(null);
            Command detectTimezone = commands.stream().filter(c -> c.getName().equals("detect-timezone")).findFirst().orElse(null);
            Command removeTimezone = commands.stream().filter(c -> c.getName().equals("remove-timezone")).findFirst().orElse(null);
            Command discordTimestamp = commands.stream().filter(c -> c.getName().equals("discord-timestamp")).findFirst().orElse(null);
            Command timeFor = commands.stream().filter(c -> c.getName().equals("time-for")).findFirst().orElse(null);
            Command listTimezones = commands.stream().filter(c -> c.getName().equals("list-timezones")).findFirst().orElse(null);
            Command toggleTimes = commands.stream().filter(c -> c.getName().equals("toggle-times")).findFirst().orElse(null);

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
                listPrivileges.put(listTimezones.getId(), allowEveryone);

                if (privileges.size() > 10) {
                    // this is more overrides than Discord allows! so just allow everyone to use the command,
                    // non-admins will get an error message if they try anyway.
                    logger.info("{} has too many privileges that qualify for /toggle-times ({} > 10 max), allowing everyone!", g, privileges.size());
                    listPrivileges.put(toggleTimes.getId(), allowEveryone);
                    g.updateCommandPrivileges(listPrivileges).queue();
                } else {
                    logger.info("The following entities have access to /toggle-times in {}: roles {}, owner with id {}", g, rolesWithPerms, g.getOwnerIdLong());
                    listPrivileges.put(toggleTimes.getId(), privileges);
                    g.updateCommandPrivileges(listPrivileges).queue();
                }
            }
        });
    }

    /**
     * Replaces the command list. Usually not called, but the bot can be run once with this method called
     * to add, update or delete commands.
     */
    private static void registerSlashCommands() {
        // register the slash commands, then assign per-guild permissions for /toggle-times.
        // all commands have defaultEnabled = false to disable them in DMs.
        jda.updateCommands()
                .addCommands(new CommandData("timezone", "Sets up or replaces your timezone role")
                        .addOption(OptionType.STRING, "tz_name", "Timezone name, use /detect-timezone to figure it out", true)
                        .setDefaultEnabled(false))
                .addCommands(new CommandData("detect-timezone", "Detects your current timezone")
                        .setDefaultEnabled(false))
                .addCommands(new CommandData("remove-timezone", "Removes your timezone role")
                        .setDefaultEnabled(false))
                .addCommands(new CommandData("discord-timestamp", "Gives a Discord timestamp, to tell a date/time to other people regardless of their timezone")
                        .addOption(OptionType.STRING, "date_time", "Date and time to convert (format: YYYY-MM-DD hh:mm:ss)", true)
                        .setDefaultEnabled(false))
                .addCommands(new CommandData("time-for", "Gives the time it is now for another member of the server")
                        .addOption(OptionType.USER, "member", "The member you want to get the time of", true)
                        .setDefaultEnabled(false))
                .addCommands(new CommandData("toggle-times", "[Admin] Switches on/off whether to show the time it is in timezone roles")
                        .setDefaultEnabled(false))
                .addCommands(new CommandData("list-timezones", "Lists the timezones of all members in the server")
                        .addOptions(new OptionData(OptionType.STRING, "visibility", "Whether the response should be public or private (private by default)", false)
                                .addChoice("public", "public")
                                .addChoice("private", "private"))
                        .setDefaultEnabled(false))
                .complete();
    }
}
