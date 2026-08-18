package com.example.im.route;

import java.util.List;

public interface ServerRegistry {

    void heartbeat(String serverId, long timestampMillis);

    void remove(String serverId);

    List<String> findActiveServers(long nowMillis, long offlineTimeoutMillis);

    List<String> removeExpiredServers(long nowMillis, long offlineTimeoutMillis);
}
