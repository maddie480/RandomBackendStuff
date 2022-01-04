package com.max480.discord.randombots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A (pretty silly) service aiming to change my GB profile background and Ripe supporter highlight on a daily basis (called every day at midnight).
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
                " by https://github.com/max4805/RandomBackendStuff/blob/main/src/celeste-backend-crontabs/StyleRefreshService.java */\n";

        css += "body::before { background: url('"
                + backgrounds[dayOfYear % backgrounds.length]
                + "') center / cover fixed; }";

        if (dayOfYear % backgrounds.length == 0) {
            css += "\n@media(max-aspect-ratio: 1.5) { body::before { " +
                    "background: url('https://cdn.discordapp.com/attachments/445236692136230943/871419904836894720/httpss3-us-west-2_007.png') center / cover fixed;" +
                    " } }";
        }

        double rng = Math.random();
        if (rng < 0.01) {
            css += "\n#IdentityModule .Avatar.IsRipeSupporter::before { background: url('https://media.discordapp.net/attachments/445236692136230943/927910115229696061/test5.png?width=128&height=128'); }\n" +
                    "#IdentityModule .Avatar.IsRipeSupporter { background: #398066; }";
        } else if (rng < 0.02) {
            css += "\n#IdentityModule .Avatar.IsRipeSupporter::before { background: url('https://media.discordapp.net/attachments/445236692136230943/921794754071650314/test.png?width=128&height=128'); }\n" +
                    "#IdentityModule .Avatar.IsRipeSupporter { background: #EED7A5; }";
        } else if (rng < 0.1) {
            css += "\n#IdentityModule .Avatar.IsRipeSupporter { background: linear-gradient(to bottom, fuchsia, white, blue); }";
        } else if (rng < 0.2) {
            css += "\n#IdentityModule .Avatar.IsRipeSupporter { background: linear-gradient(to bottom, #5BCEFA, #F5A9B8, #FFFFFF, #F5A9B8, #5BCEFA); }";
        } else if (rng < 0.3) {
            css += "\n#IdentityModule .Avatar.IsRipeSupporter { background: linear-gradient(to bottom, #FDF336, #FBFAFD, #9F5AD2, #2E2B2E); }";
        } else if (rng < 0.4) {
            css += "\n#IdentityModule .Avatar.IsRipeSupporter { background: linear-gradient(to bottom, #EC422A, #831121, #8A2E98, #DB49E3); }";
        } else if (rng < 0.5) {
            css += "\n#IdentityModule .Avatar.IsRipeSupporter { background: linear-gradient(to bottom, #F07357, #444, #5D9DBF); }";
        } else if (rng < 0.6) {
            css += "\n#IdentityModule .Avatar.IsRipeSupporter {\n" +
                    "    background: linear-gradient(to bottom, #FF2457, #FF8524, #FFC824, #88FF24, #24BBFF, #B824FF);\n" +
                    "    padding: 0.5em;\n" +
                    "    border-radius: 1em;\n" +
                    "}\n" +
                    "#IdentityModule .Avatar.IsRipeSupporter::before { border-radius: 0.5em; }";
        } else if (rng < 0.7) {
            css += "\n#IdentityModule .Avatar.IsRipeSupporter { background: linear-gradient(to bottom, #FF2457, #FF8524, #FFC824, #88FF24, #24BBFF, #B824FF); }";
        } else if (rng < 0.8) {
            css += "\n#IdentityModule .Avatar.IsRipeSupporter {\n" +
                    "    padding: 0;\n" +
                    "    border-radius: 1em;\n" +
                    "    background: none;\n" +
                    "    box-shadow: 0 0 15px 10px #F07357;\n" +
                    "    margin: 1em 0 1.5em 0;\n" +
                    "}\n" +
                    "#IdentityModule .Avatar.IsRipeSupporter::before { border-radius: 1em; }";
        } else if (rng < 0.9) {
            css += "\n#IdentityModule .Avatar.IsRipeSupporter { background: linear-gradient(to bottom, #FF5F42, #8A0F36, #DE2A2A); }";
        } else {
            css += "\n#IdentityModule .Avatar.IsRipeSupporter { background: pink; }";
        }

        CloudStorageUtils.sendStringToCloudStorage(css, "gamebanana-profile-background.css", "text/css");

        String report = "RNG value is " + rng + ", day of year is " + dayOfYear + " (% " + backgrounds.length + " = " + (dayOfYear % backgrounds.length)
                + ") => New CSS pushed:\n" + css;
        logger.info(report);
    }
}
