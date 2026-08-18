package com.example.im.route;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@ConfigurationProperties(prefix = "im.server")
public class ServerProperties {

    private String id;
    private long heartbeatIntervalSeconds = 10;
    private long offlineTimeoutSeconds = 30;
    private long routeTtlSeconds = 60;
    private volatile String generatedId;

    public String getId() {
        if (id != null && !id.isBlank()) {
            return id;
        }
        if (generatedId == null) {
            synchronized (this) {
                if (generatedId == null) {
                    generatedId = "im-server-" + UUID.randomUUID().toString().substring(0, 8);
                }
            }
        }
        return generatedId;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public void setHeartbeatIntervalSeconds(long heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    public long getOfflineTimeoutSeconds() {
        return offlineTimeoutSeconds;
    }

    public void setOfflineTimeoutSeconds(long offlineTimeoutSeconds) {
        this.offlineTimeoutSeconds = offlineTimeoutSeconds;
    }

    public long getRouteTtlSeconds() {
        return routeTtlSeconds;
    }

    public void setRouteTtlSeconds(long routeTtlSeconds) {
        this.routeTtlSeconds = routeTtlSeconds;
    }
}
