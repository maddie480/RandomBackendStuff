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
import ovh.maddie480.randomstuff.backend.celeste.FrontendTaskReceiver;
import ovh.maddie480.randomstuff.backend.celeste.crontabs.*;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.ModDatabase;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.ModUpdater;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.UpdateCheckerTracker;
import ovh.maddie480.randomstuff.backend.discord.crontabs.*;
import ovh.maddie480.randomstuff.backend.discord.modstructureverifier.ModStructureVerifier;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.QuestCommunityBot;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.daily.*;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.hourly.TemperatureChecker;
import ovh.maddie480.randomstuff.backend.discord.serverjanitor.ServerJanitorBot;
import ovh.maddie480.randomstuff.backend.discord.timezonebot.TimezoneBot;
import ovh.maddie480.randomstuff.backend.streams.apis.IChatProvider;
import ovh.maddie480.randomstuff.backend.streams.apis.TwitchChatProvider;
import ovh.maddie480.randomstuff.backend.streams.apis.YouTubeChatProvider;
import ovh.maddie480.randomstuff.backend.streams.features.LNJBot;
import ovh.maddie480.randomstuff.backend.utils.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The class responsible for running all recurring processes,
 * and sending out alerts if one of them crashes.
 */
public class CrontabRunner {
    private static final Logger logger = LoggerFactory.getLogger(CrontabRunner.class);

