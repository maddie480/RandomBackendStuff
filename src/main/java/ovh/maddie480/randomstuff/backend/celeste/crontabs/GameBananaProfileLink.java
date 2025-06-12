package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.DatabaseUpdater;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class GameBananaProfileLink {
    private static final Logger logger = LoggerFactory.getLogger(GameBananaProfileLink.class);

    public static void main(String[] args) throws IOException {
        JSONObject glob = new JSONObject();
        JSONObject categoryIcons = new JSONObject();
        JSONObject submitterInfo = new JSONObject();
        glob.put("categories", categoryIcons);
        glob.put("submitters", submitterInfo);

        for (String itemtype : DatabaseUpdater.VALID_CATEGORIES) {
            JSONObject categoryIconsForType = new JSONObject();
            categoryIcons.put(itemtype, categoryIconsForType);

            int i = 1;
            while (true) {
                int pageNumber = i;
                logger.debug("Querying {} page {}, got {} cats and {} submitters so far", itemtype, pageNumber, categoryIconsForType.length(), submitterInfo.length());
                JSONArray page = ConnectionUtils.runWithRetry(() -> {
                    try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://gamebanana.com/apiv8/" + itemtype + "/ByGame?_aGameRowIds[]=6460&_csvProperties=_aCategory,_aSubmitter&_nPage=" + pageNumber + "&_nPerpage=50")) {
                        return new JSONArray(new JSONTokener(is));
                    }
                });

                if (page.isEmpty()) break;
                for (Object o : page) {
                    JSONObject item = (JSONObject) o;

                    categoryIconsForType.put(Integer.toString(item.getJSONObject("_aCategory").getInt("_idRow")),
                            item.getJSONObject("_aCategory").getString("_sIconUrl"));

                    JSONObject sub = new JSONObject();
                    sub.put("profile", item.getJSONObject("_aSubmitter").getString("_sProfileUrl"));
                    sub.put("avatar", item.getJSONObject("_aSubmitter").getString("_sAvatarUrl"));
                    submitterInfo.put(item.getJSONObject("_aSubmitter").getString("_sName"), sub);
                }
                i++;
            }
        }

        try (OutputStream os = Files.newOutputStream(Paths.get("/shared/celeste/submitter_and_author_info.json"));
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {

            glob.write(bw);
        }
    }

    public static void checkEmbedBuilder() throws IOException {
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(
                "https://maddie480.ovh/discord/bananabot-embed-builder-check?key=" + SecretConstants.RELOAD_SHARED_SECRET);
        connection.setRequestMethod("POST");
        connection.setReadTimeout(300_000);

        if (connection.getResponseCode() != 200) {
            throw new IOException("BananaBot embed builder check failed!");
        }
    }
}
