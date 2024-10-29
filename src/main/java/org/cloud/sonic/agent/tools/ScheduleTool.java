package org.cloud.sonic.agent.tools;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author JayWenStar
 * @date 2022/4/25 11:21 上午
 */
public class ScheduleTool {

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors() << 1
    );

    public static void scheduleAtFixedRate(Runnable command,
                                           long initialDelay,
                                           long period,
                                           TimeUnit unit) {
        scheduler.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    public static ScheduledFuture<?> schedule(Runnable command, long initialDelay) {
        return scheduler.schedule(command, initialDelay, TimeUnit.MINUTES);
    }

    public static void shutdownScheduler() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
