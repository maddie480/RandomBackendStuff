package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.daily;

import java.io.File;
import java.io.IOException;

public class ChangeBGToRandom {
    public static void run() throws IOException {
        try {
            Process p = new ProcessBuilder("/app/static/change-bg-to-random.sh")
                    .directory(new File("/backend"))
                    .inheritIO()
                    .start();

            p.waitFor();

            if (p.exitValue() != 0) {
                throw new IOException("change-bg-to-random.sh quit with exit code " + p.exitValue());
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }
}
