package org.isnsest.dauth;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

public class LogoutTimerManager {

    private static final Map<UUID, String> sessionMap = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final ConcurrentHashMap<UUID, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    public static void updateSession(UUID playerId, String ip) {
        sessionMap.put(playerId, ip);
    }

    public static boolean checkSession(UUID playerId, String ip) {
        String storedIp = sessionMap.get(playerId);
        return storedIp != null && storedIp.equals(ip);
    }

    public static void removeSession(UUID playerId) {
        sessionMap.remove(playerId);
    }

    public static void startTimer(UUID playerId, Runnable onExpire, long delaySeconds) {
        cancelTimer(playerId);

        ScheduledFuture<?> scheduled = scheduler.schedule(() -> {
            ScheduledFuture<?> removed = timers.remove(playerId);

            if (removed != null) {
                onExpire.run();
            }
        }, delaySeconds, TimeUnit.SECONDS);

        timers.put(playerId, scheduled);
    }

    public static void cancelTimer(UUID playerId) {
        ScheduledFuture<?> scheduled = timers.remove(playerId);
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }
}