    static void main(String[] args) {
        String arg = args != null && args.length > 0 ? args[0] : "";

        switch (arg) {
            case "--daily" -> {
                runDailyProcesses();
                sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":white_check_mark: Daily processes completed!");
                System.exit(0);
                return;
            }
            case "--hourly" -> {
                runHourlyProcesses();
                return;
            }
            case "--updater" -> {
                runUpdater(true);
                return;
            }
            case "--mirrorcheck" -> {
                try {
                    FullMirrorCheck.main(null);
                    sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, ":tada: Full mirror check found no issues!");
                } catch (Exception e) {
                    logger.error("Error while running FullMirrorCheck", e);
                    sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, "Error while running `FullMirrorCheck`: " + e);
                }
                return;
            }
        }

        // redirect logs to a file
        redirectLogsToFile(args[0]);

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
            ModStructureVerifier.main();

            new QuestCommunityBot((jda, uptimeCommand) -> {
                // start the health checks, and make them control the bot status
                ContinuousHealthChecks.startChecking(jda, uptimeCommand::setBotStatus);
            });

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
        runInParallel(Arrays.asList(
                // The YouTube bot is not approved by Google, so its authorization gets revoked after a week,
                // so we need to reauthorize through the Authorization Code Flow: I allow the app,
                // then Google gives me a token, and I paste it into youtube_auth_code.txt.
                // This is referred to as the "Token Exchange Ritual", because it sounds more ~mysterious~.
                // Live streams happen on Sunday, so Saturday evening is the right time to do this!
                new RunProcessParameters("[Daily] checkChatProviderCanConnect(YouTube)", () -> {
                    if (ZonedDateTime.now().getDayOfWeek() != DayOfWeek.SATURDAY) return;

                    OutputStreamLogger.redirectAllOutput(logger,
                            new ProcessBuilder("rm", "-rfv", "youtube_api_credentials").start()).waitFor();
                    OutputStreamLogger.redirectAllOutput(logger,
                            new ProcessBuilder("touch", "youtube_auth_code.txt").start()).waitFor();

                    sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, "You have 5 minutes to execute the YouTube Token Exchange Ritual!");

                    unstoppableSleep(300000);

                    checkChatProviderCanConnect(new YouTubeChatProvider(() -> logger.info("Giving up!")));
                }),

                // Update tasks
                new RunProcessParameters("[Daily] Dependabork", () -> Dependabork.main(null)),
                new RunProcessParameters("[Daily] MembershipNotifier", () -> MembershipNotifier.main(null)),
                new RunProcessParameters("[Daily] TimezoneBot.leaveDeadServerIfNecessary", TimezoneBot::leaveDeadServerIfNecessary),
                new RunProcessParameters("[Daily] CustomSlashCommandsCleanup", CustomSlashCommandsCleanup::housekeep),
                new RunProcessParameters("[Daily] refreshArbitraryModAppCache", ArbitraryModAppCacher::refreshArbitraryModAppCache),
                new RunProcessParameters("[Daily] CustomEntityCatalogGenerator", () -> {
                    CustomEntityCatalogGenerator.main();
                    CelesteStuffHealthCheck.checkCustomEntityCatalog();
                }),
                new RunProcessParameters("[Daily] ServerJanitorBot", () -> ServerJanitorBot.main(null)),
                new RunProcessParameters("[Daily] housekeepArbitraryModApp", CrontabRunner::housekeepArbitraryModApp),
                new RunProcessParameters("[Daily] AssetDriveService", () -> {
                    AssetDriveService.listAllFiles();
                    AssetDriveService.rsyncFiles();
                    AssetDriveService.classifyAssets();
                }),
                new RunProcessParameters("[Daily] ServerCountUploader", ServerCountUploader::run),
                new RunProcessParameters("[Daily] writeWeeklyStatisticsToFile", UsageStatsService::writeWeeklyStatisticsToFile),
                new RunProcessParameters("[Daily] TASCheckUpdate", TASCheckUpdate::main),
                new RunProcessParameters("[Daily] TranslationViewer.triggerRefresh", TranslationViewerCheck::triggerRefresh),
                new RunProcessParameters("[Daily] RefreshMapEditorVanillaEntities", RefreshMapEditorVanillaEntities::checkForAhornPlugins),

                // Health checks
                new RunProcessParameters("[Daily] checkUnapprovedCategories", GameBananaAutomatedChecks::checkUnapprovedCategories),
                new RunProcessParameters("[Daily] WorldClockHealthCheck", () -> WorldClockHealthCheck.main(null)),
                new RunProcessParameters("[Daily] checkEverestExists(daily: true)", () -> CelesteStuffHealthCheck.checkEverestExists(true)),
                new RunProcessParameters("[Daily] checkOlympusExists(daily: true)", () -> CelesteStuffHealthCheck.checkOlympusExists(true)),
                new RunProcessParameters("[Daily] checkLoennVersionsListAPI", CelesteStuffHealthCheck::checkLoennVersionsListAPI),
                new RunProcessParameters("[Daily] checkFontGeneratorBMFont", CelesteStuffHealthCheck::checkFontGeneratorBMFont),
                new RunProcessParameters("[Daily] checkFontGeneratorBMFontCustom", CelesteStuffHealthCheck::checkFontGeneratorBMFontCustom),
                new RunProcessParameters("[Daily] checkModStructureVerifier", CelesteStuffHealthCheck::checkModStructureVerifier),
                new RunProcessParameters("[Daily] checkMapTreeViewer", () -> {
                    CelesteStuffHealthCheck.checkMapTreeViewer();
                    CelesteStuffHealthCheck.checkMapTreeViewerWithWackyEncoding();
                }),
                new RunProcessParameters("[Daily] checkFileSearcher", CelesteStuffHealthCheck::checkFileSearcher),
                new RunProcessParameters("[Daily] checkDirectLinkService", CelesteStuffHealthCheck::checkDirectLinkService),
                new RunProcessParameters("[Daily] checkStaticPages", CelesteStuffHealthCheck::checkStaticPages),
                new RunProcessParameters("[Daily] checkGameBananaCategories", CelesteStuffHealthCheck::checkGameBananaCategories),
                new RunProcessParameters("[Daily] everestYamlValidatorHealthCheck", CelesteStuffHealthCheck::everestYamlValidatorHealthCheck),
                new RunProcessParameters("[Daily] checkSmallerGameBananaAPIs", CelesteStuffHealthCheck::checkSmallerGameBananaAPIs),
                new RunProcessParameters("[Daily] EmbedBuilder.integrityCheck()", EmbedBuilder::integrityCheck),
                new RunProcessParameters("[Daily] checkSrcModUpdateNotificationsPage", CelesteStuffHealthCheck::checkSrcModUpdateNotificationsPage),
                new RunProcessParameters("[Daily] checkDiscordBotsPage", CelesteStuffHealthCheck::checkDiscordBotsPage),
                new RunProcessParameters("[Daily] checkCelesteNewsNetworkSubscriptionService", CelesteStuffHealthCheck::checkCelesteNewsNetworkSubscriptionService),
                new RunProcessParameters("[Daily] checkCollabList", CelesteStuffHealthCheck::checkCollabList),
                new RunProcessParameters("[Daily] checkOlympusNews", CelesteStuffHealthCheck::checkOlympusNews),
                new RunProcessParameters("[Daily] checkAssetDriveBrowser", CelesteStuffHealthCheck::checkAssetDriveBrowser),
                new RunProcessParameters("[Daily] checkWipeConverter", CelesteStuffHealthCheck::checkWipeConverter),
                new RunProcessParameters("[Daily] checkArbitraryModApp", CrontabRunner::checkArbitraryModApp),
                new RunProcessParameters("[Daily] GitHubActionsChecker", () -> GitHubActionsChecker.main(null)),
                new RunProcessParameters("[Daily] BadCharactersChecker", BadCharactersChecker::main),
                new RunProcessParameters("[Daily] checkMilestoneIsInTheFuture", EverestRepositoriesRitualCheck::checkMilestoneIsInTheFuture),
                new RunProcessParameters("[Daily] checkLatestVersionsArePinned", EverestRepositoriesRitualCheck::checkLatestVersionsArePinned),
                new RunProcessParameters("[Daily] checkBananaMirrorDatabaseMatch", CelesteStuffHealthCheck::checkBananaMirrorDatabaseMatch),
                new RunProcessParameters("[Daily] checkEverestGitHubAPIMirrorMatch", CelesteStuffHealthCheck::checkEverestGitHubAPIMirrorMatch),
                new RunProcessParameters("[Daily] TimezoneBot.checkIfEnoughUsers", TimezoneBot::checkIfEnoughUsers),
                new RunProcessParameters("[Daily] TranslationViewer.check", TranslationViewerCheck::main),

                // Non-Celeste stuff
                new RunProcessParameters("[Daily] LNJBot.healthCheck", LNJBot::healthCheck),
                new RunProcessParameters("[Daily] checkChatProviderCanConnect(Twitch)", () -> checkChatProviderCanConnect(new TwitchChatProvider())),
                new RunProcessParameters("[Daily] checkRadioLNJ", CrontabRunner::checkRadioLNJ),
                new RunProcessParameters("[Daily] checkLNJEmotes()", CrontabRunner::checkLNJEmotes),
                new RunProcessParameters("[Daily] checkEnhancedBananaEmbeds()", CrontabRunner::checkEnhancedBananaEmbeds),
                new RunProcessParameters("[Daily] ChangeBGToRandom", ChangeBGToRandom::run),
                new RunProcessParameters("[Daily] PurgePosts", PurgePosts::run),
                new RunProcessParameters("[Daily] QuestCommunityWebsiteHealthCheck", QuestCommunityWebsiteHealthCheck::run),
                new RunProcessParameters("[Daily] PrivateDiscordJanitor", PrivateDiscordJanitor::runDaily)
        ));

        // once everything is done, do the backup.
        runProcessAndAlertOnException("[Daily] PlatformBackup", PlatformBackup::run);
    }

    private static void runHourlyProcesses() {
        runInParallel(Arrays.asList(
                // Update tasks
                new RunProcessParameters("[Hourly] updatePrivateHelpersFromGitHub", UpdateCheckerTracker::updatePrivateHelpersFromGitHub),
                new RunProcessParameters("[Hourly] CollabAutoHider", CollabAutoHider::run),
                new RunProcessParameters("[Hourly] cleanUpFolder(/shared/temp)", () -> TempFolderCleanup.cleanUpFolder("/shared/temp", 1, _ -> true)),
                new RunProcessParameters("[Hourly] cleanUpFolder(/logs)", () -> {
                    TempFolderCleanup.cleanUpFolder("/logs", 30, path -> path.getFileName().toString().endsWith(".backend.log.gz"));
                    TempFolderCleanup.cleanUpFolder("/logs", 1, path -> path.getFileName().toString().endsWith(".autodeploy.log"));
                    TempFolderCleanup.zipUpOldFiles("/logs", 8, path -> path.getFileName().toString().endsWith(".backend.log"));
                }),
                new RunProcessParameters("[Hourly] MastodonUpdateChecker", () -> {
                    MastodonUpdateChecker.loadFile();
                    MastodonUpdateChecker.checkForUpdates();
                }),
                new RunProcessParameters("[Hourly] OlympusNewsUpdateChecker", () -> {
                    OlympusNewsUpdateChecker.loadPreviouslyPostedNews();
                    OlympusNewsUpdateChecker.checkForUpdates();
                }),
                new RunProcessParameters("[Hourly] LoennVersionLister", LoennVersionLister::update),
                new RunProcessParameters("[Hourly] TopGGCommunicator.refreshVotes", () -> TopGGCommunicator.refreshVotes(message -> CrontabRunner.sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, message))),
                new RunProcessParameters("[Hourly] EverestPRLabelSlapper", () -> EverestPRLabelSlapper.main(null)),
                new RunProcessParameters("[Hourly] ModUpdater::updateFeaturedMods", ModUpdater::updateFeaturedMods),

                // GameBanana automated checks
                new RunProcessParameters("[Generic] new ModDatabase()", () -> {
                    try (ModDatabase database = new ModDatabase()) {
                        runInParallel(Arrays.asList(
                                new RunProcessParameters("[Hourly] checkYieldReturnOrigAndIntPtrTrick", () -> GameBananaAutomatedChecks.checkYieldReturnOrigAndIntPtrTrick(database)),
                                new RunProcessParameters("[Hourly] checkForForbiddenFiles", () -> GameBananaAutomatedChecks.checkForForbiddenFiles(database)),
                                new RunProcessParameters("[Hourly] checkAllModsWithEverestYamlValidator", () -> GameBananaAutomatedChecks.checkAllModsWithEverestYamlValidator(database)),
                                new RunProcessParameters("[Hourly] checkPngFilesArePngFiles", () -> GameBananaAutomatedChecks.checkPngFilesArePngFiles(database)),
                                new RunProcessParameters("[Hourly] checkDuplicateModIdsCaseInsensitive", () -> GameBananaAutomatedChecks.checkDuplicateModIdsCaseInsensitive(database)),
                                new RunProcessParameters("[Hourly] checkForBananaServingTheWrongFile", () -> GameBananaAutomatedChecks.checkForBananaGettingDrunkAndServingTheWrongFile(database))
                        ));
                    }
                }),

                // Health checks
                new RunProcessParameters("[Hourly] updateCheckerHealthCheck", CelesteStuffHealthCheck::updateCheckerHealthCheck),
                new RunProcessParameters("[Hourly] checkEverestExists(daily: false)", () -> CelesteStuffHealthCheck.checkEverestExists(false)),
                new RunProcessParameters("[Hourly] checkOlympusExists(daily: false)", () -> CelesteStuffHealthCheck.checkOlympusExists(false)),
                new RunProcessParameters("[Hourly] checkOlympusAPIs", CelesteStuffHealthCheck::checkOlympusAPIs),

                // Quest Community Bot stuff
                new RunProcessParameters("[Hourly] TemperatureChecker", () -> new TemperatureChecker().checkForUpdates()),
                new RunProcessParameters("[Hourly] TwitchUpdateChecker", () -> new TwitchUpdateChecker().checkForUpdates())
        ));
    }

    private static void runUpdater(boolean fullUpdateCheck) {
        if (fullUpdateCheck) {
            runProcessAndAlertOnException("[Updater] ModUpdater::fullUpdate", ModUpdater::fullUpdate);
            runProcessAndAlertOnException("[Updater] ModUpdater::updateFeaturedMods", ModUpdater::updateFeaturedMods);
            return;
        }

        runInParallel(Arrays.asList(
                new RunProcessParameters("[Updater] checkEverestVersions", EverestVersionLister::checkEverestVersions),
                new RunProcessParameters("[Updater] checkOlympusVersions", OlympusVersionLister::checkOlympusVersions),
                new RunProcessParameters("[Updater] ModUpdater::incrementalUpdate", ModUpdater::incrementalUpdate)
        ));
    }

    private record RunProcessParameters(String name, ExplodyMethod process) {
    }

    private static void runInParallel(List<RunProcessParameters> toRun) {
        runProcessAndAlertOnException("[Generic] runInParallel", () -> {
            List<ParallelzUtilz.ExplodyRunnable> tasks = toRun.stream()
                    .<ParallelzUtilz.ExplodyRunnable>map(r -> (() -> runProcessAndAlertOnException(r.name, r.process)))
                    .toList();
            ParallelzUtilz.runInParallel(tasks);
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
                    .getFirst().text());
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
        Path statusFile = null;
        try {
            statusFile = Files.createTempFile(Paths.get("/shared/temp"), "status-", ".txt");
            Files.writeString(statusFile, name + "\n", UTF_8);
            logger.info("Starting {}", name);
            process.run();
            logger.info("Ended {}", name);
        } catch (Exception e) {
            logger.error("Error while running {}", name, e);
            sendMessageToWebhook(SecretConstants.UPDATE_CHECKER_LOGS_HOOK, "Error while running `" + name + "`: " + e);
        } finally {
            if (statusFile != null && Files.exists(statusFile)) {
                try {
                    Files.delete(statusFile);
                } catch (IOException e) {
                    // oh well...
                }
            }
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
}
