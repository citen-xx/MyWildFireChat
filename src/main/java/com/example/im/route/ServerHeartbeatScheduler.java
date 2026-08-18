package com.example.im.route;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ServerHeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(ServerHeartbeatScheduler.class);

    private final ServerRegistry serverRegistry;
    private final ServerProperties properties;
    private final AtomicBoolean started = new AtomicBoolean();
    private ScheduledExecutorService executor;

    public ServerHeartbeatScheduler(ServerRegistry serverRegistry, ServerProperties properties) {
        this.serverRegistry = serverRegistry;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "im-server-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatSafely();
        long interval = Math.max(properties.getHeartbeatIntervalSeconds(), 1L);
        executor.scheduleWithFixedDelay(this::heartbeatSafely, interval, interval, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::cleanExpiredServersSafely, interval, interval, TimeUnit.SECONDS);
        log.info("server heartbeat started serverId={} intervalSeconds={} offlineTimeoutSeconds={}",
                properties.getId(), interval, properties.getOfflineTimeoutSeconds());
    }

    @PreDestroy
    public synchronized void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        try {
            serverRegistry.remove(properties.getId());
            log.info("server heartbeat stopped serverId={}", properties.getId());
        } catch (Exception exception) {
            log.warn("server heartbeat unregister failed serverId={}", properties.getId(), exception);
        }
    }

    private void heartbeatSafely() {
        try {
            serverRegistry.heartbeat(properties.getId(), System.currentTimeMillis());
            log.debug("server heartbeat serverId={}", properties.getId());
        } catch (Exception exception) {
            log.warn("server heartbeat failed serverId={}", properties.getId(), exception);
        }
    }

    private void cleanExpiredServersSafely() {
        try {
            long offlineTimeoutMillis = Math.max(properties.getOfflineTimeoutSeconds(), 1L) * 1000L;
            List<String> expiredServers = serverRegistry.removeExpiredServers(System.currentTimeMillis(), offlineTimeoutMillis);
            for (String serverId : expiredServers) {
                log.info("server offline serverId={}", serverId);
            }
        } catch (Exception exception) {
            log.warn("server registry cleanup failed serverId={}", properties.getId(), exception);
        }
    }
}
