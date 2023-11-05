package ovh.maddie480.randomstuff.backend.discord.slashcommandbot;

import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

public class SlackToDiscord {
    private static final Logger logger = LoggerFactory.getLogger(SlackToDiscord.class);

    public static Pair<MessageCreateData, Boolean> sendSlashCommand(long userId, long channelId, String command, String token, String targetUrl) {

        StringBuilder urlEncodedStringBuilder = new StringBuilder()
                .append("user_name=")
                .append(userId)
                .append("&user_id=")
                .append(userId)
                .append("&channel_name=")
                .append(channelId)
                .append("&command=");

        String[] commandSplit = command.split(" ", 2);
        urlEncodedStringBuilder.append(URLEncoder.encode(commandSplit[0], UTF_8));

        if (commandSplit.length == 2) {
            urlEncodedStringBuilder.append("&text=").append(URLEncoder.encode(commandSplit[1], UTF_8));
        }

        urlEncodedStringBuilder.append("&token=").append(Objects.requireNonNullElse(token, "none"));

        String request = urlEncodedStringBuilder.toString();

        try {
            JSONObject jsonObject;
            String local = new CommandsMovedFromWebsite().tryRunCommand(commandSplit[0], commandSplit.length == 2 ? commandSplit[1] : null, Long.toString(userId));

            if (local != null) {
                logger.debug("Got response locally: {}", local);
                jsonObject = new JSONObject(local);
            } else {
                logger.debug("Sending request to slash command at URL {}: {}", targetUrl, request);
                HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(targetUrl);

                connection.setDoInput(true);
                connection.setDoOutput(true);

                connection.setRequestMethod("POST");

                connection.connect();

                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream()));
                writer.write(request);
                writer.close();

                if (connection.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(ConnectionUtils.connectionToInputStream(connection), UTF_8));

                    StringBuilder response = new StringBuilder();
                    String s;
                    while ((s = reader.readLine()) != null) {
                        response.append(s).append("\n");
                    }
                    reader.close();

                    logger.debug("Response: {}", response);

                    jsonObject = new JSONObject(response.toString());
                } else {
                    logger.error("Réponse non 200 OK");

                    logger.debug("Connexion fermée");
                    connection.disconnect();

                    return Pair.of(new MessageCreateBuilder()
                            .setContent("J'ai reçu une réponse en erreur de la part de la slash command (code " + connection.getResponseCode() + "). Désolé.\n<:A_ckc:644445091884171264>")
                            .build(), true);
                }

                logger.debug("Connexion fermée");
                connection.disconnect();
            }

            MessageCreateBuilder discordMessageBuilder = new MessageCreateBuilder();

            if (jsonObject.has("text")) {
                String responseText = jsonObject.getString("text");

                // convertir les (fausses) mentions Slack en mentions Discord
                String filteredResponse = responseText
                        .replace("@" + userId, "<@" + userId + ">")
                        .replace("#" + channelId, "<#" + channelId + ">")
                        .replace(":ckc:", "<:A_ckc:644445091884171264>");

                logger.debug("Réponse après conversion en Discord : {}", filteredResponse);
                discordMessageBuilder.setContent(filteredResponse);
            }

            if (jsonObject.has("attachments")) {
                JSONObject attachmentObject = (JSONObject) ((JSONArray) jsonObject.get("attachments")).get(0);

                logger.debug("== Converting attachment to embed");
                EmbedBuilder attachmentEmbed = new EmbedBuilder();

                if (attachmentObject.has("title")) {
                    logger.debug("Title = {}", attachmentObject.getString("title"));
                    attachmentEmbed.setTitle(attachmentObject.getString("title"));
                }

                if (attachmentObject.has("text")) {
                    logger.debug("Description = text = {}", attachmentObject.getString("text"));
                    attachmentEmbed.setDescription(attachmentObject.getString("text"));
                }

                if (attachmentObject.has("image_url")) {
                    logger.debug("Image URL = {}", attachmentObject.getString("image_url"));
                    attachmentEmbed.setImage(attachmentObject.getString("image_url"));
                }

                logger.debug("== Converted attachment to embed");

                discordMessageBuilder.setEmbeds(attachmentEmbed.build());
            }

            return Pair.of(discordMessageBuilder.build(),
                    !jsonObject.has("response_type") || !jsonObject.getString("response_type").equals("in_channel"));

        } catch (Exception e) {
            logger.error("Oh mon dieu, c'est pas censé arriver ça", e);

            return Pair.of(new MessageCreateBuilder().setContent("Oh mon dieu, tout a explosé ! `" + e + "`\n<:A_ckc:644445091884171264>").build(), true);
        }
    }
}
