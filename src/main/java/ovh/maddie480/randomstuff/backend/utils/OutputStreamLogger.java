package ovh.maddie480.randomstuff.backend.utils;

import org.apache.poi.util.IOUtils;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * A utility class for redirecting an output stream to a logger.
 */
public class OutputStreamLogger extends OutputStream {
    private final Consumer<String> logger;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    public OutputStreamLogger(Consumer<String> logger) {
        this.logger = logger;
    }

    @Override
    public void write(int b) throws IOException {
        if (b == '\r' || b == '\n') {
            flushBytes();
        } else {
            bytes.write(b);
        }
    }

    @Override
    public void close() {
        flushBytes();
    }

    private void flushBytes() {
        if (bytes.size() == 0) return;
        logger.accept(bytes.toString(StandardCharsets.UTF_8));
        bytes.reset();
    }


    public static Process redirectAllOutput(Logger logger, Process p) {
        runOutputStreamThread(logger, logger::info, p.getInputStream());
        runOutputStreamThread(logger, logger::error, p.getErrorStream());
        return p;
    }

    public static Process redirectErrorOutput(Logger logger, Process p) {
        runOutputStreamThread(logger, logger::error, p.getErrorStream());
        return p;
    }

    private static void runOutputStreamThread(Logger logger, Consumer<String> log, InputStream stream) {
        new Thread("Output Stream Logger") {
            @Override
            public void run() {
                try (OutputStreamLogger os = new OutputStreamLogger(log)) {
                    IOUtils.copy(stream, os);
                } catch (IOException e) {
                    logger.error("Stopped tracking output of process", e);
                }
            }
        }.start();
    }
}
