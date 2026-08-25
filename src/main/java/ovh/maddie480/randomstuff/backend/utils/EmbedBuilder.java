package ovh.maddie480.randomstuff.backend.utils;

import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.json.JSONArray;
import org.json.JSONObject;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.ModDatabase;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.CategoryRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ModRecord;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * Copy-paste of https://github.com/maddie480/RandomStuffWebsite/blob/main/src/main/java/ovh/maddie480/randomstuff/frontend/discord/bananabot/EmbedBuilder.java
 */
public class EmbedBuilder {
    public static void integrityCheck() throws IOException {
        try (ModDatabase database = new ModDatabase()) {
            for (ModRecord mod : database.allMods) {
                buildEmbedFor(mod);
            }
        }
    }

    /**
     * Builds a GameBanana embed for the given mod.
     *
     * @param mod The mod to build an embed for, in mod_search_database.yaml entry format
     * @return A 1-element JSON array ready to be inserted as the "embeds" field of a Discord webhook call
     */
    public static JSONArray buildEmbedFor(ModRecord mod) {
        JSONObject embed = new JSONObject();
        embed.put("title", mod.name);
        embed.put("color", 16769075);
        embed.put("timestamp", Instant.ofEpochSecond(mod.createdDate).atZone(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        embed.put("url", mod.pageUrl);

        {
            JSONObject image = new JSONObject();
            embed.put("image", image);
            image.put("url", mod.screenshots[0].mainUrl);
        }

        {
            JSONObject footer = new JSONObject();
            embed.put("footer", footer);
            footer.put("text", "GameBanana");
            footer.put("icon_url", "https://images.gamebanana.com/static/img/favicon/128x128.png");
        }

        String iconUrl = "";
        {
            JSONArray fields = new JSONArray();
            embed.put("fields", fields);
            {
                List<String> categorySections = new LinkedList<>();
                CategoryRecord currentCategory = mod.category;
                while (currentCategory != null) {
                    categorySections.addFirst("[" + MarkdownSanitizer.escape(currentCategory.name) + "](" + currentCategory.pageUrl + ")");
                    if (iconUrl.isEmpty()) iconUrl = currentCategory.iconUrl;
                    currentCategory = currentCategory.parent;
                }

                JSONObject category = new JSONObject();
                fields.put(category);
                category.put("name", "Category");
                category.put("value", String.join(" > ", categorySections));
                category.put("inline", false);
            }
            {
                DecimalFormat thousandSeparated = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
                JSONObject stats = new JSONObject();
                fields.put(stats);
                stats.put("name", "Stats");
                stats.put("inline", false);
                stats.put("value", ":inbox_tray: " + thousandSeparated.format(mod.downloads) + " / " +
                        ":eye: " + thousandSeparated.format(mod.views) + " " +
                        "/ :heart: " + thousandSeparated.format(mod.likes));
            }
        }

        embed.put("description", MarkdownSanitizer.escape(mod.summary));
        {
            JSONObject author = new JSONObject();
            embed.put("author", author);
            author.put("name", mod.author.name);
            author.put("icon_url", mod.author.avatarUrl);
            author.put("url", mod.author.profileUrl);
        }

        if (!iconUrl.isEmpty()) {
            JSONObject thumbnail = new JSONObject();
            embed.put("thumbnail", thumbnail);
            thumbnail.put("url", iconUrl);
        }

        JSONArray embeds = new JSONArray();
        embeds.put(embed);
        return embeds;
    }
}
