package com.max480.discord.randombots;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.Nonnull;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;

public class ModStructureVerifier extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ModStructureVerifier.class);

    private static final String CHANNELS_SAVE_FILE_NAME = "mod_structure_police_save.csv";
    private static final String FREE_CHANNELS_SAVE_FILE_NAME = "mod_structure_police_save_free.csv";
    private static final String NO_NAME_CHANNELS_SAVE_FILE_NAME = "mod_structure_police_save_noname.csv";
    private static final String MESSAGES_TO_ANSWERS_FILE_NAME = "mod_structure_police_messages_to_answers.csv";

    private static final Map<Long, Long> responseChannels = new HashMap<>(); // watched channel ID > response channel ID
    private static final Map<Long, String> collabAssetPrefixes = new HashMap<>(); // watched channel ID > collab assets prefix
    private static final Map<Long, String> collabMapPrefixes = new HashMap<>(); // watched channel ID > collab maps prefix

    // watched channel ID > response channel ID but for channels allowing to use the bot freely with --verify
    private static final Map<Long, Long> freeResponseChannels = new HashMap<>();
    // watched channel ID > response channel ID but for channels that don't check file names
    private static final Map<Long, Long> noNameResponseChannels = new HashMap<>();

    private static final Map<Long, Long> messagesToEmbeds = new HashMap<>(); // message ID > embed message ID from the bot

    private static Map<String, String> assetToMod = Collections.emptyMap();
    private static Map<String, String> entityToMod = Collections.emptyMap();
    private static Map<String, String> triggerToMod = Collections.emptyMap();
    private static Map<String, String> effectToMod = Collections.emptyMap();

    private static JDA jda;
    private static int analyzedZipCount = 0;

    public static void main(String[] args) throws Exception {
        // load the list of channels the bot should be listening to from disk.
        if (new File(CHANNELS_SAVE_FILE_NAME).exists()) {
            try (Stream<String> lines = Files.lines(Paths.get(CHANNELS_SAVE_FILE_NAME))) {
                lines.forEach(line -> {
                    String[] split = line.split(";", 4);
                    responseChannels.put(Long.parseLong(split[0]), Long.parseLong(split[1]));
                    collabAssetPrefixes.put(Long.parseLong(split[0]), split[2]);
                    collabMapPrefixes.put(Long.parseLong(split[0]), split[3]);
                });
            }
        }

        if (new File(FREE_CHANNELS_SAVE_FILE_NAME).exists()) {
            try (Stream<String> lines = Files.lines(Paths.get(FREE_CHANNELS_SAVE_FILE_NAME))) {
                lines.forEach(line -> {
                    String[] split = line.split(";", 2);
                    freeResponseChannels.put(Long.parseLong(split[0]), Long.parseLong(split[1]));
                });
            }
        }

        if (new File(NO_NAME_CHANNELS_SAVE_FILE_NAME).exists()) {
            try (Stream<String> lines = Files.lines(Paths.get(NO_NAME_CHANNELS_SAVE_FILE_NAME))) {
                lines.forEach(line -> {
                    String[] split = line.split(";", 2);
                    noNameResponseChannels.put(Long.parseLong(split[0]), Long.parseLong(split[1]));
                });
            }
        }

        // load the list of messages-to-embeds associations from disk.
        if (new File(MESSAGES_TO_ANSWERS_FILE_NAME).exists()) {
            try (Stream<String> lines = Files.lines(Paths.get(MESSAGES_TO_ANSWERS_FILE_NAME))) {
                lines.forEach(line -> {
                    String[] split = line.split(";", 2);
                    messagesToEmbeds.put(Long.parseLong(split[0]), Long.parseLong(split[1]));
                });
            }
        }

        // clean up messages-to-embeds links that are older than 6 months (message IDs are "snowflakes" and the creation date can be deduced from them).
        for (Long messageId : new ArrayList<>(messagesToEmbeds.keySet())) {
            ZonedDateTime messageDate = Instant.ofEpochMilli((messageId >> 22) + 1420070400000L).atZone(ZoneId.systemDefault());
            if (messageDate.isBefore(ZonedDateTime.now().minusMonths(6))) {
                logger.warn("Forgetting message {} because its date is {}", messageId, messageDate);
                messagesToEmbeds.remove(messageId);
            }
        }

        // start up the bot.
        jda = JDABuilder.createLight(SecretConstants.MOD_STRUCTURE_VERIFIER_TOKEN, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new ModStructureVerifier(),
                        // some code specific to the Strawberry Jam 2021 server, not published and has nothing to do with timezones
                        // but that wasn't really enough to warrant a separate bot
                        new StrawberryJamUpdate())
                .build().awaitReady();

        int serverCount = jda.getGuilds().size();
        jda.getPresence().setActivity(Activity.playing(
                "--help | " + serverCount + " server" + (serverCount == 1 ? "" : "s")));

        // clean up channels that do not exist anymore.
        for (Long channelId : new ArrayList<>(responseChannels.keySet())) {
            if (jda.getGuilds().stream().noneMatch(guild -> guild.getTextChannelById(channelId) != null)) {
                logger.warn("Forgetting channel {} (fixed-name) because it does not exist", channelId);
                responseChannels.remove(channelId);
                collabAssetPrefixes.remove(channelId);
                collabMapPrefixes.remove(channelId);
            }
        }
        for (Long channelId : new ArrayList<>(freeResponseChannels.keySet())) {
            if (jda.getGuilds().stream().noneMatch(guild -> guild.getTextChannelById(channelId) != null)) {
                logger.warn("Forgetting channel {} (free-name) because it does not exist", channelId);
                freeResponseChannels.remove(channelId);
            }
        }
        for (Long channelId : new ArrayList<>(noNameResponseChannels.keySet())) {
            if (jda.getGuilds().stream().noneMatch(guild -> guild.getTextChannelById(channelId) != null)) {
                logger.warn("Forgetting channel {} (no-name) because it does not exist", channelId);
                noNameResponseChannels.remove(channelId);
            }
        }

        // save the files after cleanup.
        saveMap(null, null);
        savePostedMessagesMap(null);

        logger.debug("Bot is currently in following guilds: {}", jda.getGuilds());
    }

    /**
     * This is called from {@link UpdateCheckerTracker} when the database changes (or on startup),
     * in order to refresh the asset maps.
     */
    static void updateAssetToModDictionary(Map<String, String> assetToMod,
                                           Map<String, String> entityToMod,
                                           Map<String, String> triggerToMod,
                                           Map<String, String> effectToMod) {

        ModStructureVerifier.assetToMod = assetToMod;
        ModStructureVerifier.entityToMod = entityToMod;
        ModStructureVerifier.triggerToMod = triggerToMod;
        ModStructureVerifier.effectToMod = effectToMod;
    }

    public static int getServerCount() {
        return jda.getGuilds().size();
    }

    // let the owner know when the bot joins or leaves servers
    @Override
    public void onGuildJoin(@NotNull GuildJoinEvent event) {
        logger.info("Just joined server {}", event.getGuild());
        event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                .sendMessage("I just joined a new server! I am now in **" + event.getJDA().getGuilds().size() + "** servers.").queue();
    }

    @Override
    public void onGuildLeave(@NotNull GuildLeaveEvent event) {
        logger.info("Just left server {}", event.getGuild());
        event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                .sendMessage("I was just kicked from a server. I am now in **" + event.getJDA().getGuilds().size() + "** servers.").queue();
    }

    @Override
    public void onMessageReceived(@Nonnull MessageReceivedEvent event) {
        if (!event.isFromGuild()) return;

        if (event.getAuthor().getIdLong() == event.getJDA().getSelfUser().getIdLong()) {
            // failsafe: do not react to our own messages.
            return;
        }

        if (!event.getAuthor().isBot() && (event.getMember().hasPermission(Permission.ADMINISTRATOR)
                || event.getMember().hasPermission(Permission.MANAGE_SERVER))) {

            // anyone with admin or manage server permissions can run commands to set up the bot.
            parseAdminCommand(event);
        }

        if (event.getMessage().getContentRaw().startsWith("--generate-font ")) {
            if (event.getMessage().getAttachments().isEmpty()) {
                event.getChannel().sendMessage(":x: Send a text file in order to generate a font for it!").queue();
            } else {
                event.getMessage().getAttachments().get(0).getProxy().downloadToFile(new File("/tmp/text_file_" + System.currentTimeMillis() + ".txt"))
                        .thenAcceptAsync(file -> FontGenerator.generateFontFromDiscord(file, event.getMessage().getContentRaw().substring("--generate-font ".length()), event.getChannel(), event.getMessage()));
            }
        } else if (freeResponseChannels.containsKey(event.getChannel().getIdLong()) && event.getMessage().getContentRaw().startsWith("--verify ")) {
            // a --verify command was sent in a channel where people are allowed to send --verify commands, hmm...
            // there should be 3 parts (so 2 parameters including --verify itself), with all 2 being alphanumeric.
            String[] settings = event.getMessage().getContentRaw().split(" ", 4);
            if (settings.length >= 3 && settings[1].matches("[A-Za-z0-9]+") && settings[2].matches("[A-Za-z0-9]+")) {
                scanMapFromMessage(event, settings[1], settings[2], freeResponseChannels.get(event.getChannel().getIdLong()));
            } else {
                // print help if one of the parameters is invalid.
                event.getChannel().sendMessage("Usage: `--verify [assets folder name] [maps folder name]`\n" +
                        "`[assets folder name]` and `[maps folder name]` should be alphanumeric.").queue();
            }
        } else if (noNameResponseChannels.containsKey(event.getChannel().getIdLong())) {
            // message was sent in a no-name channel! scan it with null names.
            scanMapFromMessage(event, null, null, noNameResponseChannels.get(event.getChannel().getIdLong()));

        } else if (responseChannels.containsKey(event.getChannel().getIdLong())) {
            // message was sent in a watched channel...

            final String expectedCollabAssetPrefix = collabAssetPrefixes.get(event.getChannel().getIdLong());
            final String expectedCollabMapPrefix = collabMapPrefixes.get(event.getChannel().getIdLong());

            scanMapFromMessage(event, expectedCollabAssetPrefix, expectedCollabMapPrefix, responseChannels.get(event.getChannel().getIdLong()));
        }
    }

    /**
     * Scans a map contained in a message (no matter if it is scanned automatically, or with the --verify command).
     *
     * @param event                     The message to scan
     * @param expectedCollabAssetPrefix The prefix for assets to check in the structure
     * @param expectedCollabMapPrefix   The prefix for maps to check in the structure
     * @param responseChannelId         The channel where all problems with the map will be sent
     */
    private void scanMapFromMessage(@NotNull MessageReceivedEvent event, String expectedCollabAssetPrefix, String expectedCollabMapPrefix, long responseChannelId) {
        for (Message.Attachment attachment : event.getMessage().getAttachments()) {
            if (attachment.getFileName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                // this is a zip attachment! analyze it.

                event.getMessage().addReaction(Emoji.fromUnicode("\uD83E\uDD14")).complete(); // :thinking:

                logger.info("{} sent a file named {} in {} that we should analyze!", event.getMember(), attachment.getFileName(), event.getChannel());
                attachment.getProxy().downloadToFile(new File("/tmp/modstructurepolice_" + System.currentTimeMillis() + ".zip"))
                        .thenAcceptAsync(file -> analyzeZipFileFromDiscord(event, attachment, expectedCollabAssetPrefix, expectedCollabMapPrefix, file, responseChannelId));
            }
        }

        // try recognizing Google Drive links by regex in the message text.
        String messageText = event.getMessage().getContentRaw();
        String googleDriveId = null;
        Matcher googleDriveLinkFormat1 = Pattern.compile(".*https://drive\\.google\\.com/open\\?id=([A-Za-z0-9_-]+).*", Pattern.DOTALL).matcher(messageText);
        if (googleDriveLinkFormat1.matches()) {
            googleDriveId = googleDriveLinkFormat1.group(1);
        }
        Matcher googleDriveLinkFormat2 = Pattern.compile(".*https://drive\\.google\\.com/file/d/([A-Za-z0-9_-]+).*", Pattern.DOTALL).matcher(messageText);
        if (googleDriveLinkFormat2.matches()) {
            googleDriveId = googleDriveLinkFormat2.group(1);
        }

        if (googleDriveId != null) {
            logger.info("{} sent a Google Drive file with id {} in {} that we should analyze!", event.getMember(), googleDriveId, event.getChannel());

            event.getMessage().addReaction(Emoji.fromUnicode("\uD83E\uDD14")).complete(); // :thinking:

            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://www.googleapis.com/drive/v3/files/" + googleDriveId + "?key=" + SecretConstants.GOOGLE_DRIVE_API_KEY + "&alt=media")) {
                // download the file through the Google Drive API and analyze it.
                File target = new File("/tmp/modstructurepolice_" + System.currentTimeMillis() + ".zip");
                FileUtils.copyToFile(is, target);
                analyzeZipFileFromDiscord(event, null, expectedCollabAssetPrefix, expectedCollabMapPrefix, target, responseChannelId);
            } catch (IOException e) {
                // the file could not be downloaded (the file is probably private or non-existent).
                logger.warn("Could not download file id {}", googleDriveId, e);
                event.getMessage().removeReaction(Emoji.fromUnicode("\uD83E\uDD14")).queue(); // :thinking:
                event.getMessage().addReaction(Emoji.fromUnicode("\uD83D\uDCA3")).queue(); // :bomb:

                // post a message, since this kind of error might be on the user.
                Optional.<MessageChannel>ofNullable(event.getGuild().getTextChannelById(responseChannelId))
                        .orElse(event.getChannel())
                        .sendMessage(event.getAuthor().getAsMention() + " I couldn't download the file from the Google Drive link you posted in " + event.getChannel().getAsMention() + "." +
                                " Maybe the file is private or it doesn't exist anymore? :thinking:\nMake sure anyone that has the link can download it.").queue();
            }
        }
    }

    public static void analyzeZipFileFromDiscord(MessageReceivedEvent event, Message.Attachment attachment, String expectedCollabAssetPrefix,
                                                 String expectedCollabMapsPrefix, File file, Long responseChannelId) {

        analyzeZipFile(event, attachment, expectedCollabAssetPrefix, expectedCollabMapsPrefix, file, responseChannelId, null);
    }

    public static void analyzeZipFileFromFrontend(File file, String expectedCollabAssetPrefix, String expectedCollabMapsPrefix,
                                                  BiConsumer<String, List<File>> sendResultToFrontend) {

        analyzeZipFile(null, null, expectedCollabAssetPrefix, expectedCollabMapsPrefix, file, null, sendResultToFrontend);
    }

    /**
     * Scans a zip file.
     *
     * @param event                     The message event that triggered the scan
     * @param attachment                The Discord attachment file, or null if the file comes from Google Drive
     * @param expectedCollabAssetPrefix The expected collab assets prefix
     * @param expectedCollabMapsPrefix  The expected collab maps prefix for that channel
     * @param file                      The file to scan
     * @param responseChannelId         The channel where all problems with the map will be sent
     * @param sendResultToFrontend      The method to call to send the result of the verification to the frontend
     */
    private static void analyzeZipFile(MessageReceivedEvent event, Message.Attachment attachment, String expectedCollabAssetPrefix,
                                       String expectedCollabMapsPrefix, File file, Long responseChannelId, BiConsumer<String, List<File>> sendResultToFrontend) {

        if (event != null) {
            analyzedZipCount++;
            int serverCount = event.getJDA().getGuilds().size();
            jda.getPresence().setActivity(Activity.playing(
                    "--help | " + analyzedZipCount + " zip" + (analyzedZipCount == 1 ? "" : "s") + " analyzed since startup | "
                            + serverCount + " server" + (serverCount == 1 ? "" : "s")));
        }

        boolean isHtml = (sendResultToFrontend != null);

        logger.debug("Collab assets folder = {}, Collab maps folder = {}", expectedCollabAssetPrefix, expectedCollabMapsPrefix);

        try (ZipFile zipFile = new ZipFile(file)) {
            List<String> problemList = new ArrayList<>();
            Set<String> websiteProblemList = new HashSet<>();
            Set<String> missingDependencies = new HashSet<>();

            final List<String> fileListing = zipFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(ZipEntry::getName)
                    .collect(Collectors.toList());

            boolean hasNameScan = (expectedCollabAssetPrefix != null && expectedCollabMapsPrefix != null);

            if (hasNameScan) {
                logger.debug("Scanning invalid asset paths...");

                // asset paths being Assets/ (lua cutscenes), Graphics/Atlases/, Graphics/ColorGrading/ and Tutorials/
                // should match: Graphics/Atlases/[anything]/collabname/[anything]/[anything]
                parseProblematicPaths(problemList, websiteProblemList, "assets", "You have assets that are at the wrong place, please move them", fileListing.stream()
                        .filter(entry -> entry.startsWith("Assets/") || entry.startsWith("Graphics/ColorGrading/")
                                || entry.startsWith("Graphics/Atlases/") || entry.startsWith("Tutorials/"))
                        .filter(entry -> !entry.matches("^(Assets|Graphics/Atlases|Graphics/ColorGrading|Tutorials)(/.+)?/" + expectedCollabAssetPrefix + "/.+/.+$"))
                        .collect(Collectors.toList()), isHtml);

                logger.debug("Scanning invalid XML paths...");

                // XMLs are anything that matches Graphics/[anything].xml
                // should match: Graphics/collabnamexmls/[anything]/[anything].xml
                parseProblematicPaths(problemList, websiteProblemList, "xmls", "You have XMLs that are at the wrong place, please move them", fileListing.stream()
                        .filter(entry -> entry.startsWith("Graphics/") && entry.endsWith(".xml"))
                        .filter(entry -> !entry.matches("^Graphics/" + expectedCollabAssetPrefix + "xmls/.+/.+\\.xml$"))
                        .collect(Collectors.toList()), isHtml);
            }

            logger.debug("Scanning presence of map bins...");

            // if name scan is enabled, there should be exactly one map in the zip.
            // otherwise, there should be at least one.
            List<String> maps = fileListing.stream()
                    .filter(entry -> entry.startsWith("Maps/") && entry.endsWith(".bin"))
                    .collect(Collectors.toList());

            boolean shouldScanMapContents = true;
            if (maps.size() == 0) {
                problemList.add(pickFormat(isHtml,
                        "<b>There is no map in the Maps folder!</b> No map will appear in-game.",
                        "**There is no map in the Maps folder!** No map will appear in-game."));
                websiteProblemList.add("nomap");
                shouldScanMapContents = false;
            } else if (maps.size() >= 2 && hasNameScan) {
                problemList.add("There are " + maps.size() + " maps in this zip. \uD83E\uDD14"); // :thinking:
                websiteProblemList.add("multiplemaps");
                shouldScanMapContents = false;
            } else if (hasNameScan) {
                // check its path
                String mapPath = maps.get(0);
                if (!mapPath.matches("^Maps/" + expectedCollabMapsPrefix + "/.+/.+\\.bin$")) {
                    parseProblematicPaths(problemList, websiteProblemList, "badmappath",
                            "Your map is not in the right folder", Collections.singletonList(mapPath), isHtml);
                }
            }

            if (hasNameScan) {
                // Dialog/English.txt is not required to exist, but if it does, it'd better be valid.
                ZipEntry englishTxt = zipFile.getEntry("Dialog/English.txt");
                if (englishTxt != null) {
                    logger.debug("Scanning invalid English.txt entries...");

                    List<String> badDialogEntries = new ArrayList<>();

                    // dialog entries are matched using the same regex as in-game.
                    // it should match: [collabname]_[anything]_[anything] or [englishtxtname]_[anything]_[anything]
                    Pattern dialogEntry = Pattern.compile("^\\w+=.*");
                    Pattern validDialogEntry = Pattern.compile("^(" + expectedCollabAssetPrefix + ")_[^_]+_.*=.*");
                    Pattern altValidDialogEntry = Pattern.compile("^(" + expectedCollabMapsPrefix + ")_[^_]+_.*=.*");
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(zipFile.getInputStream(englishTxt)))) {
                        String s;
                        while ((s = br.readLine()) != null) {
                            s = s.trim();
                            if (dialogEntry.matcher(s).matches() && !validDialogEntry.matcher(s).matches()
                                    && !altValidDialogEntry.matcher(s).matches()) {

                                badDialogEntries.add(s.substring(0, s.indexOf("=")));
                            }
                        }
                    }

                    parseProblematicPaths(problemList, websiteProblemList, "badenglish", "You have English.txt entries with invalid names, please rename them", badDialogEntries, isHtml);
                }
            }

            logger.debug("Scanning everest.yaml...");

            String yamlName = null;
            List<String> dependencies = null;

            // everest.yaml should exist
            ZipEntry everestYaml = zipFile.getEntry("everest.yaml");
            if (everestYaml == null) {
                if (fileListing.stream().anyMatch(f -> f.endsWith("/everest.yaml"))) {
                    problemList.add(pickFormat(isHtml,
                            "You have an everest.yaml, but it is in a subfolder. <b>Hint:</b> when zipping your mod, don't zip the folder, but the contents of it. " +
                                    "That is, go inside your mod folder, select everything, and compress that!",
                            "You have an everest.yaml, but it is in a subfolder. **Hint:** when zipping your mod, don't zip the folder, but the contents of it. " +
                                    "That is, go inside your mod folder, select everything, and compress that!"));
                    websiteProblemList.add("misplacedyaml");
                } else {
                    problemList.add(pickFormat(isHtml,
                            "You have no everest.yaml, please create one. You can install" +
                                    " <a href=\"https://gamebanana.com/tools/6908\" target=\"_blank\">this tool</a> to help you out.",
                            "You have no everest.yaml, please create one. You can install this tool to help you out: <https://gamebanana.com/tools/6908>"));
                    websiteProblemList.add("noyaml");
                }
            } else {
                try (InputStream is = zipFile.getInputStream(everestYaml)) {
                    // save it in temp directory
                    String dir = "/tmp/everest_yaml_" + System.currentTimeMillis();
                    new File(dir).mkdir();
                    FileUtils.copyToFile(is, new File(dir + "/everest.yaml"));

                    // build a request to everest.yaml validator
                    HttpPostMultipart submit = new HttpPostMultipart("https://max480-random-stuff.appspot.com/celeste/everest-yaml-validator", "UTF-8", new HashMap<>());
                    submit.addFilePart("file", new File(dir + "/everest.yaml"));
                    submit.addFormField("outputFormat", "json");
                    HttpURLConnection result = submit.finish();

                    // delete the temp file
                    new File(dir + "/everest.yaml").delete();
                    new File(dir).delete();

                    // read the response from everest.yaml validator
                    JSONObject resultBody = new JSONObject(IOUtils.toString(result.getInputStream(), StandardCharsets.UTF_8));
                    if (!resultBody.has("modInfo")) {
                        problemList.add(pickFormat(isHtml,
                                "Your everest.yaml seems to have problems, send it to <a href=\"https://max480-random-stuff.appspot.com/celeste/everest-yaml-validator\" target=\"_blank\">the everest.yaml validator</a> for more details",
                                "Your everest.yaml seems to have problems, send it to <https://max480-random-stuff.appspot.com/celeste/everest-yaml-validator> for more details"));
                        websiteProblemList.add("yamlinvalid");
                    } else {
                        // grab the mod name and dependency names given by the validator so that we don't have to do that ourselves later!
                        yamlName = resultBody.getJSONObject("modInfo").getString("Name");
                        dependencies = new ArrayList<>();
                        for (Object o : resultBody.getJSONObject("modInfo").getJSONArray("Dependencies")) {
                            dependencies.add(((JSONObject) o).getString("Name"));
                        }
                    }
                }
            }

            if (shouldScanMapContents && dependencies != null) {
                for (String mapPath : maps) {
                    // if the map exists and has a proper everest.yaml, then we can check if it contains everything that is needed for the map.
                    searchForMissingComponents(problemList, websiteProblemList, missingDependencies, fileListing, zipFile, mapPath, dependencies, isHtml);
                }
            }

            GuildMessageChannel channel = null;
            if (event != null) {
                channel = Optional.<GuildMessageChannel>ofNullable(event.getGuild().getTextChannelById(responseChannelId)).orElse(event.getChannel().asGuildMessageChannel());
            }

            logger.debug("Checking for missing fonts...");
            Map<String, String> missingFonts = checkForMissingFonts(zipFile);
            if (!missingFonts.isEmpty()) {
                String attachmentMessage = channel == null || channel.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_ATTACH_FILES) ?
                        "You will find the missing characters in the attached text files." :
                        "_Grant the Attach Files permission to the bot in order to get text files with all missing characters._";

                problemList.add(pickFormat(isHtml,
                        "You use characters that are missing from the game's font in some of your dialog files. " + attachmentMessage +
                                " If you want to be able to use them, use <a href=\"https://max480-random-stuff.appspot.com/celeste/font-generator\" target=\"_blank\">the Font Generator</a>" +
                                " to add them to the game.",
                        "You use characters that are missing from the game's font in some of your dialog files. " + attachmentMessage +
                                " If you want to be able to use them, you can use this bot's `--generate-font [language]` command to add them to the game."));
                websiteProblemList.add("missingfonts");
            }

            if (problemList.isEmpty()) {
                if (event != null) {
                    event.getMessage().removeReaction(Emoji.fromUnicode("\uD83E\uDD14")).queue(); // :thinking:
                    event.getMessage().addReaction(Emoji.fromUnicode("\uD83D\uDC4C")).queue(); // :ok_hand:
                } else {
                    sendResultToFrontend.accept("✅ <b>No issue was found with your zip!</b>", Collections.emptyList());
                }

                if (attachment != null && event != null) {
                    // post an embed to the 2-click installer.
                    EmbedBuilder embedBuilder = new EmbedBuilder()
                            .setTitle("Install " + yamlName, "https://0x0ade.ga/twoclick?" + attachment.getUrl())
                            .setDescription("Posted by " + event.getAuthor().getAsMention())
                            .setTimestamp(Instant.now());
                    event.getChannel().sendMessageEmbeds(embedBuilder.build())
                            .queue(postedMessage -> {
                                // save the message ID and the embed ID.
                                messagesToEmbeds.put(event.getMessageIdLong(), postedMessage.getIdLong());
                                savePostedMessagesMap(event);
                            });
                }
            } else {
                String url = null;
                if (!websiteProblemList.isEmpty()) {
                    if (hasNameScan) {
                        url = "https://max480-random-stuff.appspot.com/celeste/mod-structure-verifier-help?collabName=" + expectedCollabAssetPrefix
                                + (!expectedCollabAssetPrefix.equals(expectedCollabMapsPrefix) ? "&collabMapName=" + expectedCollabMapsPrefix : "")
                                + "&" + String.join("&", websiteProblemList);
                    } else {
                        url = "https://max480-random-stuff.appspot.com/celeste/mod-structure-verifier-help?" + String.join("&", websiteProblemList);
                    }
                }

                // format the missing dependency list (if any) in human-readable format.
                String dependenciesList = "";
                missingDependencies.remove(null); // "mod not found" = null
                if (missingDependencies.size() == 1) {
                    dependenciesList = pickFormat(isHtml,
                            "\n\nYou should probably add <code>" + StringEscapeUtils.escapeHtml4(missingDependencies.iterator().next()) + "</code> as a dependency for your map, or stop using things from it if it is another map.",
                            "\n\nYou should probably add `" + missingDependencies.iterator().next().replace("`", "") + "` as a dependency for your map, or stop using things from it if it is another map.");
                } else if (!missingDependencies.isEmpty()) {
                    StringBuilder list = new StringBuilder("\n\nYou should probably add ");
                    int index = 0;
                    for (String dependency : missingDependencies) {
                        if (index == missingDependencies.size() - 1) {
                            list.append(" and ");
                        } else if (index != 0) {
                            list.append(", ");
                        }

                        if (isHtml) {
                            list.append("<code>").append(StringEscapeUtils.escapeHtml4(dependency)).append("</code>");
                        } else {
                            list.append('`').append(dependency.replace("`", "")).append('`');
                        }

                        index++;
                    }
                    list.append(" as dependencies for your map, or stop using things from them if they are other maps.");

                    dependenciesList = list.toString();
                }

                if (event != null) {
                    // ping the user with an issues list in the response channel, truncating the message if it is somehow too long.
                    String message = event.getAuthor().getAsMention() + " Oops, there are issues with the zip you just posted in " + event.getChannel().getAsMention() + ":\n- "
                            + String.join("\n- ", problemList) + dependenciesList;
                    if (message.length() > 2000) {
                        message = message.substring(0, 1997) + "...";
                    }

                    MessageCreateBuilder discordMessage = new MessageCreateBuilder().setContent(message);

                    // if there is any "website problem", attach a link to the help website.
                    if (url != null) {
                        discordMessage.setEmbeds(new EmbedBuilder().setTitle("Click here for more help", url).build());
                    }

                    MessageCreateAction sendingMessage = channel.sendMessage(discordMessage.build());

                    if (channel.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_ATTACH_FILES)) {
                        for (Map.Entry<String, String> missingFont : missingFonts.entrySet()) {
                            sendingMessage = sendingMessage.addFiles(FileUpload.fromData(missingFont.getValue().getBytes(UTF_8), missingFont.getKey() + "_missing_chars.txt"));
                        }
                    }

                    sendingMessage.queue();

                    event.getMessage().removeReaction(Emoji.fromUnicode("\uD83E\uDD14")).queue(); // :thinking:
                    event.getMessage().addReaction(Emoji.fromUnicode("❌")).queue(); // :x:
                } else {
                    // compose the message in a very similar but not identical way
                    String message = "❌ <b>Oops, there are issues with the zip you just sent:</b><ul><li>" + String.join("</li><li>", problemList) + "</li></ul>" + dependenciesList;
                    if (url != null) {
                        message += "<a href=\"" + url + "\" target=\"_blank\">Click here for more help.</a>";
                    }

                    // write the files to the disk to prepare sending them out
                    List<File> files = missingFonts.entrySet().stream()
                            .map(entry -> {
                                try {
                                    File output = new File("/tmp/" + entry.getKey() + "_missing_chars_" + System.currentTimeMillis() + ".txt");
                                    FileUtils.writeStringToFile(output, entry.getValue(), UTF_8);
                                    return output;
                                } catch (IOException e) {
                                    logger.error("Cannot write missing chars file", e);
                                    return null;
                                }
                            })
                            .collect(Collectors.toList());

                    if (files.contains(null)) {
                        throw new IOException("Could not send missing font file!");
                    }

                    // send them out
                    sendResultToFrontend.accept(message, files);

                    // delete the files we just created.
                    for (File f : files) {
                        FileUtils.forceDelete(f);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not read zip file! Ignoring.", e);

            if (event != null) {
                event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                        .sendMessage("An error occurred while scanning a zip: " + e.toString()).queue();

                event.getMessage().removeReaction(Emoji.fromUnicode("\uD83E\uDD14")).queue(); // :thinking:
                event.getMessage().addReaction(Emoji.fromUnicode("\uD83D\uDCA3")).queue(); // :bomb:
            } else {
                sendResultToFrontend.accept("\uD83D\uDCA3 An error occurred while scanning your zip.", Collections.emptyList());
            }
        }

        // and delete the file when we're done analyzing it.
        file.delete();
    }

    @Override
    public void onMessageDelete(@NotNull MessageDeleteEvent event) {
        if (!event.isFromGuild()) return;

        if (messagesToEmbeds.containsKey(event.getMessageIdLong())) {
            // if a user deletes their file, make sure to delete the embed that goes with it.
            event.getChannel().deleteMessageById(messagesToEmbeds.get(event.getMessageIdLong())).queue();
            messagesToEmbeds.remove(event.getMessageIdLong());
            savePostedMessagesMap(event);
        }
    }

    private static void searchForMissingComponents(List<String> problemList, Set<String> websiteProblemsList, Set<String> missingDependencies,
                                                   List<String> fileListing, ZipFile zipFile, String mapPath, List<String> dependencies, boolean isHtml) throws IOException {

        // first, let's collect what is available to us with vanilla, the map's assets, and the dependencies.
        Set<String> availableDecals = VanillaDatabase.allVanillaDecals.stream().map(a -> a.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Set<String> availableStylegrounds = VanillaDatabase.allVanillaStylegrounds.stream().map(a -> a.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Set<String> availableEntities;
        Set<String> availableTriggers;
        Set<String> availableEffects;

        // collect vanilla entity info by grabbing the file left by the update checker.
        try (InputStream vanillaEntities = new FileInputStream("modfilesdatabase/ahorn_vanilla.yaml")) {
            Map<String, List<String>> entitiesList = new Yaml().load(vanillaEntities);
            availableEntities = new HashSet<>(entitiesList.get("Entities"));
            availableTriggers = new HashSet<>(entitiesList.get("Triggers"));
            availableEffects = new HashSet<>(entitiesList.get("Effects"));
        }

        // parallax is an effect too!
        availableEffects.add("parallax");

        // grab the decals and stylegrounds that ship with the mod.
        fileListing.stream()
                .filter(file -> file.startsWith("Graphics/Atlases/Gameplay/decals/") && file.endsWith(".png"))
                .map(file -> file.substring(26, file.length() - 4).toLowerCase(Locale.ROOT))
                .forEach(availableDecals::add);
        fileListing.stream()
                .filter(file -> file.startsWith("Graphics/Atlases/Gameplay/bgs/") && file.endsWith(".png"))
                .map(file -> file.substring(26, file.length() - 4).toLowerCase(Locale.ROOT))
                .forEach(availableStylegrounds::add);

        // get the mod updater database to check dependencies. (since everest.yaml was checked earlier, all dependencies should be valid)
        logger.debug("Loading mod database for decal & styleground analysis...");
        Map<String, Map<String, Object>> databaseContents;
        try (InputStream databaseFile = new FileInputStream("uploads/everestupdate.yaml")) {
            databaseContents = new Yaml().load(databaseFile);
        }

        for (String dep : dependencies) {
            if (databaseContents.containsKey(dep)) { // to exclude Everest

                String depUrl = (String) databaseContents.get(dep).get(com.max480.everest.updatechecker.Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL");
                if (depUrl.matches("https://gamebanana.com/mmdl/[0-9]+")) {
                    // instead of downloading the file, let's grab its contents from the mod files database left by the update checker.
                    File modFilesDatabaseFile = new File("modfilesdatabase/" +
                            databaseContents.get(dep).get("GameBananaType") + "/" +
                            databaseContents.get(dep).get("GameBananaId") + "/" +
                            depUrl.substring("https://gamebanana.com/mmdl/".length()) + ".yaml");

                    if (modFilesDatabaseFile.isFile()) {
                        logger.debug("Loading decals and stylegrounds from dependency {} (file {})...", dep, modFilesDatabaseFile.getAbsolutePath());
                        List<String> depFileListing;
                        try (InputStream databaseFile = new FileInputStream(modFilesDatabaseFile)) {
                            depFileListing = new Yaml().load(databaseFile);
                        }

                        // get everything looking like a decal or a styleground.
                        depFileListing.stream()
                                .filter(file -> file.startsWith("Graphics/Atlases/Gameplay/decals/") && file.endsWith(".png"))
                                .map(file -> file.substring(26, file.length() - 4).toLowerCase(Locale.ROOT))
                                .forEach(availableDecals::add);
                        depFileListing.stream()
                                .filter(file -> file.startsWith("Graphics/Atlases/Gameplay/bgs/") && file.endsWith(".png"))
                                .map(file -> file.substring(26, file.length() - 4).toLowerCase(Locale.ROOT))
                                .forEach(availableStylegrounds::add);
                    }

                    // is there a file for Ahorn and Lönn entities as well?
                    checkMapEditorEntities("ahorn", availableEntities, availableTriggers, availableEffects, databaseContents, dep, depUrl);
                    checkMapEditorEntities("loenn", availableEntities, availableTriggers, availableEffects, databaseContents, dep, depUrl);
                }
            }

            // == delete when SJ is out -- start

            if (dep.equals("StrawberryJam2021")) {
                // download and analyze the SJ2021 helper.
                try (InputStream databaseStrawberryJam = ConnectionUtils.openStreamWithTimeout(SecretConstants.STRAWBERRY_JAM_LOCATION)) {
                    String whereIsStrawberryJam = new Yaml().<Map<String, Map<String, Object>>>load(databaseStrawberryJam)
                            .get("StrawberryJam2021")
                            .get("URL").toString();

                    addStuffFromSJ2021(availableEntities, availableTriggers, availableEffects, whereIsStrawberryJam, isHtml);
                }
            }

            // == delete when SJ is out -- end
        }

        // extract the map bin.
        File tempBin = new File("/tmp/mod_bin_" + System.currentTimeMillis() + ".bin");
        logger.debug("Extracting map {} at {}...", mapPath, tempBin.getAbsolutePath());
        try (InputStream is = zipFile.getInputStream(zipFile.getEntry(mapPath))) {
            FileUtils.copyToFile(is, tempBin);
        }

        // convert it to XML
        Document binAsXmlFile = null;
        logger.debug("Reading {} as XML...", tempBin.getAbsolutePath());
        try {
            binAsXmlFile = BinToXML.toXmlDocument(tempBin.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Something bad happened while reading the XML!", e);
        }
        tempBin.delete();

        String mapPathEsc = formatProblematicThing(isHtml, mapPath);

        if (binAsXmlFile == null) {
            // conversion failed
            problemList.add("Something wrong happened while trying to analyze " + mapPathEsc + " \uD83E\uDD14 check that it is not corrupt."); // :thinking:
        } else {
            // let's start listing everything that's wrong!
            Set<String> badDecals = new HashSet<>();
            Set<String> badSGs = new HashSet<>();
            Set<String> badEntities = new HashSet<>();
            Set<String> badTriggers = new HashSet<>();
            Set<String> badEffects = new HashSet<>();

            { // check all decals in a case insensitive way
                NodeList decals = binAsXmlFile.getElementsByTagName("decal");
                for (int i = 0; i < decals.getLength(); i++) {
                    Node decal = decals.item(i);
                    if (decal.getAttributes().getNamedItem("texture") != null) {
                        String decalName = decal.getAttributes().getNamedItem("texture").getNodeValue().replace("\\", "/");
                        if (decalName.endsWith(".png")) decalName = decalName.substring(0, decalName.length() - 4);
                        if (!availableDecals.contains("decals/" + decalName.toLowerCase(Locale.ROOT))) {
                            badDecals.add(decalName);
                        }
                    }
                }
            }

            { // check all stylegrounds starting with bgs/ (to exclude stuff from the Misc atlas) in a case-insensitive way.
                NodeList stylegrounds = binAsXmlFile.getElementsByTagName("parallax");
                for (int i = 0; i < stylegrounds.getLength(); i++) {
                    Node styleground = stylegrounds.item(i);
                    if (styleground.getAttributes().getNamedItem("texture") != null) {
                        String sgName = styleground.getAttributes().getNamedItem("texture").getNodeValue().replace("\\", "/");
                        if (sgName.endsWith(".png")) sgName = sgName.substring(0, sgName.length() - 4);
                        if (sgName.startsWith("bgs/") && !availableStylegrounds.contains(sgName.toLowerCase(Locale.ROOT))) {
                            badSGs.add(sgName);
                        }
                    }
                }
            }

            // check entities, triggers and effects.
            checkForMissingEntities(availableEntities, "entities", badEntities, binAsXmlFile);
            checkForMissingEntities(availableTriggers, "triggers", badTriggers, binAsXmlFile);
            checkForMissingEntities(availableEffects, "Foregrounds", badEffects, binAsXmlFile);
            checkForMissingEntities(availableEffects, "Backgrounds", badEffects, binAsXmlFile);

            // and list out every single problem!
            parseProblematicPaths(problemList, websiteProblemsList, "missingassets", "You use missing decals in " + mapPathEsc + ", use other ones or make sure your dependencies are set up correctly", new ArrayList<>(badDecals), isHtml);
            parseProblematicPaths(problemList, websiteProblemsList, "missingassets", "You use missing parallax stylegrounds in " + mapPathEsc + ", use other ones or make sure your dependencies are set up correctly", new ArrayList<>(badSGs), isHtml);
            parseProblematicPaths(problemList, websiteProblemsList, "missingentities", "You use missing entities in " + mapPathEsc + ", make sure your dependencies are set up correctly", new ArrayList<>(badEntities), isHtml);
            parseProblematicPaths(problemList, websiteProblemsList, "missingentities", "You use missing triggers in " + mapPathEsc + ", make sure your dependencies are set up correctly", new ArrayList<>(badTriggers), isHtml);
            parseProblematicPaths(problemList, websiteProblemsList, "missingentities", "You use missing effects in " + mapPathEsc + ", make sure your dependencies are set up correctly", new ArrayList<>(badEffects), isHtml);

            // look up which mod each of these missing things could belong to, in order to have an exhaustive list at the end.
            for (String entity : badEntities) {
                missingDependencies.add(entityToMod.get(entity.toLowerCase(Locale.ROOT)));
            }
            for (String trigger : badTriggers) {
                missingDependencies.add(triggerToMod.get(trigger.toLowerCase(Locale.ROOT)));
            }
            for (String effect : badEffects) {
                missingDependencies.add(effectToMod.get(effect.toLowerCase(Locale.ROOT)));
            }
            for (String styleground : badSGs) {
                missingDependencies.add(assetToMod.get(("Graphics/Atlases/Gameplay/" + styleground + ".png").toLowerCase(Locale.ROOT)));
            }
            for (String decal : badDecals) {
                missingDependencies.add(assetToMod.get(("Graphics/Atlases/Gameplay/decals/" + decal + ".png").toLowerCase(Locale.ROOT)));
            }
        }
    }

    private static void checkMapEditorEntities(String mapEditor, Set<String> availableEntities, Set<String> availableTriggers, Set<String> availableEffects,
                                               Map<String, Map<String, Object>> databaseContents, String dep, String depUrl) throws IOException {
        File modFilesDatabaseEditorFile = new File("modfilesdatabase/" +
                databaseContents.get(dep).get("GameBananaType") + "/" +
                databaseContents.get(dep).get("GameBananaId") + "/" + mapEditor + "_" +
                depUrl.substring("https://gamebanana.com/mmdl/".length()) + ".yaml");

        if (modFilesDatabaseEditorFile.isFile()) {
            // there is! load the entities, triggers and effects from it.
            logger.debug("Loading {} entities, triggers and effects from dependency {} (file {})...", mapEditor, dep, modFilesDatabaseEditorFile.getAbsolutePath());
            try (InputStream databaseFile = new FileInputStream(modFilesDatabaseEditorFile)) {
                Map<String, List<String>> entitiesList = new Yaml().load(databaseFile);
                availableEntities.addAll(entitiesList.get("Entities"));
                availableTriggers.addAll(entitiesList.get("Triggers"));
                availableEffects.addAll(entitiesList.get("Effects"));
            }
        }
    }

    private static void checkForMissingEntities(Set<String> availableEntities, String tagName, Set<String> badEntities, Document document) {
        // list all the <entities> tags.
        NodeList entities = document.getElementsByTagName(tagName);
        for (int i = 0; i < entities.getLength(); i++) {
            // ... go through its children...
            Node entityBlock = entities.item(i);
            for (int j = 0; j < entityBlock.getChildNodes().getLength(); j++) {
                // ... and check if this is an entity that exists, replacing "_" with "/" since BinToXML turns "/" into "_" for XML escaping purposes.
                String entityName = entityBlock.getChildNodes().item(j).getNodeName();
                if (!availableEntities.contains(entityName) && !availableEntities.contains(entityName.replace("_", "/"))) {
                    badEntities.add(entityName.replace("_", "/"));
                }
            }
        }
    }

    /**
     * Checks for missing fonts for all vanilla languages.
     *
     * @param modZip The mod zip
     * @return A map containing [language name] => [all missing characters in a string]
     * @throws IOException If an error occurred while reading files from a zip
     */
    private static Map<String, String> checkForMissingFonts(ZipFile modZip) throws IOException {
        Map<String, String> issues = new HashMap<>();

        checkForMissingFonts(modZip, "renogare64", "Brazilian Portuguese", issues);
        checkForMissingFonts(modZip, "renogare64", "English", issues);
        checkForMissingFonts(modZip, "renogare64", "French", issues);
        checkForMissingFonts(modZip, "renogare64", "German", issues);
        checkForMissingFonts(modZip, "renogare64", "Italian", issues);
        checkForMissingFonts(modZip, "japanese", "Japanese", issues);
        checkForMissingFonts(modZip, "korean", "Korean", issues);
        checkForMissingFonts(modZip, "russian", "Russian", issues);
        checkForMissingFonts(modZip, "chinese", "Simplified Chinese", issues);
        checkForMissingFonts(modZip, "renogare64", "Spanish", issues);

        return issues;
    }

    /**
     * Checks for missing fonts in the specified language, and adds an entry to issues if characters are missing.
     *
     * @param modZip       The mod zip
     * @param languageName The name for the fnt file in Dialog/Fonts
     * @param txtName      The name for the language file in Dialog
     * @param issues       The map collecting missing characters in all languages
     * @throws IOException If an error occurred while reading files from a zip
     */
    private static void checkForMissingFonts(ZipFile modZip, String languageName, String txtName, Map<String, String> issues) throws IOException {
        ZipEntry dialogFile = modZip.getEntry("Dialog/" + txtName + ".txt");
        if (dialogFile != null) {
            logger.debug("Scanning {}.txt for missing fonts", txtName);
            String dialog = IOUtils.toString(modZip.getInputStream(dialogFile), UTF_8);

            Set<Integer> existingCodePoints;

            // get which characters exist in vanilla.
            // vanilla-fonts has the same contents as https://github.com/max4805/RandomStuffWebsite/tree/main/src/main/webapp/WEB-INF/classes/font-generator/vanilla
            try (InputStream is = ModStructureVerifier.class.getResourceAsStream("/vanilla-fonts/" + languageName + ".fnt")) {
                existingCodePoints = readFontFile(is);
                logger.debug("Read {} code points from vanilla-fonts/{}.fnt", existingCodePoints.size(), languageName);
            }

            // add characters that are added by the mod to that (if it has any of course!).
            ZipEntry fontFile = modZip.getEntry("Dialog/Fonts/" + languageName + ".fnt");
            if (fontFile != null) {
                try (InputStream is = modZip.getInputStream(fontFile)) {
                    existingCodePoints.addAll(readFontFile(is));
                    logger.debug("Added code points from Dialog/Fonts/{}.fnt, we now have {}", languageName, existingCodePoints.size());
                } catch (IOException e) {
                    logger.warn("Could not parse font file from Dialog/Fonts/{}.fnt!", languageName, e);
                }
            }

            final String missingCharacters = dialog.codePoints()
                    .filter(c -> !existingCodePoints.contains(c))
                    .mapToObj(c -> new String(new int[]{c}, 0, 1))
                    .distinct()
                    .filter(s -> s.matches("\\P{C}")) // not control characters!
                    .collect(Collectors.joining());

            logger.debug("Found {} missing character(s) for {}.", missingCharacters.length(), txtName);

            if (!missingCharacters.isEmpty()) {
                // there ARE missing characters!
                issues.put(languageName, missingCharacters);
            }
        }
    }

    /**
     * Reads a font file from an input stream, and extracts all character code points defined in it.
     *
     * @param fontFile The stream allowing to read the font file
     * @return All code points defined in the font file
     * @throws IOException If an error occurs while reading the file, or if the file is invalid
     */
    private static Set<Integer> readFontFile(InputStream fontFile) throws IOException {
        try {
            // parse the XML
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document document = db.parse(fontFile);

            // get the list of existing codes
            Set<Integer> existingCodes = new HashSet<>();
            NodeList chars = document.getElementsByTagName("char");
            for (int i = 0; i < chars.getLength(); i++) {
                Node charItem = chars.item(i);
                existingCodes.add(Integer.parseInt(charItem.getAttributes().getNamedItem("id").getNodeValue()));
            }
            return existingCodes;
        } catch (SAXException | ParserConfigurationException | NumberFormatException e) {
            throw new IOException(e);
        }
    }

    // == delete when SJ is out -- start

    private static void addStuffFromSJ2021(Set<String> availableEntities, Set<String> availableTriggers, Set<String> availableEffects,
                                           String whereIsStrawberryJam, boolean isHtml) throws IOException {

        List<String> ahornEntities = new LinkedList<>();
        List<String> ahornTriggers = new LinkedList<>();
        List<String> ahornEffects = new LinkedList<>();

        File modZip = new File("mod-ahornscan-sj-" + System.currentTimeMillis() + ".zip");

        // download file (pretending we are Firefox since Discord hates Java and responds 403 to it for some reason)
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(modZip))) {
            HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(whereIsStrawberryJam);
            connection.setDoInput(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:62.0) Gecko/20100101 Firefox/62.0");
            connection.connect();

            try (InputStream is = connection.getInputStream()) {
                IOUtils.copy(is, os);
            }
        }

        // scan its contents, opening Ahorn plugin files
        try (ZipFile zipFile = new ZipFile(modZip)) {
            final Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry entry = zipEntries.nextElement();
                if (entry.getName().startsWith("Ahorn/") && entry.getName().endsWith(".jl")) {
                    InputStream inputStream = zipFile.getInputStream(entry);
                    extractAhornEntities(ahornEntities, ahornTriggers, ahornEffects, entry.getName(), inputStream, isHtml);
                }
            }

            logger.info("Found {} Ahorn entities, {} triggers, {} effects in SJ2021.",
                    ahornEntities.size(), ahornTriggers.size(), ahornEffects.size());
        } catch (IOException | IllegalArgumentException e) {
            logger.error("Could not analyze Ahorn plugins from SJ2021", e);
            throw new IOException(e);
        }

        // merge the result into available entities.
        availableEntities.addAll(ahornEntities);
        availableTriggers.addAll(ahornTriggers);
        availableEffects.addAll(ahornEffects);

        FileUtils.forceDelete(modZip);
    }

    // literal copy-paste from update checker code
    private static void extractAhornEntities(List<String> ahornEntities, List<String> ahornTriggers, List<String> ahornEffects,
                                             String file, InputStream inputStream, boolean isHtml) throws IOException {

        Pattern mapdefMatcher = Pattern.compile(".*@mapdef [A-Za-z]+ \"([^\"]+)\".*");
        Pattern pardefMatcher = Pattern.compile(".*Entity\\(\"([^\"]+)\".*");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                String entityID = null;

                Matcher mapdefMatch = mapdefMatcher.matcher(line);
                if (mapdefMatch.matches()) {
                    entityID = mapdefMatch.group(1);
                }
                Matcher pardefMatch = pardefMatcher.matcher(line);
                if (pardefMatch.matches()) {
                    entityID = pardefMatch.group(1);
                }

                if (entityID != null) {
                    if (file.startsWith("Ahorn/effects/")) {
                        ahornEffects.add(entityID);
                    } else if (file.startsWith("Ahorn/entities/")) {
                        ahornEntities.add(entityID);
                    } else if (file.startsWith("Ahorn/triggers/")) {
                        ahornTriggers.add(entityID);
                    }
                }
            }
        }
    }

    // == delete when SJ is out -- end (also clean up secrets that are left unused)

    private static void parseProblematicPaths(List<String> problemList, Set<String> websiteProblemList,
                                              String websiteProblem, String problemLabel, List<String> paths, boolean isHtml) {
        logger.debug("{}: {}", problemLabel, paths);

        if (paths.size() != 0) {
            websiteProblemList.add(websiteProblem);
        }


        // just a formatting method: to display "x", "x and y" or "x and 458 others" depending on how many problematic paths there are.
        if (paths.size() == 1) {
            problemList.add(problemLabel + ": "
                    + formatProblematicThing(isHtml, paths.get(0)));
        } else if (paths.size() == 2) {
            problemList.add(problemLabel + ": "
                    + formatProblematicThing(isHtml, paths.get(0))
                    + " and " + formatProblematicThing(isHtml, paths.get(1)));
        } else if (paths.size() == 3) {
            problemList.add(problemLabel + ": "
                    + formatProblematicThing(isHtml, paths.get(0))
                    + ", " + formatProblematicThing(isHtml, paths.get(1))
                    + " and " + formatProblematicThing(isHtml, paths.get(2)));
        } else if (paths.size() == 4) {
            problemList.add(problemLabel + ": "
                    + formatProblematicThing(isHtml, paths.get(0))
                    + ", " + formatProblematicThing(isHtml, paths.get(1))
                    + ", " + formatProblematicThing(isHtml, paths.get(2))
                    + " and " + formatProblematicThing(isHtml, paths.get(3)));
        } else if (paths.size() > 4) {
            problemList.add(problemLabel + ": " + formatProblematicThing(isHtml, paths.get(0))
                    + ", " + formatProblematicThing(isHtml, paths.get(1))
                    + ", " + formatProblematicThing(isHtml, paths.get(2))
                    + " and " + (paths.size() - 3) + " others");
        }
    }

    private static String formatProblematicThing(boolean isHtml, String thing) {
        if (isHtml) {
            return "<code>" + StringEscapeUtils.escapeHtml4(thing) + "</code>";
        } else {
            return "`" + thing.replace("`", "") + "`";
        }
    }

    private static void parseAdminCommand(MessageReceivedEvent event) {
        String msg = event.getMessage().getContentRaw();
        if (msg.startsWith("--")) {
            // for logging purposes
            logger.debug("{} sent something looking like a command: {}", event.getMember(), msg);
        }

        if (msg.equals("--help")) {
            event.getChannel().sendMessage("`--setup-fixed-names [response channel] [collab assets folder name] [collab maps folder name]`\n" +
                    "will tell the bot to analyze all the .zip files in the current channel, and to post issues in [response channel]." +
                    "\n- [collab assets folder name] should identify the collab/contest and be alphanumeric. It will have to be used for asset folders (graphics, tutorials, etc)." +
                    "\n- [collab maps folder name] is the name of the folder the collab/contest map bins will be in." +
                    " It has to be alphanumeric, and can be identical to the assets folder name.\n\n" +
                    "`--setup-free-names [response channel]`\n" +
                    "allows everyone on the server to use the `--verify` command in the channel it is posted in, and will post issues in [response channel].\n" +
                    "This allows people to verify their mods with the folder names they want.\n\n" +
                    "`--setup-no-name [response channel]`\n" +
                    "will tell the bot to analyze all the .zip files in the current channel, and to post issues in [response channel].\n" +
                    "No checks will be done on folder names, and multiple maps are allowed.\n\n" +
                    "`--verify [assets folder name] [maps folder name]`\n" +
                    "will verify the given map (as an attachment, or as a Google Drive link) with the given parameters, with the same checks as collabs. Both names should be alphanumeric.\n\n" +
                    "`--generate-font [language]`\n" +
                    "will generate font files with all missing characters from the vanilla font. You should send your dialog file along with this command. `[language]` is one of `chinese`, `japanese`, `korean`, `renogare` or `russian`.\n\n" +
                    "`--remove-setup`\n" +
                    "will tell the bot to stop analyzing the .zip files in the current channel.\n\n" +
                    "`--rules`\n" +
                    "will print out the checks run by the bot in the current channel.\n\n" +
                    "`--reactions`\n" +
                    "will give a message explaining what the different reactions from the bot mean.").queue();
        } else if (msg.startsWith("--setup-fixed-names ")) {
            // setting up a new channel!
            String[] settings = msg.split(" ");
            boolean valid = false;

            // there should be 4 parts (so 3 parameters including --setup-fixed-names itself).
            if (settings.length == 4) {
                // parameter 1 should be a channel mention.
                Matcher regex = Pattern.compile("^<#!?([0-9]+)>$").matcher(settings[1]);
                if (regex.matches()) {
                    String channelId = regex.group(1);

                    // parameters 2 and 3 should be alphanumeric.
                    if (event.getGuild().getTextChannelById(channelId) != null && settings[2].matches("[A-Za-z0-9]+")
                            && settings[3].matches("[A-Za-z0-9]+")) {

                        // save the new association!
                        valid = true;
                        responseChannels.put(event.getChannel().getIdLong(), Long.parseLong(channelId));
                        collabAssetPrefixes.put(event.getChannel().getIdLong(), settings[2]);
                        collabMapPrefixes.put(event.getChannel().getIdLong(), settings[3]);

                        saveMap(event, ":white_check_mark: The bot will check zips posted in this channel against those rules:\n"
                                + getRules(event.getChannel().getIdLong()) + "\n\nAny issue found will be posted in <#" + channelId + ">.");
                    }
                }
            }

            if (!valid) {
                // print help if one of the parameters is invalid.
                event.getChannel().sendMessage("Usage: `--setup-fixed-names [response channel] [collab assets folder name] [collab maps folder name]`\n" +
                        "[response channel] should be a mention, and folder names should be alphanumeric.").queue();
            }
        } else if (msg.startsWith("--setup-free-names ")) {
            // setting up a new free use channel!
            String[] settings = msg.split(" ");
            boolean valid = false;

            // there should be 2 parts (so 1 parameter including --setup-free-names itself).
            if (settings.length == 2) {
                // parameter 1 should be a channel mention.
                Matcher regex = Pattern.compile("^<#!?([0-9]+)>$").matcher(settings[1]);
                if (regex.matches()) {
                    String channelId = regex.group(1);
                    if (event.getGuild().getTextChannelById(channelId) != null) {
                        valid = true;
                        freeResponseChannels.put(event.getChannel().getIdLong(), Long.parseLong(channelId));
                        saveMap(event, ":white_check_mark: Everyone will be able to use the `--verify [assets folder name] [maps folder name]` " +
                                "command in this channel to check the structure of their mod against those rules:\n" + getRules(null)
                                + "\n\nAny issue found will be posted in <#" + channelId + ">.");
                    }
                }
            }

            if (!valid) {
                // print help if one of the parameters is invalid.
                event.getChannel().sendMessage("Usage: `--setup-free-names [response channel]`\n" +
                        "[response channel] should be a mention.").queue();
            }
        } else if (msg.startsWith("--setup-no-name ")) {
            // setting up a no-name channel!
            String[] settings = msg.split(" ");
            boolean valid = false;

            // there should be 2 parts (so 1 parameter including --setup-no-names itself).
            if (settings.length == 2) {
                // parameter 1 should be a channel mention.
                Matcher regex = Pattern.compile("^<#!?([0-9]+)>$").matcher(settings[1]);
                if (regex.matches()) {
                    String channelId = regex.group(1);
                    if (event.getGuild().getTextChannelById(channelId) != null) {
                        valid = true;
                        noNameResponseChannels.put(event.getChannel().getIdLong(), Long.parseLong(channelId));
                        saveMap(event, ":white_check_mark: The bot will check zips posted in this channel against those rules:\n"
                                + getRulesForNoFolderName() + "\n\nAny issue found will be posted in <#" + channelId + ">.");
                    }
                }
            }

            if (!valid) {
                // print help if one of the parameters is invalid.
                event.getChannel().sendMessage("Usage: `--setup-free-names [response channel]`\n" +
                        "[response channel] should be a mention.").queue();
            }
        } else if (msg.equals("--remove-setup")) {
            if (responseChannels.containsKey(event.getChannel().getIdLong()) || freeResponseChannels.containsKey(event.getChannel().getIdLong())) {
                // forget about the channel.
                responseChannels.remove(event.getChannel().getIdLong());
                collabAssetPrefixes.remove(event.getChannel().getIdLong());
                collabMapPrefixes.remove(event.getChannel().getIdLong());
                freeResponseChannels.remove(event.getChannel().getIdLong());
                saveMap(event, ":white_check_mark: The bot will not scan zips posted in this channel anymore.");
            } else {
                event.getChannel().sendMessage(":x: The bot is not set up to scan zips sent to this channel.").queue();
            }
        } else if (msg.equals("--rules")) {
            if (noNameResponseChannels.containsKey(event.getChannel().getIdLong())) {
                event.getChannel().sendMessage("Here is what the bot checks in submitted zips: \n" + getRulesForNoFolderName()).queue();
            } else if (responseChannels.containsKey(event.getChannel().getIdLong())) {
                event.getChannel().sendMessage("Here is what the bot checks in submitted zips: \n" + getRules(event.getChannel().getIdLong())).queue();
            } else if (freeResponseChannels.containsKey(event.getChannel().getIdLong())) {
                event.getChannel().sendMessage("Here is what the bot checks in submitted zips: \n" + getRules(null)).queue();
            } else {
                event.getChannel().sendMessage(":x: The bot is not set up to scan zips sent to this channel.").queue();
            }
        } else if (msg.equals("--reactions")) {
            if (responseChannels.containsKey(event.getChannel().getIdLong())) {
                event.getChannel().sendMessage(
                        "When analyzing your zips, the bot will add reactions to it:\n" +
                                ":thinking: = your zip is being analyzed, please wait...\n" +
                                ":ok_hand: = your zip passed inspection with no issues!\n" +
                                ":x: = there are issues with your zip, you should get pinged about it in <#" + responseChannels.get(event.getChannel().getIdLong()) + ">.\n" +
                                ":bomb: = the bot exploded! max480 will look into that soon.").queue();
            } else {
                event.getChannel().sendMessage(":x: The bot is not set up to scan zips sent to this channel.").queue();
            }
        }
    }

    private static void saveMap(MessageReceivedEvent event, String successMessage) {
        try {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(CHANNELS_SAVE_FILE_NAME))) {
                for (Long channelId : responseChannels.keySet()) {
                    writer.write(channelId + ";" + responseChannels.get(channelId) + ";" + collabAssetPrefixes.get(channelId) + ";" + collabMapPrefixes.get(channelId) + "\n");
                }
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(FREE_CHANNELS_SAVE_FILE_NAME))) {
                for (Long channelId : freeResponseChannels.keySet()) {
                    writer.write(channelId + ";" + freeResponseChannels.get(channelId) + "\n");
                }
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(NO_NAME_CHANNELS_SAVE_FILE_NAME))) {
                for (Long channelId : noNameResponseChannels.keySet()) {
                    writer.write(channelId + ";" + noNameResponseChannels.get(channelId) + "\n");
                }
            }

            if (event != null) {
                event.getChannel().sendMessage(successMessage).queue();
            }
        } catch (IOException e) {
            logger.error("Error while writing file", e);

            if (event != null) {
                event.getChannel().sendMessage(":x: A technical error occurred.").queue();
                event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                        .sendMessage("Error occurred while saving response channels list: " + event.getGuild().getName()).queue();
            }
        }
    }

    private static void savePostedMessagesMap(Event event) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(MESSAGES_TO_ANSWERS_FILE_NAME))) {
            for (Map.Entry<Long, Long> entry : messagesToEmbeds.entrySet()) {
                writer.write(entry.getKey() + ";" + entry.getValue() + "\n");
            }
        } catch (IOException e) {
            logger.error("Error while writing file", e);

            if (event != null) {
                event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                        .sendMessage("An error occurred while saving sent messages list: " + e).queue();
            }
        }
    }

    private static String getRules(Long channelId) {
        String collabAssetsName = "[assets folder name]";
        String collabMapsName = "[maps folder name]";
        if (channelId != null) {
            collabAssetsName = collabAssetPrefixes.get(channelId);
            collabMapsName = collabMapPrefixes.get(channelId);
        }
        return "- files in `Assets/`, `Graphics/Atlases/`, `Graphics/ColorGrading/` and `Tutorials/` should have this path: `[basePath]/" + collabAssetsName + "/[subfolder]/[anything]`\n" +
                "- XMLs in `Graphics/` should match: `Graphics/" + collabAssetsName + "xmls/[subfolder]/[anything].xml`\n" +
                "- there should be exactly 1 file in the `Maps` folder, and its path should match: `Maps/" + collabMapsName + "/[subfolder]/[anything].bin`\n" +
                "- if there is an `English.txt`, dialog IDs should match: `" + collabAssetsName + "_[anything]_[anything]`" +
                (collabMapsName.equals(collabAssetsName) ? "" : "or `" + collabMapsName + "_[anything]_[anything]`") + "\n" +
                getRulesForNoFolderName();
    }

    private static String getRulesForNoFolderName() {
        return "- `everest.yaml` should exist and should be valid according to the everest.yaml validator\n" +
                "- all decals, stylegrounds, entities, triggers and effects should be vanilla, packaged with the mod, or from one of the everest.yaml dependencies\n" +
                "- the dialog files for vanilla languages should not contain characters that are missing from the game's font, or those extra characters should be included in the zip";
    }

    private static String pickFormat(boolean isHtml, String html, String md) {
        return isHtml ? html : md;
    }

    static void registerChannelFromSupportServer(Long channelId) {
        logger.info("Registering channel {} as free response and no-name response for support server", channelId);
        freeResponseChannels.put(channelId, channelId);
        noNameResponseChannels.put(channelId, channelId);
        saveMap(null, null);
    }
}
