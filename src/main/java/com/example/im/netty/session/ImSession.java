package com.example.im.netty.session;

import io.netty.channel.ChannelId;

import java.time.Instant;

public record ImSession(
        Long userId,
        String deviceId,
        ChannelId channelId,
        Instant connectedAt) {

    public SessionKey key() {
        return new SessionKey(userId, deviceId);
    }
}
