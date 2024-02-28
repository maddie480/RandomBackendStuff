package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.gamestats;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

public class GameDBBuilder {
    public static void main(String[] args) throws IOException, InterruptedException {
        Path outputFile = Paths.get("../games.json");
        if (Files.exists(outputFile)) {
            System.out.println("Output file already exists, skipping");
            return;
        }

        String discordToken = System.getenv("DISCORD_TOKEN");
        if (discordToken == null) {
            System.out.println("DISCORD_TOKEN not passed, skipping");
            return;
        }

        JSONArray gameDB;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://discord.com/api/v9/applications/detectable")) {
            System.out.println("Downloading game database...");
            gameDB = new JSONArray(IOUtils.toString(is, UTF_8));
        }

        List<Integer> indices = new LinkedList<>();
        for (int i = 0; i < gameDB.length(); i++) {
            indices.add(i);
        }
        Collections.shuffle(indices);

        JSONArray output = new JSONArray();

        while (indices.size() > 0) {
            System.out.println("Remaining games to fetch: " + indices.size());

            StringBuilder urlBuilder = new StringBuilder("https://discord.com/api/v9/applications/public?");
            for (int i = 0; i < 10 && indices.size() > 0; i++) {
                if (i != 0) {
                    urlBuilder.append('&');
                }

                long applicationId = gameDB.getJSONObject(indices.remove(0)).getLong("id");
                urlBuilder.append("application_ids=").append(applicationId);
            }

            HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(urlBuilder.toString());
            connection.setRequestProperty("Authorization", discordToken);

            JSONArray response;
            try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
                response = new JSONArray(IOUtils.toString(is, UTF_8));
            }

            for (Object o : response) {
                output.put(o);
            }

            Thread.sleep((int) (Math.random() * 5000));
        }

        System.out.println("Writing result...");
        try (OutputStream os = Files.newOutputStream(outputFile)) {
            IOUtils.write(output.toString(), os, UTF_8);
        }
    }
}
