package ovh.maddie480.randomstuff.backend.discord.crontabs;

import com.google.common.collect.ImmutableMap;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.randomstuff.backend.discord.modstructureverifier.ModStructureVerifier;
import ovh.maddie480.randomstuff.backend.discord.timezonebot.TimezoneBot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Uploads the server count for Timezone Bot, Mod Structure Verifier and Games Bot to the shared storage,
 * for display on the website.
 * Run every day.
 */
public class ServerCountUploader {
    private static final Logger logger = LoggerFactory.getLogger(ServerCountUploader.class);

    public static void run() throws IOException {
        int gamesBotServerCount = getServerUsageOfSlashCommandBot("Games Bot");
        int timezoneBotServerCount = getServerUsageOfSlashCommandBot("Timezone Bot");
        int bananaBotServerCount = getServerUsageOfSlashCommandBot("BananaBot");

        // to know how many servers use the Custom Slash Commands bot, just list out how many guild ids have created custom commands!
        int customSlashCommandsServerCount = new File("/shared/discord-bots/custom-slash-commands").list().length;

        // write a file with all counts in it and send it to Cloud Storage.
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            YamlUtil.dump(ImmutableMap.of(
                    "TimezoneBotLite", timezoneBotServerCount,
                    "TimezoneBotFull", TimezoneBot.getServerCount(),
                    "ModStructureVerifier", ModStructureVerifier.getServerCount(),
                    "GamesBot", gamesBotServerCount,
                    "CustomSlashCommands", customSlashCommandsServerCount,
                    "BananaBot", bananaBotServerCount
            ), os);

            String yamlData = os.toString(StandardCharsets.UTF_8);
            Files.writeString(Paths.get("/shared/discord-bots/bot-server-counts.yaml"), yamlData, StandardCharsets.UTF_8);
            logger.info("Stats saved on Cloud Storage: {}", yamlData);
        }

        // TopGGCommunicator.refreshServerCounts(gamesBotServerCount, customSlashCommandsServerCount, timezoneBotServerCount);
    }

    /**
     * Roughly estimates the server count of an interaction-based bot, by counting the amount of servers it was
     * used on in the last 30 days.
     */
    private static int getServerUsageOfSlashCommandBot(String botName) {
        Pattern m = Pattern.compile(".*Guild ([0-9]+) used the " + botName + "!.*");
        try (Stream<Path> frontendLogs = Files.list(Paths.get("/logs"))) {
            return (int) frontendLogs
                    .filter(p -> p.getFileName().toString().endsWith(".jetty.log"))
                    .map(p -> {
                        try (Stream<String> lines = Files.lines(p)) {
                            return lines
                                    .map(m::matcher)
                                    .filter(Matcher::matches)
                                    .map(l -> Long.parseLong(l.group(1)))
                                    .collect(Collectors.toList());
                        } catch (IOException e) {
                            logger.warn("Could not check backend log entries!", e);
                            return Collections.<Long>emptyList();
                        }
                    })
                    .flatMap(List::stream)
                    .distinct()
                    .count();
        } catch (IOException e) {
            logger.warn("Could not check backend log entries!", e);
            return 0;
        }
    }
}
