package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.gamestats;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class GameDB {
    private static final Logger log = LoggerFactory.getLogger(GameDB.class);

    public static MessageEmbed findGame(String name) {
        return findGame(name, false);
    }

    public static MessageEmbed findGame(String name, boolean ignoreCase) {
        JSONArray gameDB;
        try (InputStream is = new FileInputStream("/app/static/games.json")) {
            log.debug("Reloading game DB");
            gameDB = new JSONArray(new JSONTokener(is));
        } catch (Exception e) {
            log.error("Impossible de lire la base de données de jeux", e);
            return null;
        }

        try {
            JSONObject result = null;
            for (Object object : gameDB) {
                JSONObject game = (JSONObject) object;

                boolean matches = false;
                if (ignoreCase) {
                    if (game.getString("name").equalsIgnoreCase(name)) {
                        matches = true;
                    } else if ((game.has("aliases"))) {
                        for (Object alias : game.getJSONArray("aliases")) {
                            if (name.equalsIgnoreCase(alias.toString())) {
                                matches = true;
                            }
                        }
                    }
                } else {
                    if (game.getString("name").equals(name)) {
                        matches = true;
                    } else if ((game.has("aliases"))) {
                        for (Object alias : game.getJSONArray("aliases")) {
                            if (name.equals(alias)) {
                                matches = true;
                                break;
                            }
                        }
                    }
                }

                if (matches) {
                    if (result != null) {
                        log.warn("Il y a plusieurs jeux qui correspondent => abandon");
                        return null;
                    } else {
                        result = game;
                    }
                }
            }

            if (result != null) {
                EmbedBuilder builder = new EmbedBuilder()
                        .setTitle(result.getString("name"));

                if (result.has("description")) {
                    builder.setDescription(result.getString("description"));
                }

                if (result.has("icon") && result.has("id")) {
                    builder.setThumbnail("https://cdn.discordapp.com/app-icons/" + result.getString("id") + "/"
                            + result.getString("icon") + ".webp");
                }

                if (result.has("splash") && result.has("id")) {
                    builder.setImage("https://cdn.discordapp.com/app-icons/" + result.getString("id") + "/"
                            + result.getString("splash") + ".webp?size=512");
                }

                if (result.has("developers") && !result.getJSONArray("developers").isEmpty()) {
                    List<String> devs = new ArrayList<>();
                    for (Object dev : result.getJSONArray("developers")) {
                        devs.add(((JSONObject) dev).getString("name"));
                    }
                    builder.addField("Développé par", String.join(", ", devs), false);
                }

                if (result.has("publishers") && !result.getJSONArray("publishers").isEmpty()) {
                    List<String> devs = new ArrayList<>();
                    for (Object dev : result.getJSONArray("publishers")) {
                        devs.add(((JSONObject) dev).getString("name"));
                    }
                    builder.addField("Publié par", String.join(", ", devs), false);
                }

                if (result.has("third_party_skus")) {
                    for (Object vendor : result.getJSONArray("third_party_skus")) {
                        JSONObject vendorObj = (JSONObject) vendor;
                        if (vendorObj.get("distributor").equals("steam")) {
                            builder.addField("Steam", "https://store.steampowered.com/app/" + vendorObj.getString("id") + "/", false);
                        }
                    }
                }

                builder.setFooter("Base de données Discord", null);

                log.debug("Je renvoie quelque chose");
                return builder.build();
            }
        } catch (Exception e) {
            log.error("Impossible de lire la base de données de jeux", e);
        }

        log.debug("Je renvoie null");
        return null;
    }
}
