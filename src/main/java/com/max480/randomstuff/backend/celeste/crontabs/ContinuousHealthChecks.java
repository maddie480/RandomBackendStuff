package com.max480.randomstuff.backend.celeste.crontabs;

import com.google.common.collect.ImmutableMap;
import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import com.max480.randomstuff.backend.utils.WebhookExecutor;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.function.IOSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * This class checks the health of multiple platforms (the website, the bot, the mirror and GameBanana)
 * every minute. When multiple checks in a row fail, an alert is sent to a few webhooks.
 * This is different from CelesteStuffHealthCheck, which checks for more specific stuff way less frequently.
 */
public class ContinuousHealthChecks {
    private static final Logger logger = LoggerFactory.getLogger(ContinuousHealthChecks.class);

    private static final Map<String, Integer> servicesHealth = new HashMap<>();
    private static final Map<String, Boolean> servicesStatus = new HashMap<>();

    private static long lastBotAliveTime = 0;

    // called by the bot main loop to signify it's in fact online and running
    public static void botIsAlive() {
        lastBotAliveTime = System.currentTimeMillis();
    }

    public static void startChecking() {
        new Thread("Continuous Health Checks") {
            @Override
            public void run() {
                while (true) {
                    try {
                        // 0x0a.de health checks
                        checkURL("https://celestemodupdater.0x0a.de/banana-mirror", "484937.zip",
                                "Banana Mirror", SecretConstants.JADE_PLATFORM_HEALTHCHECK_HOOKS);
                        checkURL("https://celestenet.0x0a.de/api/status", "\"StartupTime\":",
                                "CelesteNet", SecretConstants.JADE_PLATFORM_HEALTHCHECK_HOOKS);
                        checkHealth(ContinuousHealthChecks::checkCelesteNetUDP,
                                "CelesteNet UDP", SecretConstants.JADE_PLATFORM_HEALTHCHECK_HOOKS);

                        // Update Checker health check
                        checkHealth(() -> System.currentTimeMillis() - UpdateCheckerTracker.lastEndOfCheckForUpdates.toInstant().toEpochMilli() < 1_800_000L /* 30m */,
                                "Update Checker", SecretConstants.NON_JADE_PLATFORM_HEALTHCHECK_HOOKS);

                        // max480-random-stuff health check
                        checkURL("https://max480-random-stuff.appspot.com/celeste/everest_update.yaml", "SpringCollab2020:",
                                "max480's Random Stuff Website", SecretConstants.NON_JADE_PLATFORM_HEALTHCHECK_HOOKS);

                        // GameBanana health checks
                        checkURL("https://gamebanana.com/games/6460", "Celeste",
                                "GameBanana Website", SecretConstants.NON_JADE_PLATFORM_HEALTHCHECK_HOOKS);
                        checkURL("https://files.gamebanana.com/bitpit/check.txt", "The check passed!",
                                "GameBanana File Server", SecretConstants.NON_JADE_PLATFORM_HEALTHCHECK_HOOKS);
                        checkURL("https://gamebanana.com/apiv8/Mod/150813?_csvProperties=@gbprofile", "\"https:\\/\\/gamebanana.com\\/dl\\/484937\"",
                                "GameBanana API", SecretConstants.NON_JADE_PLATFORM_HEALTHCHECK_HOOKS);

                        // backend check: this one posts to a private hook, since 99% of the time no-one cares or notices when it goes down. :p
                        checkHealth(() -> System.currentTimeMillis() - lastBotAliveTime < 1_800_000L,
                                "Random Stuff Backend", Collections.singletonList(SecretConstants.UPDATE_CHECKER_LOGS_HOOK));
                    } catch (Exception e) {
                        // this shouldn't happen, unless we cannot communicate with Discord.
                        logger.error("Uncaught exception happened during health check!", e);
                    }

                    try {
                        // wait until the start of the next minute.
                        Thread.sleep(60000 - (ZonedDateTime.now().getSecond() * 1000L
                                + ZonedDateTime.now().getNano() / 1_000_000) + 50);
                    } catch (InterruptedException e) {
                        // this shouldn't happen AT ALL.
                        logger.error("Sleep interrupted!", e);
                    }
                }
            }
        }.start();
    }

