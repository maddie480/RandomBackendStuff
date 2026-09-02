package ovh.maddie480.randomstuff.backend.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

public class ParallelzUtilz {
    private static final Logger logger = LoggerFactory.getLogger(ParallelzUtilz.class);

    public interface ExplodyRunnable {
        void run() throws Exception;
    }

    public static void runInParallel(List<ExplodyRunnable> tasks) throws IOException {
        final int workerThreads = 20;
        int progress = 0;
        Semaphore limiter = new Semaphore(workerThreads);
        AtomicReference<Exception> whoops = new AtomicReference<>();

        for (ExplodyRunnable task : tasks) {
            progress++;
            int current = progress;

            // wait for enough threads to be done first...
            limiter.acquireUninterruptibly();

            // launch a new thread
            new Thread(() -> {
                try {
                    logger.debug("Running task {}/{}", current, newFiles.size());
                    task.run();
                    logger.debug("Task {}/{} finished", current, newFiles.size());
                } catch (Exception e) {
                    logger.warn("Exception occurred running task {}", current);
                    whoops.set(e);
                } finally {
                    // we're done
                    limiter.release();
                }
            }).start();

            // if some thread crashed, no use in running other ones, stop now!
            if (whoops.get() != null) break;
        }

        // wait for EVERY thread to be done
        limiter.acquireUninterruptibly(workerThreads);
        // if a thread crashed, send the exception to the caller
        if (whoops.get() != null)
            throw new IOException("An exception occurred on a worker thread", whoops.get());
    }
}