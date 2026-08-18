package com.example.im.route;

public record ConnectionRoute(
        Long userId,
        String deviceId,
        String serverId,
        String connectionId,
        long connectedAtMillis) {
}
