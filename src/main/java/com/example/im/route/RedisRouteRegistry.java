package com.example.im.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class RedisRouteRegistry implements RouteRegistry {

    private static final Logger log = LoggerFactory.getLogger(RedisRouteRegistry.class);

    private final StringRedisTemplate redisTemplate;
    private final ServerProperties properties;
    private final DefaultRedisScript<Long> removeIfOwnerScript;
    private final DefaultRedisScript<Long> refreshIfOwnerScript;

    public RedisRouteRegistry(StringRedisTemplate redisTemplate, ServerProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.removeIfOwnerScript = script("scripts/route_remove_if_owner.lua");
        this.refreshIfOwnerScript = script("scripts/route_refresh_if_owner.lua");
    }

    @Override
    public void register(ConnectionRoute route) {
        validate(route.userId(), route.deviceId(), route.connectionId());
        String routeKey = routeKey(route.userId(), route.deviceId());
        Map<String, String> values = new HashMap<>();
        values.put("userId", route.userId().toString());
        values.put("deviceId", route.deviceId());
        values.put("serverId", route.serverId());
        values.put("connectionId", route.connectionId());
        values.put("connectedAt", Long.toString(route.connectedAtMillis()));
        redisTemplate.opsForHash().putAll(routeKey, values);
        redisTemplate.expire(routeKey, routeTtl());
        redisTemplate.opsForSet().add(userDevicesKey(route.userId()), route.deviceId());
        redisTemplate.expire(userDevicesKey(route.userId()), routeTtl());
        log.info("route registered serverId={} userId={} deviceId={} connectionId={}",
                route.serverId(), route.userId(), route.deviceId(), route.connectionId());
    }

    @Override
    public RouteRefreshResult refresh(Long userId, String deviceId, String connectionId, String serverId) {
        validate(userId, deviceId, connectionId);
        Long result = redisTemplate.execute(
                refreshIfOwnerScript,
                List.of(routeKey(userId, deviceId), userDevicesKey(userId)),
                connectionId,
                Long.toString(Math.max(properties.getRouteTtlSeconds(), 1L)),
                deviceId,
                serverId,
                Long.toString(System.currentTimeMillis()),
                userId.toString());
        if (result == null) {
            return RouteRefreshResult.FAILED;
        }
        if (result == 1L) {
            log.debug("route refreshed serverId={} userId={} deviceId={} connectionId={}",
                    serverId, userId, deviceId, connectionId);
            return RouteRefreshResult.REFRESHED;
        }
        if (result == 2L) {
            log.info("route re-registered serverId={} userId={} deviceId={} connectionId={}",
                    serverId, userId, deviceId, connectionId);
            return RouteRefreshResult.REGISTERED;
        }
        return RouteRefreshResult.OWNERSHIP_MISMATCH;
    }

    @Override
    public boolean remove(Long userId, String deviceId, String connectionId) {
        validate(userId, deviceId, connectionId);
        Long removed = redisTemplate.execute(
                removeIfOwnerScript,
                List.of(routeKey(userId, deviceId), userDevicesKey(userId)),
                connectionId,
                deviceId);
        boolean success = removed != null && removed == 1L;
        if (success) {
            log.info("route removed userId={} deviceId={} connectionId={}",
                    userId, deviceId, connectionId);
        }
        return success;
    }

    @Override
    public Optional<ConnectionRoute> find(Long userId, String deviceId) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(routeKey(userId, deviceId));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ConnectionRoute(
                    Long.valueOf(value(values, "userId")),
                    value(values, "deviceId"),
                    value(values, "serverId"),
                    value(values, "connectionId"),
                    Long.parseLong(value(values, "connectedAt"))));
        } catch (RuntimeException exception) {
            log.warn("route value is invalid userId={} deviceId={}", userId, deviceId, exception);
            return Optional.empty();
        }
    }

    @Override
    public List<ConnectionRoute> findUserDevices(Long userId) {
        Set<String> deviceIds = redisTemplate.opsForSet().members(userDevicesKey(userId));
        if (deviceIds == null || deviceIds.isEmpty()) {
            return List.of();
        }
        return deviceIds.stream()
                .map(deviceId -> find(userId, deviceId)
                        .orElseGet(() -> {
                            redisTemplate.opsForSet().remove(userDevicesKey(userId), deviceId);
                            return null;
                        }))
                .filter(route -> route != null)
                .sorted(Comparator.comparing(ConnectionRoute::deviceId))
                .toList();
    }

    private Duration routeTtl() {
        return Duration.ofSeconds(Math.max(properties.getRouteTtlSeconds(), 1L));
    }

    private String routeKey(Long userId, String deviceId) {
        return "im:route:" + userId + ":" + deviceId;
    }

    private String userDevicesKey(Long userId) {
        return "im:user:devices:" + userId;
    }

    private String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("route field is missing: " + key);
        }
        return value.toString();
    }

    private void validate(Long userId, String deviceId, String connectionId) {
        if (userId == null || userId <= 0 || deviceId == null || deviceId.isBlank()
                || connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("userId, deviceId and connectionId are required");
        }
    }

    private DefaultRedisScript<Long> script(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }
}
