package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.gamestats;

import org.json.JSONArray;
import org.json.JSONTokener;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.GZIPInputStream;

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
        try (InputStream is = openStreamWithTimeout("https://discord.com/api/v9/applications/detectable")) {
            System.out.println("Downloading game database...");
            gameDB = new JSONArray(new JSONTokener(is));
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

            HttpURLConnection connection = openConnectionWithTimeout(urlBuilder.toString());
            connection.setRequestProperty("Authorization", discordToken);

            JSONArray response;
            try (InputStream is = connectionToInputStream(connection)) {
                response = new JSONArray(new JSONTokener(is));
            }

            for (Object o : response) {
                output.put(o);
            }

            Thread.sleep((int) (Math.random() * 5000));
        }

        System.out.println("Writing result...");
        try (BufferedWriter bw = Files.newBufferedWriter(outputFile)) {
            output.write(bw);
        }
    }

    // == copy-paste from ConnectionUtils, since this needs to be self-contained

    private static HttpURLConnection openConnectionWithTimeout(String url) throws IOException {
        URLConnection con;

        try {
            con = new URI(url).toURL().openConnection();
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }

        con.setRequestProperty("User-Agent", "Maddie-Random-Stuff-Backend/1.0.0 (+https://github.com/maddie480/RandomBackendStuff)");
        con.setRequestProperty("Accept-Encoding", "gzip");

        con.setConnectTimeout(10000);
        con.setReadTimeout(30000);

        return (HttpURLConnection) con;
    }

    private static InputStream connectionToInputStream(HttpURLConnection con) throws IOException {
        InputStream is = con.getInputStream();
        if ("gzip".equals(con.getContentEncoding())) {
            return new GZIPInputStream(is);
        }
        return is;
    }

    private static InputStream openStreamWithTimeout(String url) throws IOException {
        return connectionToInputStream(openConnectionWithTimeout(url));
    }
}
