package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.daily;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.utils.OutputStreamLogger;

import java.io.File;
import java.io.IOException;

public class PurgePosts {
    private static final Logger log = LoggerFactory.getLogger(PurgePosts.class);

    public static void run() throws IOException {
        try {
            Process p = OutputStreamLogger.redirectAllOutput(log,
                    new ProcessBuilder("/app/static/purge-posts.sh")
                            .directory(new File("/backend"))
                            .start());

            p.waitFor();

            if (p.exitValue() != 0) {
                throw new IOException("purge-posts.sh quit with exit code " + p.exitValue());
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }
}
