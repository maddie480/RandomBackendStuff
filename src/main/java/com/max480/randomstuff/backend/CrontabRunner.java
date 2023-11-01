package com.max480.randomstuff.backend;

import com.max480.everest.updatechecker.YamlUtil;
import com.max480.randomstuff.backend.celeste.FrontendTaskReceiver;
import com.max480.randomstuff.backend.celeste.crontabs.*;
import com.max480.randomstuff.backend.discord.crontabs.*;
import com.max480.randomstuff.backend.discord.modstructureverifier.ModStructureVerifier;
import com.max480.randomstuff.backend.discord.questcommunitybot.QuestCommunityBot;
import com.max480.randomstuff.backend.discord.questcommunitybot.crontabs.daily.*;
import com.max480.randomstuff.backend.discord.questcommunitybot.crontabs.hourly.*;
import com.max480.randomstuff.backend.discord.serverjanitor.ServerJanitorBot;
import com.max480.randomstuff.backend.discord.slashcommandbot.SlashCommandBot;
import com.max480.randomstuff.backend.discord.timezonebot.TimezoneBot;
import com.max480.randomstuff.backend.twitch.LNJTwitchBot;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import com.max480.randomstuff.backend.utils.WebhookExecutor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
        com.max480.everest.updatechecker.Main.serverConfig = new com.max480.everest.updatechecker.ServerConfig(config);

        String arg = args != null && args.length > 0 ? args[0] : "";

        if (arg.equals("--daily")) {
            runDailyProcesses();
            sendMessageToWebhook(":white_check_mark: Daily processes completed!");
            System.exit(0);
            return;
        }

        if (arg.equals("--hourly")) {
            // load #celeste_news_network state from disk
            MastodonUpdateChecker.loadFile();
            OlympusNewsUpdateChecker.loadPreviouslyPostedNews();

            runHourlyProcesses();
            return;
        }

        if (arg.equals("--updater") || arg.equals("--updater-full")) {
            // register update checker tracker
            com.max480.everest.updatechecker.EventListener.addEventListener(new UpdateCheckerTracker());

            Path lockFile = Paths.get("updater_lock");
            if (Files.exists(lockFile)) {
                logger.warn("Updater already running!");
                return;
            }

            try {
                Files.createFile(lockFile);
            } catch (IOException e) {
                logger.error("Could not lock updater", e);
                sendMessageToWebhook(":x: Could not lock updater: " + e);
            }

            boolean full = arg.equals("--updater-full");
            runUpdater(full);

            try {
                Files.delete(lockFile);
            } catch (IOException e) {
                logger.error("Could not unlock updater", e);
                sendMessageToWebhook(":x: Could not unlock updater: " + e);
            }

            return;
        }

        // redirect logs to a file
        try {
            String date = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            System.setOut(new PrintStream(args[0] + File.separator + date + "_out.backend.log"));
            System.setErr(new PrintStream(args[0] + File.separator + date + "_err.backend.log"));
        } catch (FileNotFoundException e) {
            logger.error("Could not redirect trace to log file", e);
        }

        // start the health checks
        ContinuousHealthChecks.startChecking();

        // start communication channel with the frontend
        FrontendTaskReceiver.start();

        try {
            // start the Timezone Bot and Mod Structure Verifier
            TimezoneBot.main(null);
            ModStructureVerifier.main(null);

            // and those obscure bots as well
            new QuestCommunityBot();
            new SlashCommandBot().start();
        } catch (Exception e) {
            logger.error("Error while starting up the bots", e);
            sendMessageToWebhook(":x: Could not start up the bots: " + e);
        }
    }

    private static void runDailyProcesses() {
        runProcessAndAlertOnException("Daily processes", () -> {
            // GameBanana automated checks
            GameBananaAutomatedChecks.checkYieldReturnOrigAndIntPtrTrick();
            GameBananaAutomatedChecks.checkForForbiddenFiles();
            GameBananaAutomatedChecks.checkForDuplicateModIds();
            GameBananaAutomatedChecks.checkForFilesBelongingToMultipleMods();
            GameBananaAutomatedChecks.checkAllModsWithEverestYamlValidator();
            GameBananaAutomatedChecks.checkPngFilesArePngFiles();
            GameBananaAutomatedChecks.checkUnapprovedCategories();
            GameBananaAutomatedChecks.checkDuplicateModIdsCaseInsensitive();

            // update tasks
            AutoLeaver.main(null);
            CustomSlashCommandsCleanup.housekeep();
            ServerCountUploader.run();
            ArbitraryModAppCacher.refreshArbitraryModAppCache();
            CustomEntityCatalogGenerator.main(null);
            ServerJanitorBot.main(null);
            housekeepArbitraryModApp();
            AssetDriveService.refreshCachedAssets();
            AssetDriveService.classifyAssets();

            // health checks
            WorldClockHealthCheck.main(null);
            CelesteStuffHealthCheck.checkEverestExists(true);
            CelesteStuffHealthCheck.checkOlympusExists(true);
            CelesteStuffHealthCheck.checkBananaMirrorDatabaseMatch();
            CelesteStuffHealthCheck.checkFontGeneratorBMFont();
            CelesteStuffHealthCheck.checkFontGeneratorBMFontCustom();
            CelesteStuffHealthCheck.checkModStructureVerifier();
            CelesteStuffHealthCheck.checkMapTreeViewer();
            CelesteStuffHealthCheck.checkFileSearcher();
            CelesteStuffHealthCheck.checkDirectLinkService();
            CelesteStuffHealthCheck.checkStaticPages();
            CelesteStuffHealthCheck.checkGameBananaCategories();
            CelesteStuffHealthCheck.everestYamlValidatorHealthCheck();
            CelesteStuffHealthCheck.checkSmallerGameBananaAPIs();
            CelesteStuffHealthCheck.checkSrcModUpdateNotificationsPage();
            CelesteStuffHealthCheck.checkDiscordBotsPage();
            CelesteStuffHealthCheck.checkCelesteNewsNetworkSubscriptionService();
            CelesteStuffHealthCheck.checkCollabList();
            CelesteStuffHealthCheck.checkCustomEntityCatalog();
            CelesteStuffHealthCheck.checkOlympusNews();
            checkArbitraryModApp();
            checkRadioLNJ();
            LNJTwitchBot.healthCheck();

            // Quest Community Bot stuff
            ChangeBGToRandom.run();
            PurgePosts.run();
            QuestCommunityWebsiteHealthCheck.run();
            SlashCommandBotHealthCheck.checkSlashCommands();

            JDA client = JDABuilder.createLight(SecretConstants.QUEST_COMMUNITY_BOT_TOKEN, GatewayIntent.MESSAGE_CONTENT)
                    .build().awaitReady();

            try {
                StonkUpdateChecker.postTo(client.getTextChannelById(551822297573490749L));
                PlatformBackup.run(client);

                client.shutdown();
            } catch (IOException e) {
                client.shutdown();
                throw e;
            }

            // save the longest for the end
            AssetDriveService.cacheAllFiles();

            Semaphore mutex = new Semaphore(0);
            new Thread("Discord Janitor") {
                @Override
                public void run() {
                    try {
                        PrivateDiscordJanitor.runCleanup();
                        mutex.release();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }.start();

            if (mutex.tryAcquire(10, TimeUnit.MINUTES)) {
                logger.debug("Discord Janitor finished!");
            } else {
                logger.warn("Discord Janitor didn't finish in 10 minutes, aborting.");
            }
        });
    }

    private static void runHourlyProcesses() {
        runProcessAndAlertOnException("Hourly processes", () -> {
            // update tasks
            UpdateCheckerTracker.updatePrivateHelpersFromGitHub();
            CollabAutoHider.run();
            TempFolderCleanup.cleanUpFolder("/shared/temp", 1, path -> true);
            TempFolderCleanup.cleanUpFolder("/logs", 30, path -> path.getFileName().toString().endsWith(".backend.log"));
            TopGGCommunicator.refreshVotes(CrontabRunner::sendMessageToWebhook);
            UsageStatsService.writeWeeklyStatisticsToFile();
            MastodonUpdateChecker.checkForUpdates();
            OlympusNewsUpdateChecker.checkForUpdates();

            // health checks
            CelesteStuffHealthCheck.updateCheckerHealthCheck();
            CelesteStuffHealthCheck.checkEverestExists(false);
            CelesteStuffHealthCheck.checkOlympusExists(false);
            CelesteStuffHealthCheck.checkOlympusAPIs();

            // Quest Community Bot stuff
            JDA client = JDABuilder.createLight(SecretConstants.QUEST_COMMUNITY_BOT_TOKEN).build().awaitReady();

            try {
                TextChannel webhookHell = client.getTextChannelById(551822297573490749L);

                BusUpdateChecker.runCheckForUpdates(webhookHell);
                new ComicUpdateChecker().runCheckForUpdates(webhookHell);
                RandomStuffPoster.run(webhookHell);
                new TemperatureChecker().checkForUpdates(webhookHell);
                PinUpdater.update(webhookHell);
                new TwitchUpdateChecker().checkForUpdates(webhookHell);

                JsonUpdateChecker checker = new JsonUpdateChecker();
                checker.initialize();
                checker.checkForUpdates(webhookHell);

                client.shutdown();
            } catch (IOException e) {
                client.shutdown();
                throw e;
            }
        });
    }

    private static void runUpdater(boolean fullUpdateCheck) {
        runProcessAndAlertOnException("Everest Update Checker", () -> {
            EverestVersionLister.checkEverestVersions();
            OlympusVersionLister.checkOlympusVersions();
            com.max480.everest.updatechecker.Main.updateDatabase(fullUpdateCheck);
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
            playlist = new JSONObject(IOUtils.toString(is, UTF_8));
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


    private interface ExplodyMethod {
        void run() throws Exception;
    }

    private static void runProcessAndAlertOnException(String name, ExplodyMethod process) {
        try {
            logger.info("Starting {}", name);
            process.run();
            logger.info("Ended {}", name);
        } catch (Exception e) {
            logger.error("Error while running {}", name, e);
            sendMessageToWebhook("Error while running " + name + ": " + e);
        }
    }

    private static void sendMessageToWebhook(String message) {
        try {
            WebhookExecutor.executeWebhook(
                    SecretConstants.UPDATE_CHECKER_LOGS_HOOK,
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/compute-engine.png",
                    "Crontab Runner",
                    message);
        } catch (IOException e) {
            logger.error("Error while sending message \"{}\"", message, e);
        }
    }
}
