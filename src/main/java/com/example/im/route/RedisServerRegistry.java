package com.example.im.route;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Set;

public class RedisServerRegistry implements ServerRegistry {

    public static final String REGISTRY_KEY = "im:server:registry";

    private final StringRedisTemplate redisTemplate;

    public RedisServerRegistry(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void heartbeat(String serverId, long timestampMillis) {
        try {
            redisTemplate.opsForZSet().add(REGISTRY_KEY, serverId, timestampMillis);
        } catch (IllegalStateException exception) {
            return;
        }
    }

    @Override
    public void remove(String serverId) {
        try {
            redisTemplate.opsForZSet().remove(REGISTRY_KEY, serverId);
        } catch (IllegalStateException exception) {
            return;
        }
    }

    @Override
    public List<String> findActiveServers(long nowMillis, long offlineTimeoutMillis) {
        try {
            Set<String> servers = redisTemplate.opsForZSet().rangeByScore(
                    REGISTRY_KEY,
                    Math.max(nowMillis - offlineTimeoutMillis, 0L),
                    Double.POSITIVE_INFINITY);
            return servers == null ? List.of() : List.copyOf(servers);
        } catch (IllegalStateException exception) {
            return List.of();
        }
    }

    @Override
    public List<String> removeExpiredServers(long nowMillis, long offlineTimeoutMillis) {
        try {
            Set<String> expiredServers = redisTemplate.opsForZSet().rangeByScore(
                    REGISTRY_KEY,
                    0,
                    Math.max(nowMillis - offlineTimeoutMillis, 0L));
            if (expiredServers == null || expiredServers.isEmpty()) {
                return List.of();
            }
            redisTemplate.opsForZSet().remove(REGISTRY_KEY, expiredServers.toArray());
            return List.copyOf(expiredServers);
        } catch (IllegalStateException exception) {
            return List.of();
        }
    }
}
