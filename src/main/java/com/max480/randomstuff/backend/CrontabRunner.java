package com.max480.randomstuff.backend;

import com.max480.everest.updatechecker.YamlUtil;
import com.max480.randomstuff.backend.celeste.FrontendTaskReceiver;
import com.max480.randomstuff.backend.celeste.crontabs.*;
import com.max480.randomstuff.backend.discord.crontabs.*;
import com.max480.randomstuff.backend.discord.serverjanitor.ServerJanitorBot;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import com.max480.randomstuff.backend.utils.WebhookExecutor;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The class responsible for running all recurring processes,
 * and sending out alerts if one of them crashes.
 */
public class CrontabRunner {
    private static final Logger logger = LoggerFactory.getLogger(CrontabRunner.class);

    private static boolean fullUpdateCheck = true;
    private static long lastRun = System.currentTimeMillis();

    public static void main(String[] args) {
        // start the health checks
        ContinuousHealthChecks.startChecking();

        // start communication channel with the frontend
        FrontendTaskReceiver.start();

        // load #celeste_news_network state from disk
        MastodonUpdateChecker.loadFile();
        OlympusNewsUpdateChecker.loadPreviouslyPostedNews();

        // load update checker config from secret constants
        ByteArrayInputStream is = new ByteArrayInputStream(SecretConstants.UPDATE_CHECKER_CONFIG.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> config = YamlUtil.load(is);
        com.max480.everest.updatechecker.Main.serverConfig = new com.max480.everest.updatechecker.ServerConfig(config);

        // register update checker tracker
        com.max480.everest.updatechecker.EventListener.addEventListener(new UpdateCheckerTracker());

        while (true) {
            ZonedDateTime startTime = ZonedDateTime.now();

            if (startTime.getHour() == 18 && startTime.getMinute() == 0) {
                runDailyProcesses();
            }

            if (startTime.getMinute() == 0) {
                runHourlyProcesses();
            }

            if (startTime.getMinute() % 15 == 0) {
                run15MinuteProcesses();
            }

            if (startTime.getMinute() % 2 == 0) {
                runUpdater();
            }

            lastRun = System.currentTimeMillis();

            try {
                // wait until the start of the next minute (+ 50 ms)
                Thread.sleep(60000 - (ZonedDateTime.now().getSecond() * 1000
                        + ZonedDateTime.now().getNano() / 1_000_000) + 50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void forceFullUpdateCheck() {
        fullUpdateCheck = true;
    }

    public static long getLastRun() {
        return lastRun;
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

            // update tasks
            AutoLeaver.main(null);
            CustomSlashCommandsCleanup.housekeep();
            ServerCountUploader.run();
            ArbitraryModAppCacher.refreshArbitraryModAppCache();
            CustomEntityCatalogGenerator.main(null);
            housekeepArbitraryModApp();

            // health checks
            WorldClockHealthCheck.main(null);
            CelesteStuffHealthCheck.checkEverestExists(true);
            CelesteStuffHealthCheck.checkOlympusExists(true);
            CelesteStuffHealthCheck.checkBananaMirrorDatabaseMatch();
            CelesteStuffHealthCheck.checkFontGeneratorLibGdx();
            CelesteStuffHealthCheck.checkFontGeneratorBMFont();
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
            checkArbitraryModApp();
        });
    }

    private static void runHourlyProcesses() {
        runProcessAndAlertOnException("Hourly processes", () -> {
            forceFullUpdateCheck();

            // update tasks
            UpdateCheckerTracker.updatePrivateHelpersFromGitHub();
            CollabAutoHider.run();
            TempFolderCleanup.cleanUpFolder("/shared/temp", 1, path -> true);
            TempFolderCleanup.cleanUpFolder("/logs", 30, path -> path.getFileName().toString().endsWith(".backend.log"));
            TopGGCommunicator.refreshVotes(CrontabRunner::sendMessageToWebhook);
            UsageStatsService.writeWeeklyStatisticsToFile();

            // health checks
            CelesteStuffHealthCheck.updateCheckerHealthCheck();
            CelesteStuffHealthCheck.checkOlympusAPIs();
            CelesteStuffHealthCheck.checkEverestExists(false);
            CelesteStuffHealthCheck.checkOlympusExists(false);
        });
    }

    private static void run15MinuteProcesses() {
        runProcessAndAlertOnException("15-minute processes", () -> {
            MastodonUpdateChecker.checkForUpdates();
            OlympusNewsUpdateChecker.checkForUpdates();
            EverestVersionLister.checkEverestVersions();
        });
    }

    private static void runUpdater() {
        runProcessAndAlertOnException("Everest Update Checker", () -> {
            com.max480.everest.updatechecker.Main.updateDatabase(fullUpdateCheck);
            fullUpdateCheck = false;

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
                    "https://cdn.discordapp.com/attachments/445236692136230943/921309225697804299/compute_engine.png",
                    "Crontab Runner",
                    message);
        } catch (IOException e) {
            logger.error("Error while sending message \"{}\"", message, e);
        }
    }
}
