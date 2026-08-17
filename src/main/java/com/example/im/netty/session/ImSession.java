package com.example.im.netty.session;

import java.time.Instant;

public record ImSession(
        Long userId,
        String deviceId,
        String connectionId,
        Instant connectedAt) {

    public SessionKey key() {
        return new SessionKey(userId, deviceId);
    }
}
