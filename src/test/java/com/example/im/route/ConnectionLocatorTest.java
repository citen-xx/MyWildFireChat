package com.example.im.route;

import com.example.im.message.service.SendMessageResult;
import com.example.im.netty.session.ClientConnection;
import com.example.im.netty.session.SessionManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ConnectionLocatorTest {

    @Test
    void locateShouldReturnLocalRemoteAndOfflineTargets() {
        ServerProperties properties = properties("phase7-server-1");
        FakeRouteRegistry routeRegistry = new FakeRouteRegistry();
        SessionManager sessionManager = new SessionManager();
        ConnectionLocator locator = new ConnectionLocator(routeRegistry, sessionManager, properties);

        FakeConnection localConnection = new FakeConnection("local-conn");
        sessionManager.bind(970011L, "web", localConnection);
        routeRegistry.register(new ConnectionRoute(970011L, "web", "phase7-server-1", "local-conn", now()));
        routeRegistry.register(new ConnectionRoute(970011L, "pc", "phase7-server-2", "remote-conn", now()));

        assertThat(locator.locate(970011L, "web").type()).isEqualTo(ConnectionLocationType.LOCAL);
        assertThat(locator.locate(970011L, "pc").type()).isEqualTo(ConnectionLocationType.REMOTE);
        assertThat(locator.locate(970011L, "phone").type()).isEqualTo(ConnectionLocationType.OFFLINE);
    }

    @Test
    void routeServiceShouldNotThrowWhenRedisRepositoryFails() {
        ServerProperties properties = properties("phase7-server-1");
        ConnectionRouteService routeService = new ConnectionRouteService(new ThrowingRouteRegistry(), properties);
        SessionManager sessionManager = new SessionManager();
        sessionManager.bind(970012L, "web", new FakeConnection("broken-redis-conn"));

        assertThatCode(() -> sessionManager.findUserSessionConnections(970012L)
                .forEach(session -> {
                    routeService.register(new com.example.im.netty.session.ImSession(
                            session.key().userId(),
                            session.key().deviceId(),
                            session.connection().id(),
                            java.time.Instant.now()));
                    routeService.refresh(new com.example.im.netty.session.ImSession(
                            session.key().userId(),
                            session.key().deviceId(),
                            session.connection().id(),
                            java.time.Instant.now()));
                    routeService.remove(new com.example.im.netty.session.ImSession(
                            session.key().userId(),
                            session.key().deviceId(),
                            session.connection().id(),
                            java.time.Instant.now()));
                }))
                .doesNotThrowAnyException();
    }

    private ServerProperties properties(String serverId) {
        ServerProperties properties = new ServerProperties();
        properties.setId(serverId);
        return properties;
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private static class FakeConnection implements ClientConnection {

        private final String id;

        private FakeConnection(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void sendPush(SendMessageResult message) {
            // Not needed for route location tests.
        }

        @Override
        public void close() {
            // Not needed for route location tests.
        }

        @Override
        public boolean isActive() {
            return true;
        }
    }

    private static class FakeRouteRegistry implements RouteRegistry {

        private final List<ConnectionRoute> routes = new ArrayList<>();

        @Override
        public void register(ConnectionRoute route) {
            routes.removeIf(item -> item.userId().equals(route.userId()) && item.deviceId().equals(route.deviceId()));
            routes.add(route);
        }

        @Override
        public RouteRefreshResult refresh(Long userId, String deviceId, String connectionId, String serverId) {
            return RouteRefreshResult.REFRESHED;
        }

        @Override
        public boolean remove(Long userId, String deviceId, String connectionId) {
            return routes.removeIf(item -> item.userId().equals(userId)
                    && item.deviceId().equals(deviceId)
                    && item.connectionId().equals(connectionId));
        }

        @Override
        public Optional<ConnectionRoute> find(Long userId, String deviceId) {
            return routes.stream()
                    .filter(item -> item.userId().equals(userId) && item.deviceId().equals(deviceId))
                    .findFirst();
        }

        @Override
        public List<ConnectionRoute> findUserDevices(Long userId) {
            return routes.stream()
                    .filter(item -> item.userId().equals(userId))
                    .toList();
        }
    }

    private static class ThrowingRouteRegistry extends FakeRouteRegistry {

        @Override
        public void register(ConnectionRoute route) {
            throw new IllegalStateException("redis unavailable");
        }

        @Override
        public RouteRefreshResult refresh(Long userId, String deviceId, String connectionId, String serverId) {
            throw new IllegalStateException("redis unavailable");
        }

        @Override
        public boolean remove(Long userId, String deviceId, String connectionId) {
            throw new IllegalStateException("redis unavailable");
        }
    }
}
