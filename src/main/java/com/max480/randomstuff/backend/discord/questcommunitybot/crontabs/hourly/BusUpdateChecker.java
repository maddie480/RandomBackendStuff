package com.max480.randomstuff.backend.discord.questcommunitybot.crontabs.hourly;

import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public class BusUpdateChecker {
    private static final Logger log = LoggerFactory.getLogger(BusUpdateChecker.class);

    public static void runCheckForUpdates(MessageChannel target) throws IOException {
        String hash = FileUtils.readFileToString(new File("bus.txt"), StandardCharsets.UTF_8);

        String[] info = SecretConstants.BUS_URL.split(";");
        for (Element link : ConnectionUtils.jsoupGetWithRetry(info[0]).select(info[1])) {
            String url = info[2] + link.attr("href");
            if (url.contains(info[3])) {
                // follow redirects, manually since there are protocol changes along the way
                for (int i = 0; i < 30; i++) {
                    HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(url);
                    log.debug("URL {} responds with code {}", url, connection.getResponseCode());
                    if (connection.getResponseCode() / 100 == 3) {
                        url = connection.getHeaderField("Location");
                    } else {
                        break;
                    }
                }

                String newHash;
                try (InputStream is = ConnectionUtils.openStreamWithTimeout(url)) {
                    newHash = DigestUtils.sha512Hex(is);
                }
                if (!hash.equals(newHash)) {
                    try (InputStream is = ConnectionUtils.openStreamWithTimeout(url)) {
                        target.sendMessage(
                                new MessageCreateBuilder()
                                        .addContent(":warning: Les horaires des bus ont changé !")
                                        .addFiles(Collections.singletonList(FileUpload.fromData(is, "Horaires_" + info[3] + ".pdf")))
                                        .build()
                        ).complete();
                    }

                    FileUtils.writeStringToFile(new File("bus.txt"), newHash, StandardCharsets.UTF_8);
                }
                break;
            }
        }
    }
}
