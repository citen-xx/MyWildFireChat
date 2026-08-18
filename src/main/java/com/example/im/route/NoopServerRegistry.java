package com.example.im.route;

import java.util.List;

public class NoopServerRegistry implements ServerRegistry {

    @Override
    public void heartbeat(String serverId, long timestampMillis) {
        // Redis server registry is disabled or unavailable in this application context.
    }

    @Override
    public void remove(String serverId) {
        // Redis server registry is disabled or unavailable in this application context.
    }

    @Override
    public List<String> findActiveServers(long nowMillis, long offlineTimeoutMillis) {
        return List.of();
    }

    @Override
    public List<String> removeExpiredServers(long nowMillis, long offlineTimeoutMillis) {
        return List.of();
    }
}
