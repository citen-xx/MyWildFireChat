package com.example.im.route;

import com.example.im.netty.session.ImSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

@Service
public class ConnectionRouteService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRouteService.class);

    private final RouteRegistry routeRegistry;
    private final ServerProperties properties;
    private ExecutorService routeExecutor;

    public ConnectionRouteService(RouteRegistry routeRegistry, ServerProperties properties) {
        this.routeRegistry = routeRegistry;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        this.routeExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "im-route-registry");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void stop() {
        if (routeExecutor != null) {
            routeExecutor.shutdownNow();
        }
    }

    public String currentServerId() {
        return properties.getId();
    }

    public void register(ImSession session) {
        submit(() -> registerNow(session));
    }

    public void refresh(ImSession session) {
        submit(() -> refreshNow(session));
    }

    public void remove(ImSession session) {
        submit(() -> removeNow(session));
    }

    private void registerNow(ImSession session) {
        try {
            routeRegistry.register(new ConnectionRoute(
                    session.userId(),
                    session.deviceId(),
                    properties.getId(),
                    session.connectionId(),
                    session.connectedAt().toEpochMilli()));
        } catch (Exception exception) {
            log.warn("route register failed serverId={} userId={} deviceId={} connectionId={}",
                    properties.getId(), session.userId(), session.deviceId(), session.connectionId(), exception);
        }
    }

    private void refreshNow(ImSession session) {
        try {
            RouteRefreshResult result = routeRegistry.refresh(
                    session.userId(),
                    session.deviceId(),
                    session.connectionId(),
                    properties.getId());
            if (result == RouteRefreshResult.OWNERSHIP_MISMATCH) {
                log.debug("route refresh skipped by ownership mismatch serverId={} userId={} deviceId={} connectionId={}",
                        properties.getId(), session.userId(), session.deviceId(), session.connectionId());
            }
        } catch (Exception exception) {
            log.warn("route refresh failed serverId={} userId={} deviceId={} connectionId={}",
                    properties.getId(), session.userId(), session.deviceId(), session.connectionId(), exception);
        }
    }

    private void removeNow(ImSession session) {
        try {
            routeRegistry.remove(session.userId(), session.deviceId(), session.connectionId());
        } catch (Exception exception) {
            log.warn("route remove failed serverId={} userId={} deviceId={} connectionId={}",
                    properties.getId(), session.userId(), session.deviceId(), session.connectionId(), exception);
        }
    }

    private void submit(Runnable task) {
        ExecutorService executor = routeExecutor;
        if (executor == null) {
            task.run();
            return;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException exception) {
            log.warn("route task rejected serverId={} error={}", properties.getId(), exception.getMessage());
        }
    }
}
