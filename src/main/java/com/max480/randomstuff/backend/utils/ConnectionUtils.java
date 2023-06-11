package com.max480.randomstuff.backend.utils;

import net.dv8tion.jda.api.requests.RestAction;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.function.IOSupplier;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

public final class ConnectionUtils {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionUtils.class);

    /**
     * Creates an HttpURLConnection to the specified URL, getting sure timeouts are set
     * (connect timeout = 10 seconds, read timeout = 30 seconds).
     *
     * @param url The URL to connect to
     * @return An HttpURLConnection to this URL
     * @throws IOException If an exception occured while trying to connect
     */
    public static HttpURLConnection openConnectionWithTimeout(String url) throws IOException {
        URLConnection con;

        try {
            con = new URI(url).toURL().openConnection();
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }

        con.setRequestProperty("User-Agent", "Maddie-Random-Stuff-Backend/1.0.0 (+https://github.com/maddie480/RandomBackendStuff)");
        con.setRequestProperty("Accept-Encoding", "gzip");

        con.setConnectTimeout(10000);
        con.setReadTimeout(30000);

        return (HttpURLConnection) con;
    }

    /**
     * Turns an HTTP connection into an input stream, going through gzip decoding if necessary.
     *
     * @param con The connection
     * @return An input stream that reads from the connection
     * @throws IOException If an exception occured while trying to connect
     */
    public static InputStream connectionToInputStream(HttpURLConnection con) throws IOException {
        InputStream is = con.getInputStream();
        if ("gzip".equals(con.getContentEncoding())) {
            return new GZIPInputStream(is);
        }
        return is;
    }

    /**
     * Creates a stream to the specified URL, getting sure timeouts are set
     * (connect timeout = 10 seconds, read timeout = 30 seconds).
     *
     * @param url The URL to connect to
     * @return A stream to this URL
     * @throws IOException If an exception occured while trying to connect
     */
    public static InputStream openStreamWithTimeout(String url) throws IOException {
        return connectionToInputStream(openConnectionWithTimeout(url));
    }


    /**
     * Opens a connection to the given URL with a timeout, and returns all its contents as a string.
     *
     * @param url     The URL to read
     * @param charset The charset to use to decode the contents
     * @return The entire contents of the URL as a string
     * @throws IOException If an exception occurred while reading the contents
     */
    public static String toStringWithTimeout(String url, Charset charset) throws IOException {
        return IOUtils.toString(openStreamWithTimeout(url), charset);
    }

    /**
     * Opens a connection to the given URL with a timeout, and returns all its contents as a byte array.
     *
     * @param url The URL to read
     * @return The entire contents of the URL as a byte array
     * @throws IOException If an exception occurred while reading the contents
     */
    public static byte[] toByteArrayWithTimeout(String url) throws IOException {
        return IOUtils.toByteArray(openStreamWithTimeout(url));
    }

    /**
     * Runs a task (typically a network operation), retrying up to 3 times if it throws an IOException.
     *
     * @param task The task to run and retry
     * @param <T>  The return type for the task
     * @return What the task returned
     * @throws IOException If the task failed 3 times
     */
    public static <T> T runWithRetry(IOSupplier<T> task) throws IOException {
        for (int i = 1; i < 3; i++) {
            try {
                return task.get();
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
        return task.get();
    }

    /**
     * Retrieves an HTML page with Jsoup, including retries if the request fails.
     *
     * @param url The URL to get
     * @return The HTML Document obtained from the URL
     * @throws IOException If an exception occurred three times while reading the contents
     */
    public static Document jsoupGetWithRetry(String url) throws IOException {
        return runWithRetry(() -> Jsoup.connect(url)
                .userAgent("Maddie-Random-Stuff-Backend/1.0.0 (+https://github.com/maddie480/RandomBackendStuff)")
                .get());
    }

    private static class ResultHolder<T> {
        public T result;
    }

    public static <T> T completeWithTimeout(Supplier<RestAction<T>> actionProvider) throws IOException {
        return runWithRetry(() -> {
            final ResultHolder<T> resultHolder = new ResultHolder<>();
            final ResultHolder<Throwable> failureHolder = new ResultHolder<>();
            Semaphore mutex = new Semaphore(0);

            actionProvider.get().queue(
                    result -> {
                        resultHolder.result = result;
                        mutex.release();
                    },
                    failure -> {
                        failureHolder.result = failure;
                        mutex.release();
                    });

            try {
                if (!mutex.tryAcquire(30, TimeUnit.SECONDS)) {
                    throw new IOException("RestAction didn't finish in time!");
                }
                if (failureHolder.result != null) {
                    if (failureHolder.result instanceof IOException ioException) {
                        throw ioException;
                    } else {
                        throw new RuntimeException(failureHolder.result);
                    }
                }
                return resultHolder.result;
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        });
    }
}