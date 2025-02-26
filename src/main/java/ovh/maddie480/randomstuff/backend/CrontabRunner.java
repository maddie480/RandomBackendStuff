package ovh.maddie480.randomstuff.backend;

import net.dv8tion.jda.api.requests.GatewayIntent;
import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.EventListener;
import ovh.maddie480.everest.updatechecker.Main;
import ovh.maddie480.everest.updatechecker.ServerConfig;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.randomstuff.backend.celeste.FrontendTaskReceiver;
import ovh.maddie480.randomstuff.backend.celeste.crontabs.*;
import ovh.maddie480.randomstuff.backend.discord.crontabs.*;
import ovh.maddie480.randomstuff.backend.discord.modstructureverifier.ModStructureVerifier;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.QuestCommunityBot;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.daily.*;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.hourly.BusUpdateChecker;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.hourly.TemperatureChecker;
import ovh.maddie480.randomstuff.backend.discord.serverjanitor.ServerJanitorBot;
import ovh.maddie480.randomstuff.backend.discord.timezonebot.TimezoneBot;
import ovh.maddie480.randomstuff.backend.streams.apis.IChatProvider;
import ovh.maddie480.randomstuff.backend.streams.apis.TwitchChatProvider;
import ovh.maddie480.randomstuff.backend.streams.apis.YouTubeChatProvider;
import ovh.maddie480.randomstuff.backend.streams.features.LNJBot;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.DiscardableJDA;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The class responsible for running all recurring processes,
 * and sending out alerts if one of them crashes.
 */
public class CrontabRunner {
    private static final Logger logger = LoggerFactory.getLogger(CrontabRunner.class);

