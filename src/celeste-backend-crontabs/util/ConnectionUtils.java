package com.max480.discord.randombots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

public final class ConnectionUtils {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionUtils.class);

    /**
     * Much like {@link java.util.function.Supplier} but throwing an IOException (which a network operation may do).
     *
     * @param <T> The return type for the operation
     */
    public interface NetworkingOperation<T> {
        T run() throws IOException;
    }

    /**
     * Opens a stream to the specified URL, getting sure timeouts are set
     * (connect timeout = 10 seconds, read timeout = 30 seconds).
     *
     * @param url The URL to connect to
     * @return A stream to this URL
     * @throws IOException If an exception occured while trying to connect
     */
    public static InputStream openStreamWithTimeout(URL url) throws IOException {
        URLConnection con = url.openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(30000);
        return con.getInputStream();
    }

    /**
     * Runs a task (typically a network operation), retrying up to 3 times if it throws an IOException.
     *
     * @param task The task to run and retry
     * @param <T>  The return type for the task
     * @return What the task returned
     * @throws IOException If the task failed 3 times
     */
    public static <T> T runWithRetry(NetworkingOperation<T> task) throws IOException {
        for (int i = 1; i < 3; i++) {
            try {
                return task.run();
            } catch (IOException e) {
                logger.warn("I/O exception while doing networking operation (try {}/3).", i, e);

                // wait a bit before retrying
                try {
                    logger.debug("Waiting {} seconds before next try.", i * 5);
                    Thread.sleep(i * 5000);
                } catch (InterruptedException e2) {
                    logger.warn("Sleep interrupted", e2);
                }
            }
        }

        // 3rd try: this time, if it crashes, let it crash
        return task.run();
    }
}
