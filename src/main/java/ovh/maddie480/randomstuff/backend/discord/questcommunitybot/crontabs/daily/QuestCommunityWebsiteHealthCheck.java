package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.daily;

import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.IOException;

public class QuestCommunityWebsiteHealthCheck {
    private static final Logger logger = LoggerFactory.getLogger(QuestCommunityWebsiteHealthCheck.class);

    public static void run() throws IOException {
        logger.debug("Vérification du site de Quest Community Bot");

        Document site = ConnectionUtils.jsoupGetWithRetry("https://maddie480.ovh/quest/mods");
        int count = site.select("a.btn-success").size();
        logger.debug("Le site affiche {} mods", count);
        if (count == 0) {
            throw new IOException("Le site n'affiche aucun mod");
        }

        site = ConnectionUtils.jsoupGetWithRetry("https://maddie480.ovh/quest/tools");
        count = site.select("a.btn-success").size();
        logger.debug("Le site affiche {} outils", count);
        if (count == 0) {
            throw new IOException("Le site n'affiche aucun outil");
        }
    }
}
