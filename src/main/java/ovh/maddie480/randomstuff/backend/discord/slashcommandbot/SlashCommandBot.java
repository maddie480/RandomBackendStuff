package ovh.maddie480.randomstuff.backend.discord.slashcommandbot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.internal.interactions.CommandDataImpl;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.max480.discord.slashcommandbot.SlashCommandBot.PlanningExploit;

public class SlashCommandBot extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(SlashCommandBot.class);

    private static final Long SERVER_ID = 647015916588105739L;
    private static final Long CHANNEL_ID = 689481602253717555L;

    private Map<String, String> slashCommandToURL;
    private JDA jda;

    private List<ZonedDateTime> exploitTimes;
    private List<Long> principalExploit;
    private List<Long> backupExploit;
    private Map<Long, List<Pair<ZonedDateTime, ZonedDateTime>>> holidayDates;


    public void start() throws Exception {
        ConnectionUtils.runWithRetry(() -> {
            reloadPlanningExploit();
            return null;
        });

        slashCommandToURL = new HashMap<>();
        slashCommandToURL.put("/pipo", "");
        slashCommandToURL.put("/eddy", "");
        slashCommandToURL.put("/weekend", "");
        slashCommandToURL.put("/bientotleweekend", "");
        slashCommandToURL.put("/vacances", "");
        slashCommandToURL.put("/chucknorris", "");
        slashCommandToURL.put("/random", "");
        slashCommandToURL.put("/toplyrics", "");
        slashCommandToURL.put("/patoche", "");
        slashCommandToURL.put("/ckc", "");
        slashCommandToURL.put("/jcvd", "");
        slashCommandToURL.put("/languedebois", "");
        slashCommandToURL.put("/noel", "");
        slashCommandToURL.put("/joiesducode", "");
        slashCommandToURL.put("/coronavirus", "");
        slashCommandToURL.put("/fakename", "");
        slashCommandToURL.put("/tendancesyoutube", "");
        slashCommandToURL.put("/putaclic", "");
        slashCommandToURL.put("/randomparrot", "");
        slashCommandToURL.put("/monkeyuser", "");
        slashCommandToURL.put("/xkcd", "");
        slashCommandToURL.put("/infopipo", "");
        slashCommandToURL.put("/exploit", "https://maddie480.ovh/mattermost/exploit");
        slashCommandToURL.put("/absents", "https://maddie480.ovh/mattermost/absents");

        jda = JDABuilder.create(SecretConstants.SLASH_COMMAND_BOT_TOKEN,
                        GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MEMBERS, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(this)
                .build().awaitReady();

        for (Long serverId : Arrays.asList(SERVER_ID, 443390765826179072L)) {
            Guild server = jda.getGuildById(serverId);
            server.updateCommands().addCommands(
                    new CommandDataImpl("pipo", "Génère du pipo")
                            .addOption(OptionType.INTEGER, "nombre", "Le nombre de phrases pipo à générer (entre 1 et 5)"),
                    new CommandDataImpl("eddy", "Génère une phrase d'Eddy-Malou"),
                    new CommandDataImpl("weekend", "Donne un compte à rebours vers le week-end"),
                    new CommandDataImpl("bientotleweekend", "Indique si oui ou non, c'est bientôt le week-end"),
                    new CommandDataImpl("vacances", "Donne un compte à rebours vers tes vacances")
                            .addOption(OptionType.STRING, "dernier-jour", "La date de ton dernier jour de travail avant les vacances (format JJ/MM/AAAA)"),
                    new CommandDataImpl("chucknorris", "Donne un Chuck Norris Fact au hasard")
                            .addOptions(new OptionData(OptionType.STRING, "all", "Passer \"all\" pour ne pas filtrer par note", false)
                                    .addChoice("all", "all")),
                    new CommandDataImpl("random", "Tire un nombre au sort")
                            .addOption(OptionType.INTEGER, "max", "Nombre maximum du tirage au sort", true),
                    new CommandDataImpl("toplyrics", "Donne un extrait des meilleurs hits de grands artistes tels que Jul"),
                    new CommandDataImpl("patoche", "Donne un extrait d'une chanson de Patrick Sébastien"),
                    new CommandDataImpl("ckc", "Est-ce que :ckc: ?"),
                    new CommandDataImpl("jcvd", "Génère une petite phrase de Jean-Claude Van Damme"),
                    new CommandDataImpl("languedebois", "Génère du pipo politicien"),
                    new CommandDataImpl("noel", "Votre générateur de téléfilms de Noël"),
                    new CommandDataImpl("joiesducode", "Poste un GIF venant de lesjoiesducode.fr"),
                    new CommandDataImpl("coronavirus", "Donne les dernières statistiques du coronavirus"),
                    new CommandDataImpl("fakename", "Génère un faux nom et une adresse (à peu près) français"),
                    new CommandDataImpl("tendancesyoutube", "Donne une vidéo qui est (apparemment) l'une des plus populaires sur YouTube"),
                    new CommandDataImpl("putaclic", "Génère un titre accrocheur"),
                    new CommandDataImpl("exploit", "Affiche le planning d'exploit"),
                    new CommandDataImpl("absents", "Indique qui est actuellement absent (congés ou formation)"),
                    new CommandDataImpl("randomparrot", "Tire un party parrot au sort"),
                    new CommandDataImpl("monkeyuser", "Donne une bande dessinée de monkeyuser.com au hasard"),
                    new CommandDataImpl("xkcd", "Donne une bande dessinée de xkcd.com au hasard"),
                    new CommandDataImpl("infopipo", "Un générateur de dialogues informatiques pour séries et films")
            ).queue();
        }

        new Thread("Slash Command Bot Hourly Process Runner") {
            @Override
            public void run() {
                while (true) {
                    try {
                        hourlyProcess();
                    } catch (Exception e) {
                        logger.error("Uncaught exception during ", e);
                    }

                    try {
                        Thread.sleep(3600000 - (ZonedDateTime.now().getMinute() * 60000
                                + ZonedDateTime.now().getSecond() * 1000
                                + ZonedDateTime.now().getNano() / 1_000_000) + 50);
                    } catch (InterruptedException e) {
                        logger.error("Sleep interrupted", e);
                    }
                }
            }
        }.start();
    }

    private void hourlyProcess() {
        if (jda == null) return;

        // === rechargement du planning d'exploit et mise à jour des rôles

        while (!exploitTimes.isEmpty() && exploitTimes.get(0).isBefore(ZonedDateTime.now())) {
            exploitTimes.remove(0);
            principalExploit.remove(0);
            backupExploit.remove(0);
        }

        Guild server = jda.getGuildById(SERVER_ID);

        Role exploitRole = server.getRoleById(689110115751821317L);
        Role backupExploitRole = server.getRoleById(689110308769628173L);

        Member quiEstExploit = principalExploit.get(0) == -1 ? null : server.getMemberById(principalExploit.get(0));
        Member quiEstBackupExploit = backupExploit.get(0) == -1 ? null : server.getMemberById(backupExploit.get(0));

        List<Member> quiALeRoleExploit = server.getMembers().stream()
                .filter(member -> member.getRoles().stream().anyMatch(r -> r.getIdLong() == exploitRole.getIdLong()))
                .collect(Collectors.toList());
        List<Member> quiALeRoleBackupExploit = server.getMembers().stream()
                .filter(member -> member.getRoles().stream().anyMatch(r -> r.getIdLong() == backupExploitRole.getIdLong()))
                .toList();

        if ((quiEstExploit == null && quiALeRoleExploit.size() != 0)
                || (quiEstExploit != null &&
                (quiALeRoleExploit.size() != 1 || quiALeRoleExploit.get(0).getIdLong() != quiEstExploit.getIdLong()))) {

            logger.debug("Assignation du rôle {} à {}", exploitRole, quiALeRoleExploit);

            // enlever les rôles exploit et le redonner à la bonne personne
            quiALeRoleExploit.forEach(m -> server.removeRoleFromMember(m, exploitRole).complete());
            if (quiEstExploit != null) server.addRoleToMember(quiEstExploit, exploitRole).queue();
        }

        if ((quiEstBackupExploit == null && quiALeRoleBackupExploit.size() != 0)
                || (quiEstBackupExploit != null &&
                (quiALeRoleBackupExploit.size() != 1 || quiALeRoleBackupExploit.get(0).getIdLong() != quiEstBackupExploit.getIdLong()))) {

            logger.debug("Assignation du rôle {} à {}", exploitRole, quiALeRoleExploit);

            // enlever les rôles backup exploit et le redonner à la bonne personne
            quiALeRoleBackupExploit.forEach(m -> server.removeRoleFromMember(m, backupExploitRole).complete());
            if (quiEstBackupExploit != null)
                server.addRoleToMember(quiEstBackupExploit, backupExploitRole).queue();
        }

        // === mise à jour des rôles congés

        Role holidayRole = server.getRoleById(826037384588165130L);
        for (String idS : SecretConstants.PEOPLE_TO_DISCORD_IDS.values()) {
            long id = Long.parseLong(idS);

            // déterminer si l'utilisateur est en vacances
            Member discordMember = server.getMemberById(id);
            boolean isInHoliday = false;
            List<Pair<ZonedDateTime, ZonedDateTime>> holidays = new ArrayList<>(holidayDates.getOrDefault(id, Collections.emptyList()));
            for (Pair<ZonedDateTime, ZonedDateTime> holiday : holidays) {
                if (holiday.getRight().isBefore(ZonedDateTime.now())) {
                    // fin dans le passé
                    logger.debug("Le congé {} -> {} pour {} est dans le passé ! Suppression", holiday.getLeft(), holiday.getRight(), discordMember);
                    holidayDates.get(id).remove(holiday);
                } else if (holiday.getLeft().isBefore(ZonedDateTime.now())) {
                    // début dans le passé, fin dans le futur => vacances
                    isInHoliday = true;
                    break;
                }
            }

            // mettre à jour le rôle pour correspondre
            if (isInHoliday && discordMember.getRoles().stream().noneMatch(r -> r.getIdLong() == holidayRole.getIdLong())) {
                logger.debug("Ajout du rôle {} à {}", holidayRole, discordMember);
                server.addRoleToMember(discordMember, holidayRole).queue();
            } else if (!isInHoliday && discordMember.getRoles().stream().anyMatch(r -> r.getIdLong() == holidayRole.getIdLong())) {
                logger.debug("Retrait du rôle {} de {}", holidayRole, discordMember);
                server.removeRoleFromMember(discordMember, holidayRole).queue();
            }
        }

        int exploitCount = principalExploit.size();
        int holidayCount = holidayDates.values().stream().mapToInt(List::size).sum();
        jda.getPresence().setActivity(Activity.playing(
                exploitCount + (exploitCount == 1 ? " semaine" : " semaines") + " d'exploit | " +
                        holidayCount + (holidayCount == 1 ? " congé" : " congés") + " | " +
                        slashCommandToURL.size() + (slashCommandToURL.size() == 1 ? " commande" : " commandes")));
    }

    public static void deleteOldMessages(JDA jda) {
        TextChannel general = jda.getGuildById(SERVER_ID).getTextChannelById(CHANNEL_ID);

        logger.debug("Récupération des messages à supprimer...");
        long[] messagesToDelete = general.getIterableHistory().stream()
                .filter(message -> message.getTimeCreated().isBefore(OffsetDateTime.now().minusMonths(1)))
                .mapToLong(Message::getIdLong)
                .toArray();

        logger.debug("Suppression de {} messages...", messagesToDelete.length);
        for (long messageId : messagesToDelete) {
            general.deleteMessageById(messageId).complete();
        }
    }

    private void reloadPlanningExploit() throws IOException {
        logger.info("Rechargement du planning d'exploit !");

        PlanningExploit planningExploitExport = new PlanningExploit();

        List<ZonedDateTime> newExploitTimes = new ArrayList<>();
        List<Long> newPrincipalExploit = new ArrayList<>();
        List<Long> newBackupExploit = new ArrayList<>();
        Map<Long, List<Pair<ZonedDateTime, ZonedDateTime>>> newHolidayDates = new HashMap<>();

        // This call is expected to be extremely slow (it's a calendar export after all), so we want to be extremely patient
        HttpURLConnection icsConnection = ConnectionUtils.openConnectionWithTimeout(SecretConstants.EXPLOIT_PLANNING_URL);
        icsConnection.setReadTimeout(600000); // 10 minutes!

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                ConnectionUtils.connectionToInputStream(icsConnection), StandardCharsets.UTF_8))) {

            // date => ID Discord
            TreeMap<String, Long> principals = new TreeMap<>();
            TreeMap<String, Long> backups = new TreeMap<>();

            TreeMap<String, String> principalsNames = new TreeMap<>();
            TreeMap<String, String> backupsNames = new TreeMap<>();

            String line;
            while ((line = reader.readLine()) != null) {
                // recherche d'un event
                if (line.equals("BEGIN:VEVENT")) {
                    // parsing de l'event
                    Map<String, String> properties = new HashMap<>();
                    String propertyBeingParsed = null;
                    String propertyValue = null;
                    while (!(line = reader.readLine()).equals("END:VEVENT")) {
                        if (propertyBeingParsed == null || !line.startsWith(" ")) {
                            if (propertyBeingParsed != null) {
                                properties.put(propertyBeingParsed, propertyValue);
                            }

                            if (line.contains(":")) {
                                propertyBeingParsed = line.substring(0, line.indexOf(":"));
                                propertyValue = line.substring(line.indexOf(":") + 1);
                            } else {
                                propertyBeingParsed = line.substring(0, line.indexOf(";"));
                                propertyValue = line.substring(line.indexOf(";") + 1);
                            }
                        } else {
                            propertyValue += line.substring(1);
                        }
                    }

                    if (propertyBeingParsed != null) {
                        properties.put(propertyBeingParsed, propertyValue);
                    }

                    // check intégrité
                    if ("Exploitation".equals(properties.get("CATEGORIES"))
                            && Arrays.asList("Principal", "Backup", "Exploitant principal", "Exploitant backup").contains(properties.get("SUMMARY"))
                            && properties.containsKey("DTSTART;VALUE=DATE") && properties.getOrDefault("ATTENDEE", "").contains("CN=")) {

                        // extraction de l'utilisateur
                        String userName = properties.get("ATTENDEE");
                        userName = userName.substring(userName.indexOf("CN=") + 3);
                        userName = userName.substring(0, userName.indexOf(";"));

                        // extraction de la récurrence, s'il y en a une
                        LocalDate date = LocalDate.parse(properties.get("DTSTART;VALUE=DATE"), DateTimeFormatter.ofPattern("yyyyMMdd"));
                        int count = 1;
                        int interval = 1;

                        if (properties.containsKey("RRULE")) {
                            count = 5; // default count if infinite

                            for (String rrule : properties.get("RRULE").split(";")) {
                                String[] rruleKV = rrule.split("=", 2);
                                switch (rruleKV[0]) {
                                    case "FREQ":
                                        if (!rruleKV[1].equals("WEEKLY")) throw new IOException("Unsupported FREQ=" + rruleKV[1]);
                                        break;
                                    case "COUNT":
                                        count = Integer.parseInt(rruleKV[1]);
                                        break;
                                    case "INTERVAL":
                                        interval = Integer.parseInt(rruleKV[1]);
                                        break;
                                    case "BYDAY":
                                        if (!rruleKV[1].equals("MO")) throw new IOException("Unsupported BYDAY=" + rruleKV[1]);
                                        break;
                                    default:
                                        throw new IOException("Unsupported RRULE " + rrule);
                                }
                            }
                        }

                        for (int i = 0; i < count; i++) {
                            String formattedDate = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

                            // sauvegarde
                            if (Arrays.asList("Principal", "Exploitant principal").contains(properties.get("SUMMARY"))) {
                                principals.put(formattedDate, Long.parseLong(SecretConstants.PEOPLE_TO_DISCORD_IDS.getOrDefault(userName, "-1")));
                                principalsNames.put(formattedDate, userName);
                            } else {
                                backups.put(formattedDate, Long.parseLong(SecretConstants.PEOPLE_TO_DISCORD_IDS.getOrDefault(userName, "-1")));
                                backupsNames.put(formattedDate, userName);
                            }

                            date = date.plusDays(7 * interval);
                        }
                    }

                    // check intégrité pour congés
                    if (Arrays.asList("Formation École", "leaves", "Formation").contains(properties.get("CATEGORIES"))
                            && (properties.containsKey("DTSTART;VALUE=DATE") || properties.containsKey("DTSTART;TZID=Europe/Paris"))
                            && (properties.containsKey("DTEND;VALUE=DATE") || properties.containsKey("DTEND;TZID=Europe/Paris"))
                            && properties.getOrDefault("ATTENDEE", "").contains("CN=")) {

                        // extraction de l'utilisateur
                        String userName = properties.get("ATTENDEE");
                        userName = userName.substring(userName.indexOf("CN=") + 3);
                        userName = userName.substring(0, userName.indexOf(";"));

                        // parsing des dates de congés
                        Pair<ZonedDateTime, ZonedDateTime> holiday = new ImmutablePair<>(
                                properties.containsKey("DTSTART;VALUE=DATE") ?
                                        LocalDate.parse(properties.get("DTSTART;VALUE=DATE"), DateTimeFormatter.ofPattern("yyyyMMdd"))
                                                .atTime(0, 0, 0).atZone(ZoneId.of("Europe/Paris")) :
                                        LocalDateTime.parse(properties.get("DTSTART;TZID=Europe/Paris"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                                                .atZone(ZoneId.of("Europe/Paris")),
                                properties.containsKey("DTEND;VALUE=DATE") ?
                                        LocalDate.parse(properties.get("DTEND;VALUE=DATE"), DateTimeFormatter.ofPattern("yyyyMMdd"))
                                                .atTime(0, 0, 0).atZone(ZoneId.of("Europe/Paris")) :
                                        LocalDateTime.parse(properties.get("DTEND;TZID=Europe/Paris"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                                                .atZone(ZoneId.of("Europe/Paris")));

                        // enregistrer la date de congé par ID si c'est un ID connu
                        if (SecretConstants.PEOPLE_TO_DISCORD_IDS.containsKey(userName)) {
                            List<Pair<ZonedDateTime, ZonedDateTime>> userHolidays = newHolidayDates.getOrDefault(SecretConstants.PEOPLE_TO_DISCORD_IDS.get(userName), new ArrayList<>());
                            userHolidays.add(holiday);
                            newHolidayDates.put(Long.parseLong(SecretConstants.PEOPLE_TO_DISCORD_IDS.get(userName)), userHolidays);
                        }

                        // enregistrer la date de congé par nom si c'est dans le futur
                        if (holiday.getRight().isAfter(ZonedDateTime.now())) {
                            List<Pair<ZonedDateTime, ZonedDateTime>> userHolidays = planningExploitExport.holidays.getOrDefault(userName, new ArrayList<>());
                            userHolidays.add(holiday);
                            planningExploitExport.holidays.put(userName, userHolidays);
                        }
                    }
                }
            }

            for (String date : principals.keySet()) {
                if (backups.containsKey(date)) {
                    // on a un couple principal/backup qu'on peut ajouter
                    ZonedDateTime exploitTime = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyyMMdd"))
                            .atTime(8, 0).atZone(ZoneId.of("Europe/Paris"));

                    newPrincipalExploit.add(principals.get(date));
                    newBackupExploit.add(backups.get(date));
                    newExploitTimes.add(exploitTime);

                    planningExploitExport.principalExploit.add(principalsNames.get(date));
                    planningExploitExport.backupExploit.add(backupsNames.get(date));
                    planningExploitExport.exploitTimes.add(exploitTime);
                }
            }

            newExploitTimes.remove(0);
            planningExploitExport.exploitTimes.remove(0);

            while (!planningExploitExport.exploitTimes.isEmpty() && planningExploitExport.exploitTimes.get(0).isBefore(ZonedDateTime.now())) {
                planningExploitExport.exploitTimes.remove(0);
                planningExploitExport.principalExploit.remove(0);
                planningExploitExport.backupExploit.remove(0);
            }

            // exporter le planning sur Cloud Storage

            try (OutputStream os = Files.newOutputStream(Paths.get("/shared/mattermost/planning_exploit.ser"));
                 ObjectOutputStream oos = new ObjectOutputStream(os)) {

                oos.writeObject(planningExploitExport);
                logger.debug("Exported planning_exploit.ser to frontend");
            }

            // retenir le planning pour la commande /exploit
            exploitTimes = newExploitTimes;
            principalExploit = newPrincipalExploit;
            backupExploit = newBackupExploit;
            holidayDates = newHolidayDates;
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String commandName = "/" + event.getName();

        String url = slashCommandToURL.get(commandName);
        String token = SecretConstants.SLASH_COMMAND_TO_TOKEN.get(commandName);

        event.deferReply().queue();
        Pair<MessageCreateData, Boolean> callResult = SlackToDiscord.sendSlashCommand(event.getUser().getIdLong(), event.getChannel().getIdLong(),
                commandName + " " + event.getOptions().stream().map(OptionMapping::getAsString).collect(Collectors.joining(" ")),
                token, url);
        event.getHook().sendMessage(callResult.getLeft()).setEphemeral(callResult.getRight()).queue();
    }
}
