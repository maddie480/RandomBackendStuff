package ovh.maddie480.randomstuff.backend.utils;

import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import ovh.maddie480.everest.updatechecker.YamlUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Copy-paste of https://github.com/maddie480/RandomStuffWebsite/blob/main/src/main/java/ovh/maddie480/randomstuff/frontend/discord/bananabot/EmbedBuilder.java
 */
public class EmbedBuilder {
    public static void integrityCheck() throws IOException {
        List<Map<String, Object>> mods;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/mod_search_database.yaml")) {
            mods = YamlUtil.load(is);
        }
        for (Map<String, Object> mod : mods) buildEmbedFor(mod);
    }

    /**
     * Builds a GameBanana embed for the given mod.
     *
     * @param mod The mod to build an embed for, in mod_search_database.yaml entry format
     * @return A 1-element JSON array ready to be inserted as the "embeds" field of a Discord webhook call
     */
    public static JSONArray buildEmbedFor(Map<String, Object> mod) throws IOException {
        // This info isn't present in mod_search_database.yaml, but may be present in a purpose-built file updated daily:
        // submitter_and_author_info.json. Except them to be null for brand-new mods though!
        String categoryIconUrl = null;
        String subcategoryIconUrl = null;
        String authorProfileUrl = null;
        String authorAvatarUrl = null;

        {
            JSONObject categoryAndSubmitterInfo;
            try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/submitter_and_author_info.json"))) {
                categoryAndSubmitterInfo = new JSONObject(new JSONTokener(is));
            }

            JSONObject catDict = categoryAndSubmitterInfo.getJSONObject("categories").getJSONObject((String) mod.get("GameBananaType"));
            if (catDict.has(mod.get("CategoryId").toString())) {
                categoryIconUrl = catDict.getString(mod.get("CategoryId").toString());
            }
            if (mod.containsKey("SubcategoryId") && catDict.has(mod.get("SubcategoryId").toString())) {
                subcategoryIconUrl = catDict.getString(mod.get("SubcategoryId").toString());
            }

            if (categoryAndSubmitterInfo.getJSONObject("submitters").has((String) mod.get("Author"))) {
                JSONObject authorInfo = categoryAndSubmitterInfo.getJSONObject("submitters").getJSONObject((String) mod.get("Author"));
                authorAvatarUrl = authorInfo.getString("avatar");
                authorProfileUrl = authorInfo.getString("profile");
            }
        }

        JSONObject embed = new JSONObject();
        embed.put("title", mod.get("Name"));
        embed.put("color", 16769075);
        embed.put("timestamp", Instant.ofEpochSecond(Long.parseLong(mod.get("CreatedDate").toString())).atZone(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        embed.put("url", mod.get("PageURL"));

        {
            JSONObject image = new JSONObject();
            embed.put("image", image);
            image.put("url", ((List<String>) mod.get("Screenshots")).getFirst());
        }

        {
            JSONObject footer = new JSONObject();
            embed.put("footer", footer);
            footer.put("text", "GameBanana");
            footer.put("icon_url", "https://images.gamebanana.com/static/img/favicon/128x128.png");
        }

        {
            JSONArray fields = new JSONArray();
            embed.put("fields", fields);
            if (mod.containsKey("CategoryName")) {
                String lowercaseItemtype = ((String) mod.get("GameBananaType")).toLowerCase();
                String categoryLink = "[" + MarkdownSanitizer.escape((String) mod.get("CategoryName")) + "](https://gamebanana.com/" + lowercaseItemtype + "s/cats/" + mod.get("CategoryId") + ")";
                String subcategoryLink = null;

                if (mod.containsKey("SubcategoryName")) {
                    subcategoryLink = "[" + MarkdownSanitizer.escape((String) mod.get("SubcategoryName")) + "](https://gamebanana.com/" + lowercaseItemtype + "s/cats/" + mod.get("SubcategoryId") + ")";
                } else if (!lowercaseItemtype.equals("mod")) {
                    // the APIs treat itemtypes other than Mod as a parent category, so we're doing the same
                    subcategoryLink = categoryLink;
                    String itemtype = (String) mod.get("GameBananaType");
                    if (itemtype.equals("Wip")) itemtype = "WiP";
                    categoryLink = "[" + itemtype + "s](https://gamebanana.com/" + lowercaseItemtype + "s/games/6460)";
                }

                JSONObject category = new JSONObject();
                fields.put(category);
                category.put("name", "Category");
                category.put("value", categoryLink + (subcategoryLink == null ? "" : (" > " + subcategoryLink)));
                category.put("inline", false);
            }
            {
                DecimalFormat thousandSeparated = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
                JSONObject stats = new JSONObject();
                fields.put(stats);
                stats.put("name", "Stats");
                stats.put("inline", false);
                stats.put("value", ":inbox_tray: " + thousandSeparated.format((int) mod.get("Downloads")) + " / " +
                        ":eye: " + thousandSeparated.format((int) mod.get("Views")) + " " +
                        "/ :heart: " + thousandSeparated.format((int) mod.get("Likes")));
            }
        }

        embed.put("description", MarkdownSanitizer.escape((String) mod.get("Description")));
        {
            JSONObject author = new JSONObject();
            embed.put("author", author);
            author.put("name", mod.get("Author"));
            if (authorAvatarUrl != null) author.put("icon_url", authorAvatarUrl);
            if (authorProfileUrl != null) author.put("url", authorProfileUrl);
        }

        if (categoryIconUrl != null || subcategoryIconUrl != null) {
            JSONObject thumbnail = new JSONObject();
            embed.put("thumbnail", thumbnail);
            thumbnail.put("url", subcategoryIconUrl != null ? subcategoryIconUrl : categoryIconUrl);
        }

        JSONArray embeds = new JSONArray();
        embeds.put(embed);
        return embeds;
    }
}
