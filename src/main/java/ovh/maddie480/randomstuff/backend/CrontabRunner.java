package ovh.maddie480.randomstuff.backend;

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
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.hourly.TemperatureChecker;
import ovh.maddie480.randomstuff.backend.discord.serverjanitor.ServerJanitorBot;
import ovh.maddie480.randomstuff.backend.discord.timezonebot.TimezoneBot;
/* #if LNJ_BOT
import ovh.maddie480.randomstuff.backend.streams.apis.IChatProvider;
import ovh.maddie480.randomstuff.backend.streams.apis.TwitchChatProvider;
import ovh.maddie480.randomstuff.backend.streams.apis.YouTubeChatProvider;
import ovh.maddie480.randomstuff.backend.streams.features.LNJBot;
#endif */
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.EmbedBuilder;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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

        if (arg.equals("--updater")) {
            runUpdater(true);
            return;
        }

        if (arg.equals("--mirrorcheck")) {
            try {
                FullMirrorCheck.main(null);
                sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":tada: Full mirror check found no issues!");
            } catch (Exception e) {
                logger.error("Error while running FullMirrorCheck", e);
                sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, "Error while running `FullMirrorCheck`: " + e);
            }
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
                while (true) {
                    runUpdater(false);
                    unstoppableSleep(120_000);
                }
            }
        }.start();

        try {
            // start the Timezone Bot, Mod Structure Verifier and Quest Community Bot
            TimezoneBot.main(null);
            ModStructureVerifier.main(null);
            new QuestCommunityBot();
            sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":arrow_up: :desktop: The backend just started.");
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
        // Update tasks
        runProcessAndAlertOnException("[Daily] Dependabork", () -> Dependabork.main(null));
        runProcessAndAlertOnException("[Daily] GameBananaProfileLink", () -> GameBananaProfileLink.main(null));
        runProcessAndAlertOnException("[Daily] MembershipNotifier", () -> MembershipNotifier.main(null));
        runProcessAndAlertOnException("[Daily] TimezoneBot.leaveDeadServerIfNecessary", TimezoneBot::leaveDeadServerIfNecessary);
        runProcessAndAlertOnException("[Daily] CustomSlashCommandsCleanup", CustomSlashCommandsCleanup::housekeep);
        runProcessAndAlertOnException("[Daily] refreshArbitraryModAppCache", ArbitraryModAppCacher::refreshArbitraryModAppCache);
        runProcessAndAlertOnException("[Daily] CustomEntityCatalogGenerator", () -> CustomEntityCatalogGenerator.main(null));
        runProcessAndAlertOnException("[Daily] ServerJanitorBot", () -> ServerJanitorBot.main(null));
        runProcessAndAlertOnException("[Daily] housekeepArbitraryModApp", CrontabRunner::housekeepArbitraryModApp);
        runProcessAndAlertOnException("[Daily] AssetDriveService.listAllFiles", AssetDriveService::listAllFiles);
        runProcessAndAlertOnException("[Daily] AssetDriveService.rsyncFiles", AssetDriveService::rsyncFiles);
        runProcessAndAlertOnException("[Daily] AssetDriveService.classifyAssets", AssetDriveService::classifyAssets);
        runProcessAndAlertOnException("[Daily] ServerCountUploader", ServerCountUploader::run);
        runProcessAndAlertOnException("[Daily] writeWeeklyStatisticsToFile", UsageStatsService::writeWeeklyStatisticsToFile);
        runProcessAndAlertOnException("[Daily] TASCheckUpdate", () -> TASCheckUpdate.main(null));

        // Health Checks
        runProcessAndAlertOnException("[Daily] checkUnapprovedCategories", GameBananaAutomatedChecks::checkUnapprovedCategories);
        runProcessAndAlertOnException("[Daily] WorldClockHealthCheck", () -> WorldClockHealthCheck.main(null));
        runProcessAndAlertOnException("[Daily] checkEverestExists(daily: true)", () -> CelesteStuffHealthCheck.checkEverestExists(true));
        runProcessAndAlertOnException("[Daily] checkOlympusExists(daily: true)", () -> CelesteStuffHealthCheck.checkOlympusExists(true));
        runProcessAndAlertOnException("[Daily] checkLoennVersionsListAPI", CelesteStuffHealthCheck::checkLoennVersionsListAPI);
        runProcessAndAlertOnException("[Daily] checkFontGeneratorBMFont", CelesteStuffHealthCheck::checkFontGeneratorBMFont);
        runProcessAndAlertOnException("[Daily] checkFontGeneratorBMFontCustom", CelesteStuffHealthCheck::checkFontGeneratorBMFontCustom);
        runProcessAndAlertOnException("[Daily] checkModStructureVerifier", CelesteStuffHealthCheck::checkModStructureVerifier);
        runProcessAndAlertOnException("[Daily] checkMapTreeViewer", () -> {
            CelesteStuffHealthCheck.checkMapTreeViewer();
            CelesteStuffHealthCheck.checkMapTreeViewerWithWackyEncoding();
        });
        runProcessAndAlertOnException("[Daily] checkFileSearcher", CelesteStuffHealthCheck::checkFileSearcher);
        runProcessAndAlertOnException("[Daily] checkDirectLinkService", CelesteStuffHealthCheck::checkDirectLinkService);
        runProcessAndAlertOnException("[Daily] checkStaticPages", CelesteStuffHealthCheck::checkStaticPages);
        runProcessAndAlertOnException("[Daily] checkGameBananaCategories", CelesteStuffHealthCheck::checkGameBananaCategories);
        runProcessAndAlertOnException("[Daily] everestYamlValidatorHealthCheck", CelesteStuffHealthCheck::everestYamlValidatorHealthCheck);
        runProcessAndAlertOnException("[Daily] checkSmallerGameBananaAPIs", CelesteStuffHealthCheck::checkSmallerGameBananaAPIs);
        runProcessAndAlertOnException("[Daily] EmbedBuilder.integrityCheck()", EmbedBuilder::integrityCheck);
        runProcessAndAlertOnException("[Daily] checkSrcModUpdateNotificationsPage", CelesteStuffHealthCheck::checkSrcModUpdateNotificationsPage);
        runProcessAndAlertOnException("[Daily] checkDiscordBotsPage", CelesteStuffHealthCheck::checkDiscordBotsPage);
        runProcessAndAlertOnException("[Daily] checkCelesteNewsNetworkSubscriptionService", CelesteStuffHealthCheck::checkCelesteNewsNetworkSubscriptionService);
        runProcessAndAlertOnException("[Daily] checkCollabList", CelesteStuffHealthCheck::checkCollabList);
        runProcessAndAlertOnException("[Daily] checkCustomEntityCatalog", CelesteStuffHealthCheck::checkCustomEntityCatalog);
        runProcessAndAlertOnException("[Daily] checkOlympusNews", CelesteStuffHealthCheck::checkOlympusNews);
        runProcessAndAlertOnException("[Daily] checkAssetDriveBrowser", CelesteStuffHealthCheck::checkAssetDriveBrowser);
        runProcessAndAlertOnException("[Daily] checkWipeConverter", CelesteStuffHealthCheck::checkWipeConverter);
        runProcessAndAlertOnException("[Daily] checkArbitraryModApp", CrontabRunner::checkArbitraryModApp);
        runProcessAndAlertOnException("[Daily] GitHubActionsChecker", () -> GitHubActionsChecker.main(null));
        runProcessAndAlertOnException("[Daily] checkEmbedBuilder", GameBananaProfileLink::checkEmbedBuilder);
        runProcessAndAlertOnException("[Daily] BadCharactersChecker", () -> BadCharactersChecker.main(null));
        runProcessAndAlertOnException("[Daily] checkMilestoneIsInTheFuture", EverestRepositoriesRitualCheck::checkMilestoneIsInTheFuture);
        runProcessAndAlertOnException("[Daily] checkLatestVersionsArePinned", EverestRepositoriesRitualCheck::checkLatestVersionsArePinned);
        runProcessAndAlertOnException("[Daily] checkBananaMirrorDatabaseMatch", CelesteStuffHealthCheck::checkBananaMirrorDatabaseMatch);
        runProcessAndAlertOnException("[Daily] checkEverestGitHubAPIMirrorMatch", CelesteStuffHealthCheck::checkEverestGitHubAPIMirrorMatch);

        // Non-Celeste Stuff
        /* #if LNJ_BOT
        runProcessAndAlertOnException("[Daily] LNJBot.healthCheck", LNJBot::healthCheck);
        runProcessAndAlertOnException("[Daily] checkChatProviderCanConnect(Twitch)", () -> checkChatProviderCanConnect(new TwitchChatProvider()));
        runProcessAndAlertOnException("[Daily] checkChatProviderCanConnect(YouTube)", () -> {
            sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, "You have 5 minutes to execute the YouTube Token Exchange Ritual!");
            Thread.sleep(300000);
            checkChatProviderCanConnect(new YouTubeChatProvider(() -> logger.info("Giving up!")));
        });
        #endif */
        runProcessAndAlertOnException("[Daily] checkRadioLNJ", CrontabRunner::checkRadioLNJ);
        runProcessAndAlertOnException("[Daily] checkLNJEmotes()", CrontabRunner::checkLNJEmotes);
        runProcessAndAlertOnException("[Daily] checkEnhancedBananaEmbeds()", CrontabRunner::checkEnhancedBananaEmbeds);
        runProcessAndAlertOnException("[Daily] ChangeBGToRandom", ChangeBGToRandom::run);
        runProcessAndAlertOnException("[Daily] PurgePosts", PurgePosts::run);
        runProcessAndAlertOnException("[Daily] QuestCommunityWebsiteHealthCheck", QuestCommunityWebsiteHealthCheck::run);
        runProcessAndAlertOnException("[Daily] StonkUpdateChecker", StonkUpdateChecker::post);
        runProcessAndAlertOnException("[Daily] PlatformBackup", PlatformBackup::run);
        runProcessAndAlertOnException("[Daily] PrivateDiscordJanitor", PrivateDiscordJanitor::runDaily);
    }

    private static void runHourlyProcesses() {
        // Update tasks
        runProcessAndAlertOnException("[Hourly] updatePrivateHelpersFromGitHub", UpdateCheckerTracker::updatePrivateHelpersFromGitHub);
        runProcessAndAlertOnException("[Hourly] CollabAutoHider", CollabAutoHider::run);
        runProcessAndAlertOnException("[Hourly] cleanUpFolder(/shared/temp)", () -> TempFolderCleanup.cleanUpFolder("/shared/temp", 1, path -> true));
        runProcessAndAlertOnException("[Hourly] cleanUpFolder(/logs)", () -> TempFolderCleanup.cleanUpFolder("/logs", 30, path -> path.getFileName().toString().endsWith(".backend.log.gz")));
        runProcessAndAlertOnException("[Hourly] cleanUpFolder(/logs, autodeploy)", () -> TempFolderCleanup.cleanUpFolder("/logs", 1, path -> path.getFileName().toString().endsWith(".autodeploy.log")));
        runProcessAndAlertOnException("[Hourly] zipUpOldFiles(/logs)", () -> TempFolderCleanup.zipUpOldFiles("/logs", 8, path -> path.getFileName().toString().endsWith(".backend.log")));
        runProcessAndAlertOnException("[Hourly] MastodonUpdateChecker", () -> {
            MastodonUpdateChecker.loadFile();
            MastodonUpdateChecker.checkForUpdates();
        });
        runProcessAndAlertOnException("[Hourly] OlympusNewsUpdateChecker", () -> {
            OlympusNewsUpdateChecker.loadPreviouslyPostedNews();
            OlympusNewsUpdateChecker.checkForUpdates();
        });
        runProcessAndAlertOnException("[Hourly] LoennVersionLister", LoennVersionLister::update);
        runProcessAndAlertOnException("[Hourly] TopGGCommunicator.refreshVotes", () -> TopGGCommunicator.refreshVotes(message -> CrontabRunner.sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, message)));
        runProcessAndAlertOnException("[Hourly] PrivateDiscordJanitor", PrivateDiscordJanitor::runHourly);
        runProcessAndAlertOnException("[Hourly] EverestPRLabelSlapper", () -> EverestPRLabelSlapper.main(null));

        // GameBanana automated checks
        AtomicBoolean updaterStuffHappened = new AtomicBoolean(false);
        runProcessAndAlertOnException("[Hourly] Files.exists(signalFile)", () -> {
            Path signalFile = Paths.get("updater_stuff_happened");
            if (Files.exists(signalFile)) {
                logger.info("Updater stuff happened, we need to check updated mods!");
                Files.delete(signalFile);
                updaterStuffHappened.set(true);
            }
        });

        if (updaterStuffHappened.get()) {
            runProcessAndAlertOnException("[Hourly] checkYieldReturnOrigAndIntPtrTrick", GameBananaAutomatedChecks::checkYieldReturnOrigAndIntPtrTrick);
            runProcessAndAlertOnException("[Hourly] checkForForbiddenFiles", GameBananaAutomatedChecks::checkForForbiddenFiles);
            runProcessAndAlertOnException("[Hourly] checkForFilesBelongingToMultipleMods", GameBananaAutomatedChecks::checkForFilesBelongingToMultipleMods);
            runProcessAndAlertOnException("[Hourly] checkAllModsWithEverestYamlValidator", GameBananaAutomatedChecks::checkAllModsWithEverestYamlValidator);
            runProcessAndAlertOnException("[Hourly] checkPngFilesArePngFiles", GameBananaAutomatedChecks::checkPngFilesArePngFiles);
            runProcessAndAlertOnException("[Hourly] checkDuplicateModIdsCaseInsensitive", GameBananaAutomatedChecks::checkDuplicateModIdsCaseInsensitive);
        }

        // Health checks
        runProcessAndAlertOnException("[Hourly] updateCheckerHealthCheck", CelesteStuffHealthCheck::updateCheckerHealthCheck);
        runProcessAndAlertOnException("[Hourly] checkEverestExists(daily: false)", () -> CelesteStuffHealthCheck.checkEverestExists(false));
        runProcessAndAlertOnException("[Hourly] checkOlympusExists(daily: false)", () -> CelesteStuffHealthCheck.checkOlympusExists(false));
        runProcessAndAlertOnException("[Hourly] checkOlympusAPIs", CelesteStuffHealthCheck::checkOlympusAPIs);

        // Quest Community Bot stuff
        runProcessAndAlertOnException("[Hourly] TemperatureChecker", () -> new TemperatureChecker().checkForUpdates());
        runProcessAndAlertOnException("[Hourly] TwitchUpdateChecker", () -> new TwitchUpdateChecker().checkForUpdates());
    }

    private static void runUpdater(boolean fullUpdateCheck) {
        if (!fullUpdateCheck) {
            runProcessAndAlertOnException("[Updater] checkEverestVersions", EverestVersionLister::checkEverestVersions);
            runProcessAndAlertOnException("[Updater] checkOlympusVersions", OlympusVersionLister::checkOlympusVersions);
        }

        runProcessAndAlertOnException("[Updater] updateDatabase(full: " + fullUpdateCheck + ")", () -> {
            UpdateCheckerTracker tracker = new UpdateCheckerTracker();
            EventListener.addEventListener(tracker);

            try {
                Main.updateDatabase(fullUpdateCheck);
                UpdateOutgoingWebhooks.notifyUpdate();
            } finally {
                EventListener.removeEventListener(tracker);
            }
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
    /* #if LNJ_BOT
    private static void checkChatProviderCanConnect(IChatProvider<?> chatProvider) throws IOException {
        try {
            logger.info("Trying to connect with {}...", chatProvider.getClass().getName());
            chatProvider.connect(message -> logger.info("Received message: {}", message.messageContents()));
        } finally {
            logger.info("Disconnecting from {}...", chatProvider.getClass().getName());
            chatProvider.disconnect();
        }
    }
    #endif */

    private static void checkEnhancedBananaEmbeds() throws IOException {
        {
            HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/gamebanana.com/mods/53687");
            connection.setRequestProperty("User-Agent", "Discordbot (actually-health-check)");
            String result;
            try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
                result = IOUtils.toString(is, UTF_8);
            }
            if (!result.equals("""
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                    <title>Maddie's Helping Hand</title>
                    <link rel="canonical" href="https://gamebanana.com/mods/53687"/>
                    <meta property="og:url" content="https://gamebanana.com/mods/53687"/>
                    <meta property="og:title" content="Maddie's Helping Hand"/>
                    <meta property="og:description" content="A grab bag of requests"/>
                    <meta property="og:image" content="https://images.gamebanana.com/img/ss/mods/5f5d1f3d880bd.jpg"/>
                    <meta property="theme-color" content="#FFE033"/>
                    <meta property="twitter:card" content="summary_large_image"/>
                    <link rel="alternate" type="application/json+oembed" href="https://maddie480.ovh/celeste/banana-oembed/mod-53687.json" title="maddie480"/>
                    </head>
                    <body>
                    Hi! What are you doing here?
                    </body>
                    </html>""")) {

                throw new IOException("Enhanced embed HTML didn't match expected!");
            }
        }

        {
            String result;
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/banana-oembed/mod-53687.json")) {
                result = IOUtils.toString(is, UTF_8);
            }
            if (!result.startsWith("{\"author_name\":\"maddie480\",\"author_url\":\"https://gamebanana.com/members/1698143\",\"provider_name\":\"GameBanana \\u2013 ")
                    || !result.endsWith("\",\"title\":\"Embed\",\"type\":\"rich\",\"version\":\"1.0\"}")) {

                throw new IOException("Enhanced embed JSON didn't match expected!");
            }
        }

        {
            HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/gamebanana.com/mods/53687");
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 302) {
                throw new IOException("Enhanced embed URL does not redirect!");
            }
            if (!"https://gamebanana.com/mods/53687".equals(connection.getHeaderField("Location"))) {
                throw new IOException("Enhanced embed URL does not redirect to the expected place!");
            }
        }
    }


    private interface ExplodyMethod {
        void run() throws Exception;
    }

    private static void runProcessAndAlertOnException(String name, ExplodyMethod process) {
        logger.debug("Waiting for updater lock to be released...");

        Path lockFile = Paths.get("updater_lock");

        try {
            while (!tryCreate(lockFile)) unstoppableSleep(1000);
            logger.debug("Acquired updater lock!");
        } catch (IOException e) {
            logger.error("Could not lock updater", e);
            sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":x: Could not lock updater: " + e);
            return;
        }

        try {
            sendMessageToWebhook(SecretConstants.CRONTAB_LOGS_WEBHOOK_URL, "[" + ZonedDateTime.now(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] :arrow_right: Start `" + name + "`", false);
            sendCrontabStatusEvent(name);
            logger.info("Starting {}", name);
            process.run();
            logger.info("Ended {}", name);
            sendCrontabStatusEvent("");
            sendMessageToWebhook(SecretConstants.CRONTAB_LOGS_WEBHOOK_URL, "[" + ZonedDateTime.now(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] :white_check_mark: End `" + name + "`", false);
        } catch (Exception e) {
            logger.error("Error while running {}", name, e);
            sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, "Error while running `" + name + "`: " + e);
            sendMessageToWebhook(SecretConstants.CRONTAB_LOGS_WEBHOOK_URL, "[" + ZonedDateTime.now(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] :x: Fail `" + name + "`", false);
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
        sendMessageToWebhook(url, message, true);
    }
    private static void sendMessageToWebhook(String url, String message, boolean shouldLog) {
        try {
            WebhookExecutor.executeWebhook(
                    url,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/compute-engine.png",
                    "Crontab Runner",
                    message,
                    shouldLog);
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

    private static void sendCrontabStatusEvent(String status) {
        try {
            JSONObject message = new JSONObject();
            message.put("taskType", "crontabStatusChange");
            message.put("newStatus", status);

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", 44480));
                try (OutputStream os = socket.getOutputStream();
                     OutputStreamWriter bw = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {

                    message.write(bw);
                }
            }
        } catch (IOException e) {
            logger.error("Error while sending update crontab status event", e);
        }
    }
}
