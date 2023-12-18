package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.hourly;

import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.OutputStreamLogger;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

public class JsonUpdateChecker {
    private static final Logger log = LoggerFactory.getLogger(JsonUpdateChecker.class);

    private boolean firstLine = false;
    private boolean hadContent = false;

    public void checkForUpdates(MessageChannel target) throws Exception {
        {
            ZonedDateTime now = ZonedDateTime.now();

            if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY
                    || now.getHour() < 9 || now.getHour() > 17) {

                log.info("Skipping JSON update checking");
                return;
            }
        }

        try {
            log.debug("Checking for JSON updates");

            for (int i = 0; i < SecretConstants.JSON_URLS.size(); i++) {
                HttpURLConnection connection = (HttpURLConnection) ConnectionUtils.openConnectionWithTimeout(SecretConstants.JSON_URLS.get(i));
                connection.setRequestProperty("Authorization", "Basic " + SecretConstants.JSON_BASIC_AUTH);

                Path oldContents = Paths.get("old_json_contents_" + i + ".json");
                Path newContents = Paths.get("new_json_contents_" + i + ".json");

                try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
                    JSONObject contents = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                    FileUtils.writeStringToFile(newContents.toFile(), contents.toString(2), StandardCharsets.UTF_8);
                }

                if (Files.exists(oldContents)) {
                    Process p = OutputStreamLogger.redirectErrorOutput(log,
                            new ProcessBuilder("diff", "-w", "old_json_contents_" + i + ".json", "new_json_contents_" + i + ".json")
                                    .start());

                    String diff = IOUtils.toString(p.getInputStream(), StandardCharsets.UTF_8);

                    p.waitFor();

                    log.debug("Diff brute : {}", diff);

                    // formater le diff pour qu'il soit coloré syntaxiquement joliment sur Discord
                    firstLine = true;
                    hadContent = false;
                    diff = Arrays.stream(diff.split("\n"))
                            .map(line -> {
                                if (firstLine) {
                                    firstLine = false;
                                    return null;
                                }

                                if (line.isEmpty() || line.substring(1).trim().isEmpty()) {
                                    return null;
                                }
                                if (line.startsWith("<")) {
                                    hadContent = true;
                                    return "-" + line.substring(1);
                                } else if (line.startsWith(">")) {
                                    hadContent = true;
                                    return "+" + line.substring(1);
                                } else if (!line.startsWith("-") && hadContent) {
                                    hadContent = false;
                                    return "=====";
                                }

                                return null;
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.joining("\n"));

                    log.debug("Diff filtrée : {}", diff);
                    final String fullDiff = diff.replace("\r", "").replace("\n", "\r\n");
                    boolean sendFullDiff = false;

                    if (p.exitValue() == 0) {
                        log.debug("No diff");
                    } else if (diff.isEmpty()) {
                        log.debug("Il y avait une diff, mais il n'y en a plus. Certainement du pur whitespace.");
                    } else {
                        String messageBefore = "<@" + SecretConstants.OWNER_ID + "> Il y a du nouveau dans le fichier JSON que tu m'as demandé de surveiller :\n```diff\n";
                        String messageAfter = "\n```";
                        if (messageBefore.length() + diff.length() + messageAfter.length() > 2000) {
                            diff = diff.substring(0, 1985 - messageBefore.length() - messageAfter.length()) + "\n[diff tronqué]";
                            sendFullDiff = true;
                        }
                        log.info(messageBefore + diff + messageAfter);
                        target.sendMessage(messageBefore + diff + messageAfter).queue();

                        if (sendFullDiff) {
                            log.debug("Envoi de la full diff en PJ");
                            // envoyer la diff complète en PJ
                            target.sendMessage("Tous les changements ne rentrent pas dans un seul message Discord. Les voici :")
                                    .addFiles(FileUpload.fromData(fullDiff.getBytes(StandardCharsets.UTF_8), "full_diff.txt")).queue();
                        }
                    }

                    Files.delete(oldContents);
                } else {
                    log.debug("Il n'y a pas de fichier avec lequel comparer.");
                }

                Files.move(newContents, oldContents);
            }

        } catch (Exception e) {
            log.error("Erreur lors du relevé des MAJ de JSON", e);
            throw e;
        }
    }
}