    private static void checkURL(String url, String content, String serviceName, List<String> webhookUrls) {
        checkHealth(() -> {
            URLConnection con = new URL(url).openConnection();
            con.setConnectTimeout(5000);
            con.setReadTimeout(10000);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                String s;
                while ((s = br.readLine()) != null) {
                    if (s.contains(content)) {
                        return true;
                    }
                }
            }

            return false;
        }, serviceName, webhookUrls);
    }

    private static boolean checkCelesteNetUDP() throws IOException {
        // first, check whether there was no UDP traffic in both directions for the last minute.
        for (String chart : Arrays.asList("CelesteNet_v2.udpDownlinkPpS", "CelesteNet_v2.udpUplinkPpS")) {
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://netdata.0x0a.de/api/v1/data?chart=" + chart + "&after=-60&gtime=60&group=sum")) {
                JSONObject resp = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                JSONArray data = resp.getJSONArray("data").getJSONArray(0);
                if (data.getInt(1) != 0) {
                    return true;
                }
            }
        }

        // if this is the case and there were 3 or more online players in the last minute, we got a problem!
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://netdata.0x0a.de/api/v1/data?chart=CelesteNet_v2.online&after=-60&gtime=60&group=max")) {
            JSONObject resp = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
            JSONArray data = resp.getJSONArray("data").getJSONArray(0);
            return data.getInt(1) < 3;
        }
    }

    private static void checkHealth(IOSupplier<Boolean> healthCheck,
                                    String serviceName, List<String> webhookUrls) {
        boolean result;

        try {
            result = healthCheck.get();
        } catch (Exception e) {
            logger.warn("Health check error for {}!", serviceName, e);
            result = false;
        }

        logger.debug("Health check result for {}: {}", serviceName, result);

        int currentHealth = servicesHealth.getOrDefault(serviceName, 3);
        boolean currentStatus = servicesStatus.getOrDefault(serviceName, true);

        if (result) {
            if (currentHealth < 3) {
                currentHealth++;
                logger.info("Health of {} increased to {}/3 HP", serviceName, currentHealth);

                // if health is at max and the service was declared down, declare it up again!
                if (currentHealth == 3 && !currentStatus) {
                    logger.info("Service {} has full HP!", serviceName);
                    currentStatus = true;
                    for (String webhook : webhookUrls) {
                        executeWebhookSafe(webhook, ":white_check_mark: **" + serviceName + "** is up again.");
                    }
                }
            }
        } else {
            if (currentHealth > 0) {
                currentHealth--;
                logger.warn("Health of {} decreased to {}/3 HP", serviceName, currentHealth);

                // if health is at zero and the service is officially up, declare it down.
                if (currentHealth == 0 && currentStatus) {
                    logger.warn("Service {} is dead!", serviceName);
                    currentStatus = false;
                    for (String webhook : webhookUrls) {
                        executeWebhookSafe(webhook, ":x: **" + serviceName + "** is down!");
                    }
                }
            }
        }

        servicesHealth.put(serviceName, currentHealth);
        servicesStatus.put(serviceName, currentStatus);
    }

    private static void executeWebhookSafe(String webhookUrl, String body) {
        try {
            WebhookExecutor.executeWebhook(
                    webhookUrl,
                    "https://cdn.discordapp.com/attachments/445236692136230943/921309225697804299/compute_engine.png",
                    "Platform Health Checks",
                    body,
                    ImmutableMap.of("X-Everest-Log", "true"));
        } catch (IOException e) {
            logger.error("Could not send message {} to webhook {}!", body, webhookUrl, e);
        }
    }
}
