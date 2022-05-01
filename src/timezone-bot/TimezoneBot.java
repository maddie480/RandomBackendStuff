package com.max480.discord.randombots;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.data.DataObject;
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
        final String discordTag;
        final String nickname;
        final ArrayList<Long> roleIds;

        public CachedMember(long serverId, long memberId, String discordTag, String nickname, ArrayList<Long> roleIds) {
            this.serverId = serverId;
            this.memberId = memberId;
            this.discordTag = discordTag;
            this.nickname = nickname;
            this.roleIds = roleIds;
        }

        @Override
        public String toString() {
            return "MemberCache{" +
                    "serverId=" + serverId +
                    ", memberId=" + memberId +
                    ", discordTag=" + discordTag +
                    ", nickname=" + nickname +
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

        // start up the bot.
        jda = JDABuilder.createLight(SecretConstants.TIMEZONE_BOT_TOKEN, Collections.emptyList())
                .addEventListeners(new BotEventListener(timezoneMap, timezoneConflicts))
                .build().awaitReady();

        // do some cleanup, in case we were kicked from a server while offline.
        removeNonExistingServersFromServersWithTime();

        logger.debug("Users by timezone = {}, servers with time = {}", userTimezones.size(), serversWithTime.size());

        // start the background process to update users' roles.
        Thread updater = new Thread(new TimezoneRoleUpdater());
        updater.setName("Timezone Role Updater");
        updater.start();
    }

    /**
     * Removes any non-existing servers from the list of those which have /toggle-times enabled,
     * then saves the list to disk if any change was made.
     * This happens on startup, and each time the bot is kicked from a server.
     */
    static void removeNonExistingServersFromServersWithTime() {
        boolean serverWasRemoved = false;

        for (Long serverId : new HashSet<>(serversWithTime)) {
            if (jda.getGuildById(serverId) == null) {
                logger.warn("Removing non-existing server {} from servers with time list", serverId);
                serversWithTime.remove(serverId);
                serverWasRemoved = true;
            }
        }

        if (serverWasRemoved) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(SERVERS_WITH_TIME_FILE_NAME))) {
                for (Long server : serversWithTime) {
                    writer.write(server + "\n");
                }
            } catch (IOException e) {
                logger.error("Could not save the servers with time list to disk!", e);
            }
        }
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
                        CachedMember cached = new CachedMember(
                                g.getIdLong(),
                                memberId,
                                m.getUser().getName() + "#" + m.getUser().getDiscriminator(),
                                m.getEffectiveName(),
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
     * {@link CommandData} that supports command description localizations and the new default permission system.
     */
    private static class CommandDataUpdated extends CommandData {
        private boolean restrictedToAdmins = false;
        private final Map<String, String> descriptionLocalizations = new HashMap<>();

        public CommandDataUpdated(@NotNull String name, @NotNull String description) {
            super(name, description);
        }

        public CommandDataUpdated setRestrictedToAdmins(boolean restrictedToAdmins) {
            this.restrictedToAdmins = restrictedToAdmins;
            return this;
        }

        public CommandDataUpdated addDescriptionLocalization(String locale, String description) {
            descriptionLocalizations.put(locale, description);
            return this;
        }

        @Override
        public @NotNull DataObject toData() {
            DataObject data = super.toData()
                    .put("default_permission", !restrictedToAdmins)
                    .put("dm_permission", false)
                    .put("description_localizations", descriptionLocalizations);

            if (restrictedToAdmins) {
                data = data.put("default_member_permissions", Permission.getRaw(Permission.MANAGE_SERVER));
            }

            return data;
        }
    }

    /**
     * {@link OptionData} that supports command description localizations.
     */
    private static class OptionDataUpdated extends OptionData {
        private final Map<String, String> descriptionLocalizations = new HashMap<>();

        public OptionDataUpdated(@NotNull OptionType type, @NotNull String name, @NotNull String description, boolean isRequired) {
            super(type, name, description, isRequired);
        }

        public OptionDataUpdated addDescriptionLocalization(String locale, String description) {
            descriptionLocalizations.put(locale, description);
            return this;
        }

        @Override
        public @NotNull DataObject toData() {
            return super.toData().put("description_localizations", descriptionLocalizations);
        }
    }

    /**
     * Replaces the command list. Usually not called, but the bot can be run once with this method called
     * to add, update or delete commands.
     */
    private static void registerSlashCommands() {
        // register the slash commands, then assign per-guild permissions for /toggle-times.
        // all commands have defaultEnabled = false to disable them in DMs.
        jda.updateCommands()
                .addCommands(new CommandDataUpdated("timezone", "Sets up or replaces your timezone role")
                        .addDescriptionLocalization("fr", "Configure ou remplace ton rôle de fuseau horaire")
                        .addOptions(new OptionDataUpdated(OptionType.STRING, "tz_name", "Timezone name, use /detect-timezone to figure it out", true)
                                .addDescriptionLocalization("fr", "Le nom du fuseau horaire, utilise /detect-timezone pour le déterminer")))
                .addCommands(new CommandDataUpdated("detect-timezone", "Detects your current timezone")
                        .addDescriptionLocalization("fr", "Détecte ton fuseau horaire actuel"))
                .addCommands(new CommandDataUpdated("remove-timezone", "Removes your timezone role")
                        .addDescriptionLocalization("fr", "Supprime ton rôle de fuseau horaire"))
                .addCommands(new CommandDataUpdated("discord-timestamp", "Gives a Discord timestamp, to tell a date/time to other people regardless of their timezone")
                        .addDescriptionLocalization("fr", "Donne un timestamp Discord, pour dire une date et heure à quelqu'un quel que soit son fuseau horaire")
                        .addOptions(new OptionDataUpdated(OptionType.STRING, "date_time", "Date and time to convert (format: YYYY-MM-DD hh:mm:ss)", true)
                                .addDescriptionLocalization("fr", "La date et heure à convertir (au format AAAA-MM-JJ hh:mm:ss)")))
                .addCommands(new CommandDataUpdated("time-for", "Gives the time it is now for another member of the server")
                        .addDescriptionLocalization("fr", "Donne l'heure qu'il est pour quelqu'un d'autre sur le serveur")
                        .addOptions(new OptionDataUpdated(OptionType.USER, "member", "The member you want to get the time of", true)
                                .addDescriptionLocalization("fr", "Le membre pour lequel tu veux savoir l'heure qu'il est")))
                .addCommands(new CommandDataUpdated("toggle-times", "[Admin] Switches on/off whether to show the time it is in timezone roles")
                        .addDescriptionLocalization("fr", "[Admin] Active ou désactive l'affichage de l'heure qu'il est dans les rôles de fuseaux horaires")
                        .setRestrictedToAdmins(true))
                .addCommands(new CommandDataUpdated("list-timezones", "Lists the timezones of all members in the server")
                        .addDescriptionLocalization("fr", "Montre une liste des fuseaux horaires de tous les membres du serveur")
                        .addOptions(
                                new OptionDataUpdated(OptionType.STRING, "visibility", "Whether the response should be \"public\" or \"private\" (\"private\" by default)", false)
                                        .addDescriptionLocalization("fr", "\"public\" pour que la réponse soit publique, \"private\" pour qu'elle soit privée (privée par défaut)")
                                        .addChoice("public", "public")
                                        .addChoice("private", "private"),
                                new OptionDataUpdated(OptionType.STRING, "names", "The names to use for people: \"discord_tags\", \"nicknames\" or \"both\" (\"discord_tags\" by default)", false)
                                        .addDescriptionLocalization("fr", "Noms à utiliser : \"discord_tags\" (noms d'utilisateur), \"nicknames\" (pseudos) ou \"both\" (les deux)")
                                        .addChoice("discord_tags", "discord_tags")
                                        .addChoice("nicknames", "nicknames")
                                        .addChoice("both", "both")
                        ))
                .addCommands(new CommandDataUpdated("timezone-dropdown", "[Admin] Creates a dropdown that lets users pick a timezone role")
                        .addDescriptionLocalization("fr", "[Admin] Crée une liste déroulante qui permet aux utilisateurs de choisir un rôle de fuseau horaire")
                        .setRestrictedToAdmins(true)
                        .addOptions(
                                new OptionDataUpdated(OptionType.STRING, "options", "The timezones that can be picked (pass \"help\" for syntax and examples)", true)
                                        .addDescriptionLocalization("fr", "Les fuseaux horaires proposés dans la liste (tape \"help\" pour plus de détails et des exemples)"),
                                new OptionDataUpdated(OptionType.STRING, "message", "The message to display above the dropdown", false)
                                        .addDescriptionLocalization("fr", "Le message à afficher au-dessus de la liste déroulante")
                        ))
                .complete();
    }
}
