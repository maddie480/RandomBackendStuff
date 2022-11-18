package com.max480.randomstuff.backend.celeste.crontabs;

import com.max480.randomstuff.backend.utils.CloudStorageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A (pretty silly) service aiming to change my GB profile background on a daily basis (called every day).
 * The result is sent to https://storage.googleapis.com/max480-random-stuff.appspot.com/gamebanana-profile-background.css
 */
public class StyleRefreshService {
    private static final Logger logger = LoggerFactory.getLogger(StyleRefreshService.class);

    public static void refreshGameBananaCSS() throws IOException {
        String[] backgrounds = new String[]{
                "https://cdn.discordapp.com/attachments/445236692136230943/871419692617699328/SplashScreen.png",
                "https://cdn.discordapp.com/attachments/445236692136230943/850141381888311336/pride_month_maddy.png",
                "https://cdn.discordapp.com/attachments/445236692136230943/864966482672615434/complete-6.png",
                "https://cdn.discordapp.com/attachments/445236692136230943/864938905278742579/httpss3-us-west-2_016.png",
                "https://cdn.discordapp.com/attachments/445236692136230943/864966484966768640/complete-7.png",
                "https://cdn.discordapp.com/attachments/445236692136230943/864966491903623198/complete-8.png",
                "https://cdn.discordapp.com/attachments/445236692136230943/868246688899424306/httpss3-us-west-2_056.png"
        };

        int dayOfYear = ZonedDateTime.now(ZoneId.of("Europe/Paris")).getDayOfYear();

        String css = "/* Generated on " + ZonedDateTime.now(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("MMMM d, yyyy HH:mm zzz", Locale.ENGLISH)) + "" +
                " by https://github.com/max4805/RandomBackendStuff/blob/main/src/main/java/com/max480/randomstuff/backend/celeste/crontabs/StyleRefreshService.java */\n";

        css += "body::before { background: url('"
                + backgrounds[dayOfYear % backgrounds.length]
                + "') center / cover fixed; }";

        if (dayOfYear % backgrounds.length == 0) {
            css += "\n@media(max-aspect-ratio: 1.5) { body::before { " +
                    "background: url('https://cdn.discordapp.com/attachments/445236692136230943/871419904836894720/httpss3-us-west-2_007.png') center / cover fixed;" +
                    " } }";
        }

        CloudStorageUtils.sendStringToCloudStorage(css, "gamebanana-profile-background.css", "text/css");

        String report = "Day of year is " + dayOfYear + " (% " + backgrounds.length + " = " + (dayOfYear % backgrounds.length)
                + ") => New CSS pushed:\n" + css;
        logger.info(report);
    }
}
