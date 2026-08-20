package com.example.im.route;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RedisRouteRegistryIntegrationTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RedisRouteRegistry routeRegistry;
    private RedisServerRegistry serverRegistry;
    private ServerProperties serverProperties;

    @BeforeAll
    static void startRedisClient() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("localhost", 6380);
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedisClient() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        serverProperties = new ServerProperties();
        serverProperties.setId("phase7-server-1");
        serverProperties.setRouteTtlSeconds(2);
        routeRegistry = new RedisRouteRegistry(redisTemplate, serverProperties);
        serverRegistry = new RedisServerRegistry(redisTemplate);
        cleanKeys();
    }

    @AfterEach
    void tearDown() {
        cleanKeys();
    }

    @Test
    void routeLifecycleShouldProtectNewRouteFromOldConnectionRemoveAndRefresh() {
        ConnectionRoute oldRoute = new ConnectionRoute(970001L, "web", "phase7-server-1", "conn-old", now());
        ConnectionRoute newRoute = new ConnectionRoute(970001L, "web", "phase7-server-2", "conn-new", now());

        routeRegistry.register(oldRoute);
        routeRegistry.register(newRoute);

        assertThat(routeRegistry.find(970001L, "web"))
                .get()
                .extracting(ConnectionRoute::connectionId, ConnectionRoute::serverId)
                .containsExactly("conn-new", "phase7-server-2");
        assertThat(routeRegistry.remove(970001L, "web", "conn-old")).isFalse();
        assertThat(routeRegistry.refresh(970001L, "web", "conn-old", "phase7-server-1"))
                .isEqualTo(RouteRefreshResult.OWNERSHIP_MISMATCH);

        assertThat(routeRegistry.find(970001L, "web"))
                .get()
                .extracting(ConnectionRoute::connectionId, ConnectionRoute::serverId)
                .containsExactly("conn-new", "phase7-server-2");
        assertThat(routeRegistry.remove(970001L, "web", "conn-new")).isTrue();
        assertThat(routeRegistry.find(970001L, "web")).isEmpty();
    }

    @Test
    void refreshShouldReRegisterMissingRouteForActiveConnection() {
        RouteRefreshResult result = routeRegistry.refresh(970002L, "pc", "conn-pc", "phase7-server-1");

        assertThat(result).isEqualTo(RouteRefreshResult.REGISTERED);
        assertThat(routeRegistry.find(970002L, "pc"))
                .get()
                .extracting(ConnectionRoute::connectionId, ConnectionRoute::serverId)
                .containsExactly("conn-pc", "phase7-server-1");
    }

    @Test
    void routeShouldExpireByTtl() {
        routeRegistry.register(new ConnectionRoute(970003L, "phone", "phase7-server-1", "conn-phone", now()));

        assertThat(routeRegistry.find(970003L, "phone")).isPresent();
        await().atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> assertThat(routeRegistry.find(970003L, "phone")).isEmpty());
    }

    @Test
    void findUserDevicesShouldReturnRoutesAcrossServers() {
        routeRegistry.register(new ConnectionRoute(970004L, "web", "phase7-server-1", "conn-web", now()));
        routeRegistry.register(new ConnectionRoute(970004L, "pc", "phase7-server-2", "conn-pc", now()));

        List<ConnectionRoute> routes = routeRegistry.findUserDevices(970004L);

        assertThat(routes)
                .extracting(ConnectionRoute::deviceId)
                .containsExactly("pc", "web");
        assertThat(routes)
                .extracting(ConnectionRoute::serverId)
                .containsExactly("phase7-server-2", "phase7-server-1");
    }

    @Test
    void serverRegistryShouldHeartbeatAndCleanExpiredServers() {
        long now = now();
        serverRegistry.heartbeat("phase7-server-1", now);
        serverRegistry.heartbeat("phase7-server-2", now);
        serverRegistry.heartbeat("phase7-expired-server", now - 5_000L);

        assertThat(serverRegistry.findActiveServers(now, 1_000L))
                .containsExactlyInAnyOrder("phase7-server-1", "phase7-server-2");
        assertThat(serverRegistry.removeExpiredServers(now, 1_000L))
                .contains("phase7-expired-server");
        assertThat(serverRegistry.findActiveServers(now, 1_000L))
                .containsExactlyInAnyOrder("phase7-server-1", "phase7-server-2");
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private void cleanKeys() {
        redisTemplate.delete(List.of(
                "im:route:970001:web",
                "im:route:970002:pc",
                "im:route:970003:phone",
                "im:route:970004:web",
                "im:route:970004:pc",
                "im:user:devices:970001",
                "im:user:devices:970002",
                "im:user:devices:970003",
                "im:user:devices:970004"));
        redisTemplate.opsForZSet().remove(
                RedisServerRegistry.REGISTRY_KEY,
                "phase7-connect-server-1",
                "phase7-server-1",
                "phase7-server-2",
                "phase7-expired-server");
    }
}