    public static void main(String[] args) {
        // load update checker config from secret constants
        ByteArrayInputStream is = new ByteArrayInputStream(SecretConstants.UPDATE_CHECKER_CONFIG.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> config = YamlUtil.load(is);
        Main.serverConfig = new ServerConfig(config);

        String arg = args != null && args.length > 0 ? args[0] : "";

        if (arg.equals("--daily")) {
            runDailyProcesses();
            sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":white_check_mark: Daily processes completed!");
            System.exit(0);
            return;
        }

        if (arg.equals("--hourly")) {
            runHourlyProcesses();
            return;
        }

        // redirect logs to a file
        redirectLogsToFile(args[0]);

        // start the health checks
        ContinuousHealthChecks.startChecking();

        // start communication channel with the frontend
        FrontendTaskReceiver.start();

        // start the updater
        new Thread("Update Checker") {
            @Override
            public void run() {
                updaterLoop();
            }
        }.start();

        try {
            // start the Timezone Bot, Mod Structure Verifier and Quest Community Bot
            TimezoneBot.main(null);
            ModStructureVerifier.main(null);
            new QuestCommunityBot();
        } catch (Exception e) {
            logger.error("Error while starting up the bots", e);
            sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":x: Could not start up the bots: " + e);
        }
    }

    private static void redirectLogsToFile(String logsDirectory) {
        new Thread("Log Rotator") {
            @Override
            public void run() {
                PrintStream currentOut = null;
                PrintStream currentErr = null;

                try {
                    while (true) {
                        long waitTime;

                        {
                            // open new System.out and System.err streams based on the current date
                            String date = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                            PrintStream newOut = new PrintStream(logsDirectory + File.separator + date + "_out.backend.log");
                            PrintStream newErr = new PrintStream(logsDirectory + File.separator + date + "_err.backend.log");
                            System.setOut(newOut);
                            System.setErr(newErr);
                            logger.info("Redirected System.out to file {}/{}_out.backend.log", logsDirectory, date);

                            // leave the old System.out and System.err open for a minute after the switch,
                            // to leave time to ongoing processes to finish what they are doing with them
                            if (currentOut != null) {
                                Thread.sleep(60000);
                                currentOut.close();
                                currentErr.close();
                                logger.info("Closed old System.out and System.err streams");
                            }
                            currentOut = newOut;
                            currentErr = newErr;

                            long target = ZonedDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
                                    .toInstant().toEpochMilli();
                            waitTime = target - System.currentTimeMillis();
                        }

                        logger.info("Waiting for {} ms before rotating logs", waitTime);
                        Thread.sleep(waitTime);
                    }
                } catch (FileNotFoundException | InterruptedException e) {
                    logger.error("Could not redirect trace to log file", e);
                }
            }
        }.start();
    }

    private static void runDailyProcesses() {
        // This test has a tendency to OOM, get it out of the way right away
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkBananaMirrorDatabaseMatch()", () -> CelesteStuffHealthCheck.checkBananaMirrorDatabaseMatch());

        // Update tasks
        runProcessAndAlertOnException("MembershipNotifier.main(null)", () -> MembershipNotifier.main(null));
        runProcessAndAlertOnException("TimezoneBot.leaveDeadServerIfNecessary()", () -> TimezoneBot.leaveDeadServerIfNecessary());
        runProcessAndAlertOnException("CustomSlashCommandsCleanup.housekeep()", () -> CustomSlashCommandsCleanup.housekeep());
        runProcessAndAlertOnException("ArbitraryModAppCacher.refreshArbitraryModAppCache()", () -> ArbitraryModAppCacher.refreshArbitraryModAppCache());
        runProcessAndAlertOnException("CustomEntityCatalogGenerator.main(null)", () -> CustomEntityCatalogGenerator.main(null));
        runProcessAndAlertOnException("ServerJanitorBot.main(null)", () -> ServerJanitorBot.main(null));
        runProcessAndAlertOnException("housekeepArbitraryModApp()", () -> housekeepArbitraryModApp());
        runProcessAndAlertOnException("AssetDriveService.listAllFiles()", () -> AssetDriveService.listAllFiles());
        runProcessAndAlertOnException("AssetDriveService.rsyncFiles()", () -> AssetDriveService.rsyncFiles());
        runProcessAndAlertOnException("AssetDriveService.classifyAssets()", () -> AssetDriveService.classifyAssets());
        runProcessAndAlertOnException("ServerCountUploader.run()", () -> ServerCountUploader.run());

        // Health Checks
        runProcessAndAlertOnException("GameBananaAutomatedChecks.checkUnapprovedCategories()", () -> GameBananaAutomatedChecks.checkUnapprovedCategories());
        runProcessAndAlertOnException("WorldClockHealthCheck.main(null)", () -> WorldClockHealthCheck.main(null));
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkEverestExists(true)", () -> CelesteStuffHealthCheck.checkEverestExists(true));
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkOlympusExists(true)", () -> CelesteStuffHealthCheck.checkOlympusExists(true));
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkLoennVersionsListAPI()", () -> CelesteStuffHealthCheck.checkLoennVersionsListAPI());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkFontGeneratorBMFont()", () -> CelesteStuffHealthCheck.checkFontGeneratorBMFont());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkFontGeneratorBMFontCustom()", () -> CelesteStuffHealthCheck.checkFontGeneratorBMFontCustom());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkModStructureVerifier()", () -> CelesteStuffHealthCheck.checkModStructureVerifier());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkMapTreeViewer()", () -> CelesteStuffHealthCheck.checkMapTreeViewer());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkFileSearcher()", () -> CelesteStuffHealthCheck.checkFileSearcher());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkDirectLinkService()", () -> CelesteStuffHealthCheck.checkDirectLinkService());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkStaticPages()", () -> CelesteStuffHealthCheck.checkStaticPages());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkGameBananaCategories()", () -> CelesteStuffHealthCheck.checkGameBananaCategories());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.everestYamlValidatorHealthCheck()", () -> CelesteStuffHealthCheck.everestYamlValidatorHealthCheck());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkSmallerGameBananaAPIs()", () -> CelesteStuffHealthCheck.checkSmallerGameBananaAPIs());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkSrcModUpdateNotificationsPage()", () -> CelesteStuffHealthCheck.checkSrcModUpdateNotificationsPage());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkDiscordBotsPage()", () -> CelesteStuffHealthCheck.checkDiscordBotsPage());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkCelesteNewsNetworkSubscriptionService()", () -> CelesteStuffHealthCheck.checkCelesteNewsNetworkSubscriptionService());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkCollabList()", () -> CelesteStuffHealthCheck.checkCollabList());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkCustomEntityCatalog()", () -> CelesteStuffHealthCheck.checkCustomEntityCatalog());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkOlympusNews()", () -> CelesteStuffHealthCheck.checkOlympusNews());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkAssetDriveBrowser()", () -> CelesteStuffHealthCheck.checkAssetDriveBrowser());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkWipeConverter()", () -> CelesteStuffHealthCheck.checkWipeConverter());
        runProcessAndAlertOnException("checkArbitraryModApp()", () -> checkArbitraryModApp());
        runProcessAndAlertOnException("checkRadioLNJ()", () -> checkRadioLNJ());
        runProcessAndAlertOnException("LNJBot.healthCheck()", () -> LNJBot.healthCheck());
        runProcessAndAlertOnException("checkLNJEmotes()", () -> checkLNJEmotes());
        runProcessAndAlertOnException("checkChatProviderCanConnect(new TwitchChatProvider())", () -> checkChatProviderCanConnect(new TwitchChatProvider()));
        // runProcessAndAlertOnException("checkChatProviderCanConnect(new YouTubeChatProvider(() -> logger.info(\"Giving up!\")))", () -> checkChatProviderCanConnect(new YouTubeChatProvider(() -> logger.info("Giving up!"))));

        // Non-Celeste Stuff
        runProcessAndAlertOnException("ChangeBGToRandom.run()", () -> ChangeBGToRandom.run());
        runProcessAndAlertOnException("PurgePosts.run()", () -> PurgePosts.run());
        runProcessAndAlertOnException("QuestCommunityWebsiteHealthCheck.run()", () -> QuestCommunityWebsiteHealthCheck.run());
        runProcessAndAlertOnException("SlashCommandBotHealthCheck.checkSlashCommands()", () -> SlashCommandBotHealthCheck.checkSlashCommands());

        runProcessAndAlertOnException("StonkUpdateChecker.postTo(client.getTextChannelById(551822297573490749L))", () -> {
            try (DiscardableJDA client = new DiscardableJDA(SecretConstants.QUEST_COMMUNITY_BOT_TOKEN)) {
                StonkUpdateChecker.postTo(client.getTextChannelById(551822297573490749L));
            }
        });

        runProcessAndAlertOnException("PlatformBackup.run(client)", () -> {
            try (DiscardableJDA client = new DiscardableJDA(SecretConstants.QUEST_COMMUNITY_BOT_TOKEN, GatewayIntent.MESSAGE_CONTENT)) {
                PlatformBackup.run(client);
            }
        });

        runProcessAndAlertOnException("PrivateDiscordJanitor.runDaily()", () -> PrivateDiscordJanitor.runDaily());
    }

    private static void runHourlyProcesses() {
        // load #celeste_news_network state from disk
        runProcessAndAlertOnException("MastodonUpdateChecker.loadFile()", () -> MastodonUpdateChecker.loadFile());
        runProcessAndAlertOnException("TwitterUpdateChecker.loadFile()", () -> TwitterUpdateChecker.loadFile());
        runProcessAndAlertOnException("OlympusNewsUpdateChecker.loadPreviouslyPostedNews()", () -> OlympusNewsUpdateChecker.loadPreviouslyPostedNews());

        // Update tasks
        runProcessAndAlertOnException("UpdateCheckerTracker.updatePrivateHelpersFromGitHub()", () -> UpdateCheckerTracker.updatePrivateHelpersFromGitHub());
        runProcessAndAlertOnException("CollabAutoHider.run()", () -> CollabAutoHider.run());
        runProcessAndAlertOnException("TempFolderCleanup.cleanUpFolder(\"/shared/temp\", 1, path -> true)", () -> TempFolderCleanup.cleanUpFolder("/shared/temp", 1, path -> true));
        runProcessAndAlertOnException("TempFolderCleanup.cleanUpFolder(\"/logs\", 30, path -> path.getFileName().toString().endsWith(\".backend.log\"))", () -> TempFolderCleanup.cleanUpFolder("/logs", 30, path -> path.getFileName().toString().endsWith(".backend.log")));
        runProcessAndAlertOnException("TempFolderCleanup.cleanUpFolder(\"/logs\", 1, path -> path.getFileName().toString().endsWith(\".autodeploy.log\"))", () -> TempFolderCleanup.cleanUpFolder("/logs", 1, path -> path.getFileName().toString().endsWith(".autodeploy.log")));
        runProcessAndAlertOnException("UsageStatsService.writeWeeklyStatisticsToFile()", () -> UsageStatsService.writeWeeklyStatisticsToFile());
        runProcessAndAlertOnException("MastodonUpdateChecker.checkForUpdates()", () -> MastodonUpdateChecker.checkForUpdates());
        runProcessAndAlertOnException("TwitterUpdateChecker.checkForUpdates()", () -> TwitterUpdateChecker.checkForUpdates());
        runProcessAndAlertOnException("OlympusNewsUpdateChecker.checkForUpdates()", () -> OlympusNewsUpdateChecker.checkForUpdates());
        runProcessAndAlertOnException("LoennVersionLister.update()", () -> LoennVersionLister.update());
        runProcessAndAlertOnException("TopGGCommunicator.refreshVotes(message -> CrontabRunner.sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, message))", () -> TopGGCommunicator.refreshVotes(message -> CrontabRunner.sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, message)));
        runProcessAndAlertOnException("PrivateDiscordJanitor.runHourly()", () -> PrivateDiscordJanitor.runHourly());

        // GameBanana automated checks
        runProcessAndAlertOnException("GameBananaAutomatedChecks.checkYieldReturnOrigAndIntPtrTrick()", () -> GameBananaAutomatedChecks.checkYieldReturnOrigAndIntPtrTrick());
        runProcessAndAlertOnException("GameBananaAutomatedChecks.checkForForbiddenFiles()", () -> GameBananaAutomatedChecks.checkForForbiddenFiles());
        runProcessAndAlertOnException("GameBananaAutomatedChecks.checkForFilesBelongingToMultipleMods()", () -> GameBananaAutomatedChecks.checkForFilesBelongingToMultipleMods());
        runProcessAndAlertOnException("GameBananaAutomatedChecks.checkAllModsWithEverestYamlValidator()", () -> GameBananaAutomatedChecks.checkAllModsWithEverestYamlValidator());
        runProcessAndAlertOnException("GameBananaAutomatedChecks.checkPngFilesArePngFiles()", () -> GameBananaAutomatedChecks.checkPngFilesArePngFiles());
        runProcessAndAlertOnException("GameBananaAutomatedChecks.checkDuplicateModIdsCaseInsensitive()", () -> GameBananaAutomatedChecks.checkDuplicateModIdsCaseInsensitive());

        // Health checks
        runProcessAndAlertOnException("CelesteStuffHealthCheck.updateCheckerHealthCheck()", () -> CelesteStuffHealthCheck.updateCheckerHealthCheck());
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkEverestExists(false)", () -> CelesteStuffHealthCheck.checkEverestExists(false));
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkOlympusExists(false)", () -> CelesteStuffHealthCheck.checkOlympusExists(false));
        runProcessAndAlertOnException("CelesteStuffHealthCheck.checkOlympusAPIs()", () -> CelesteStuffHealthCheck.checkOlympusAPIs());

        // Quest Community Bot stuff
        runProcessAndAlertOnException("BusUpdateChecker.runCheckForUpdates(client.getTextChannelById(551822297573490749L))", () -> {
            try (DiscardableJDA client = new DiscardableJDA(SecretConstants.QUEST_COMMUNITY_BOT_TOKEN)) {
                BusUpdateChecker.runCheckForUpdates(client.getTextChannelById(551822297573490749L));
            }
        });
        runProcessAndAlertOnException("new TemperatureChecker().checkForUpdates(client.getTextChannelById(551822297573490749L))", () -> {
            try (DiscardableJDA client = new DiscardableJDA(SecretConstants.QUEST_COMMUNITY_BOT_TOKEN)) {
                new TemperatureChecker().checkForUpdates(client.getTextChannelById(551822297573490749L));
            }
        });
        runProcessAndAlertOnException("new TwitchUpdateChecker().checkForUpdates(client.getTextChannelById(551822297573490749L))", () -> {
            try (DiscardableJDA client = new DiscardableJDA(SecretConstants.QUEST_COMMUNITY_BOT_TOKEN)) {
                new TwitchUpdateChecker().checkForUpdates(client.getTextChannelById(551822297573490749L));
            }
        });
    }

    private static void updaterLoop() {
        logger.info("Registering Update Checker Tracker...");
        EventListener.addEventListener(new UpdateCheckerTracker());

        while (true) {
            ZonedDateTime runUntil = ZonedDateTime.now(ZoneId.of("UTC")).plusHours(1)
                    .withMinute(0).withSecond(0).withNano(0);

            while (runUntil.getHour() % 6 != 0) {
                runUntil = runUntil.plusHours(1);
            }

            logger.info("Starting updater loop, will stop on {}", runUntil.withZoneSameInstant(ZoneId.systemDefault()));
            boolean full = true;

            while (ZonedDateTime.now().isBefore(runUntil)) {
                runUpdater(full, runUntil);
                full = false;
                unstoppableSleep(120_000);
            }
        }
    }

    private static void runUpdater(boolean fullUpdateCheck, ZonedDateTime giveUpAt) {
        runProcessAndAlertOnException("EverestVersionLister.checkEverestVersions()", giveUpAt, () -> EverestVersionLister.checkEverestVersions());
        runProcessAndAlertOnException("OlympusVersionLister.checkOlympusVersions()", giveUpAt, () -> OlympusVersionLister.checkOlympusVersions());

        runProcessAndAlertOnException("Main.updateDatabase(fullUpdateCheck)", giveUpAt, () -> {
            Main.updateDatabase(fullUpdateCheck);
            UpdateOutgoingWebhooks.notifyUpdate();
        });
    }

    private static void housekeepArbitraryModApp() throws IOException {
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(
                "https://maddie480.ovh/gamebanana/arbitrary-mod-app-housekeep?key=" + SecretConstants.RELOAD_SHARED_SECRET);

        if (connection.getResponseCode() != 200) {
            throw new IOException("Housekeeping arbitrary mod app failed!");
        }
    }

    private static void checkArbitraryModApp() throws IOException {
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/gamebanana/arbitrary-mod-app?_idProfile=1698143")) {
            String result = IOUtils.toString(is, UTF_8);
            if (!result.contains("Jungle Helper") || !result.contains("Collab Utils 2")) {
                throw new IOException("Did not find expected contents in arbitrary mod app!");
            }
        }
    }

    private static void checkRadioLNJ() throws IOException {
        logger.debug("Starting Radio LNJ health check");

        int elementCount;

        try {
            elementCount = Integer.parseInt(Jsoup.connect("https://maddie480.ovh/radio-lnj")
                    .userAgent("Maddie-Random-Stuff-Backend/1.0.0 (+https://github.com/maddie480/RandomBackendStuff)")
                    .get()
                    .select(".header b")
                    .get(0).text());
        } catch (NumberFormatException e) {
            throw new IOException("Did not find the amount of songs where it was expected!");
        }

        logger.debug("Page says there are {} items in the playlist, retrieving it", elementCount);

        JSONObject playlist;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/radio-lnj/playlist.json")) {
            playlist = new JSONObject(new JSONTokener(is));
        }

        if (playlist.getJSONArray("playlist").length() != elementCount) {
            throw new IOException("Amount of elements in playlist is wrong!");
        }

        if (playlist.getInt("seek") > playlist.getJSONArray("playlist").getJSONObject(0).getInt("duration")) {
            throw new IOException("Seek exceeded first song duration!");
        }

        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/radio-lnj/playlist")) {
            if (!IOUtils.toString(is, UTF_8).contains(StringEscapeUtils.escapeHtml4(
                    playlist.getJSONArray("playlist").getJSONObject(0).getString("trackName")))) {

                throw new IOException("Playlist page does not show head of playlist!");
            }
        }

        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/radio-lnj/playlist.m3u");
             BufferedReader br = new BufferedReader(new InputStreamReader(is, UTF_8))) {

            if (!br.readLine().equals("https://maddie480.ovh" + playlist.getJSONArray("playlist").getJSONObject(0).getString("path"))) {
                throw new IOException("m3u head of playlist does not match JSON head of playlist!");
            }
        }

        String url = "https://maddie480.ovh" + playlist.getJSONArray("playlist").getJSONObject(0).getString("path");
        logger.debug("Downloading head of playlist at {}", url);

        try (InputStream is = ConnectionUtils.openStreamWithTimeout(url)) {
            long size = IOUtils.consume(is);

            if (size < 1) {
                throw new IOException("First song in playlist is empty!");
            }

            logger.debug("Head of playlist is {} bytes", size);
        }
    }

    private static void checkLNJEmotes() throws IOException {
        logger.debug("Checking LNJ emotes...");
        Elements emotes = Jsoup.connect("https://maddie480.ovh/lnj-emotes").get().select("table img");
        if (emotes.size() != 29) {
            throw new IOException("Expected 29 LNJ emotes!");
        }

        for (Element emote : emotes) {
            String url = emote.attr("src");
            logger.debug("Checking LNJ emote {}...", url);

            try (InputStream is = ConnectionUtils.openStreamWithTimeout(url)) {
                BufferedImage image = ImageIO.read(is);
                logger.debug("Dimensions are {}x{}", image.getWidth(), image.getHeight());

                if (image.getWidth() != 24 && image.getHeight() != 24) {
                    throw new IOException("Image did not have expected dimensions!");
                }
            }
        }
    }

    /**
     * Checks that the Twitch/YouTube bots can connect to their respective services.
     * This doubles as a way to refresh tokens more regularly than once a week... just in case,
     * since they sometimes expire, especially on YouTube's side.
     */
    private static void checkChatProviderCanConnect(IChatProvider<?> chatProvider) throws IOException {
        try {
            logger.info("Trying to connect with {}...", chatProvider.getClass().getName());
            chatProvider.connect(message -> logger.info("Received message: {}", message.messageContents()));
        } finally {
            logger.info("Disconnecting from {}...", chatProvider.getClass().getName());
            chatProvider.disconnect();
        }
    }


    private interface ExplodyMethod {
        void run() throws Exception;
    }

    private static void runProcessAndAlertOnException(String name, ExplodyMethod process) {
        runProcessAndAlertOnException(name, null, process);
    }

    private static void runProcessAndAlertOnException(String name, ZonedDateTime giveUpAt, ExplodyMethod process) {
        logger.debug("Waiting for updater lock to be released...");

        Path lockFile = Paths.get("updater_lock");

        try {
            while (!tryCreate(lockFile)) {
                unstoppableSleep(1000);

                if (giveUpAt != null && ZonedDateTime.now().isAfter(giveUpAt)) {
                    logger.debug("Waited for too long! Aborting.");
                    return;
                }
            }
            logger.debug("Acquired updater lock!");
        } catch (IOException e) {
            logger.error("Could not lock updater", e);
            sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":x: Could not lock updater: " + e);
            return;
        }

        try {
            sendMessageToWebhook(SecretConstants.CRONTAB_LOGS_WEBHOOK_URL, "[" + ZonedDateTime.now(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] :arrow_right: Start `" + name + "`");
            logger.info("Starting {}", name);
            process.run();
            logger.info("Ended {}", name);
            sendMessageToWebhook(SecretConstants.CRONTAB_LOGS_WEBHOOK_URL, "[" + ZonedDateTime.now(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] :white_check_mark: End `" + name + "`");
        } catch (Exception e) {
            logger.error("Error while running {}", name, e);
            sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, "Error while running `" + name + "`: " + e);
            sendMessageToWebhook(SecretConstants.CRONTAB_LOGS_WEBHOOK_URL, "[" + ZonedDateTime.now(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] :x: Fail `" + name + "`");
        }

        try {
            Files.delete(lockFile);
            logger.debug("Released updater lock!");
            unstoppableSleep(1000);
        } catch (IOException e) {
            logger.error("Could not unlock updater", e);
            sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":x: Could not unlock updater: " + e);
            return;
        }
    }

    private static boolean tryCreate(Path file) throws IOException {
        try {
            Files.createFile(file);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }

    private static void sendMessageToWebhook(String url, String message) {
        try {
            WebhookExecutor.executeWebhook(
                    url,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/compute-engine.png",
                    "Crontab Runner",
                    message);
        } catch (IOException e) {
            logger.error("Error while sending message \"{}\"", message, e);
        }
    }

    private static void unstoppableSleep(int delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":x: Could not wait for lock: " + e);
        }
    }
}
