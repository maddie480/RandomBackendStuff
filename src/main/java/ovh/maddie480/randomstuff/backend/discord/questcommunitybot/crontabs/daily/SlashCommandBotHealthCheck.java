package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.daily;

import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;

import static java.nio.charset.StandardCharsets.UTF_8;

public class SlashCommandBotHealthCheck {
    private static final Logger logger = LoggerFactory.getLogger(SlashCommandBotHealthCheck.class);

    public static void checkSlashCommands() throws IOException {
        for (String command : SecretConstants.SLASH_COMMAND_BOT_HEALTHCHECKS) {
            String[] commandSplit = command.split(";");

            String url = "https://maddie480.ovh/mattermost/" + commandSplit[0];

            String body = "user_name=healthcheck&user_id=healthcheck&channel_name=healthcheck&command=/" + commandSplit[0]
                    + "&token=" + commandSplit[1]
                    + "&text=" + commandSplit[2];

            logger.debug("--> {}\nBody: {}", url, body);

            long start = System.currentTimeMillis();
            HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(url);

            connection.setDoInput(true);
            connection.setDoOutput(true);

            connection.setRequestMethod("POST");

            connection.connect();

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream()));
            writer.write(body);
            writer.close();

            String response = IOUtils.toString(ConnectionUtils.connectionToInputStream(connection), UTF_8);
            logger.debug("<-- {} en {} ms\n{}", connection.getResponseCode(), System.currentTimeMillis() - start, response);
            if (response.contains(":ckc:")) {
                throw new IOException("La commande /" + commandSplit[0] + " est kc :a:");
            }
        }

        // testons le quick importer aussi
        String parrotQuickImporter = IOUtils.toString(ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/static/parrot-quick-importer-online.html"), UTF_8);
        if (!parrotQuickImporter.contains("\"Explody Parrot\"") || !parrotQuickImporter.contains("\"Zscaler Parrot\"")) {
            throw new IOException("Parrot Quick Importer est par terre ! :a:");
        }
    }
}
