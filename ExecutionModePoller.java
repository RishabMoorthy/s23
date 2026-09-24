package org.framework.db.service;

import org.common.db.config.ConfigLoader;
import org.framework.config.ExecutionModeType;
import org.framework.core.AbstractService;
import org.framework.core.ServerManager;
import org.framework.utils.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Picks up execution-mode changes made in the portal, by re-reading the database on a timer.
 *
 * <p>The portal backend cannot call this server directly. StubServerAuthBE and the hibernate
 * module are shared with the live StubServer_v1, so adding a "tell the core to refresh" call
 * there would fire against v1 - which has no such endpoint - for as long as v1 is the live core.
 * Polling keeps the whole mechanism inside StubServer_v2, so it works today, changes nothing for
 * v1, and needs no edit when v2 goes live.
 *
 * <p>Each tick re-runs the ordinary sync for every deployed service. That sync compares values
 * before writing anything, so a tick with nothing to do costs one read per service and touches
 * neither memory nor the .vs file.
 *
 * <p>Change detection compares the mode and host values, never UPDATETIME: the column is mapped
 * {@code @Temporal(TemporalType.DATE)} and is a DATE in the Oracle schema, so it has day
 * precision and cannot distinguish two changes made on the same day.
 *
 * <p>Interval comes from {@code executionmode.refresh.intervalSeconds} in config.properties.
 * Zero, blank or negative disables polling entirely, leaving the mode to be applied at deploy
 * and restart only.
 */
public final class ExecutionModePoller {

    private static final String INTERVAL_KEY = "executionmode.refresh.intervalSeconds";
    private static final int DEFAULT_INTERVAL_SECONDS = 30;

    private static ScheduledExecutorService scheduler;

    /**
     * The last failure reported per service, so a fault that persists is logged once rather
     * than on every pass. Cleared when that service next polls cleanly.
     */
    private static final Map<String, String> LAST_FAILURE = new ConcurrentHashMap<>();

    private ExecutionModePoller() {
    }

    /** Starts the timer, unless it is disabled by configuration or already running. */
    public static synchronized void start() {

        if (scheduler != null) {
            return;
        }

        int interval = intervalSeconds();

        if (interval <= 0) {
            Logger.getInstance().info("[ExecutionMode] Polling disabled ("
                    + INTERVAL_KEY + "=" + interval + "). The mode will be applied at deploy"
                    + " and restart only, or on demand via /sbackend/refreshExecutionMode.");
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ExecutionMode-Poller");
            thread.setDaemon(true);
            return thread;
        });

        // The first tick is delayed by one interval: start-up has just synced every service.
        scheduler.scheduleAtFixedRate(
                ExecutionModePoller::tick, interval, interval, TimeUnit.SECONDS);

        Logger.getInstance().info("[ExecutionMode] Polling the database every "
                + interval + "s for portal changes.");
    }

    public static synchronized void stop() {

        if (scheduler == null) {
            return;
        }

        scheduler.shutdownNow();
        scheduler = null;
        Logger.getInstance().info("[ExecutionMode] Polling stopped.");
    }

    /**
     * One pass over the deployed services.
     *
     * <p>Nothing is allowed to escape this method: an exception thrown from a task scheduled
     * with scheduleAtFixedRate cancels all future runs, which would silently stop the feature.
     */
    private static void tick() {

        try {
            Map<String, AbstractService> services = ServerManager.getInstance().getServices();

            if (services.isEmpty()) {
                return;
            }

            ExecutionDetailsService sync = new ExecutionDetailsService();

            for (AbstractService service : services.values()) {
                try {
                    // A service with no execution mode has nothing to poll for, and saying so
                    // every 30 seconds would bury the log. It is skipped here rather than
                    // inside the sync, so no work is done and nothing is written.
                    if (service.getConfig() != null
                            && service.getConfig().getExecutionModeType() != ExecutionModeType.NONE) {
                        sync.syncExecutionMode(service.getConfig(), true);
                        LAST_FAILURE.remove(service.getName());
                    }
                } catch (ExecutionModeSyncException e) {
                    // A misconfiguration fails a deploy, but it must not stop the timer or the
                    // other services from being checked.
                    reportOnce(service.getName(), "[ExecutionMode] Poll skipped for '"
                            + service.getName() + "': " + e.getMessage());
                } catch (Exception e) {
                    reportOnce(service.getName(), "[ExecutionMode] Poll failed for '"
                            + service.getName() + "': " + e.getMessage());
                }
            }

        } catch (Throwable t) {
            reportOnce("*", "[ExecutionMode] Poll pass failed: " + t.getMessage());
        }
    }

    /** Logs a failure only when it differs from the last one seen for that service. */
    private static void reportOnce(String serviceName, String message) {
        if (!message.equals(LAST_FAILURE.put(serviceName, message))) {
            Logger.getInstance().info(message);
        }
    }

    private static int intervalSeconds() {
        try {
            String raw = ConfigLoader.getProperty(INTERVAL_KEY);
            if (raw != null && !raw.isBlank()) {
                return Integer.parseInt(raw.trim());
            }
        } catch (Exception ignored) {
            // fall through to the default
        }
        return DEFAULT_INTERVAL_SECONDS;
    }
}
