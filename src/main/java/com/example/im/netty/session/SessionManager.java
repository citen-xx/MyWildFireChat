package com.example.im.netty.session;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.util.AttributeKey;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class SessionManager {

    private static final AttributeKey<ImSession> SESSION_ATTRIBUTE =
            AttributeKey.valueOf("im.session");

    private final ConcurrentMap<SessionKey, Channel> channelsBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<ChannelId, SessionKey> sessionsByChannel = new ConcurrentHashMap<>();

    public synchronized ImSession bind(Long userId, String deviceId, Channel channel) {
        if (userId == null || deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("userId and deviceId are required");
        }

        SessionKey newKey = new SessionKey(userId, deviceId);
        ImSession session = new ImSession(userId, deviceId, channel.id(), Instant.now());

        SessionKey previousKeyForChannel = sessionsByChannel.put(channel.id(), newKey);
        if (previousKeyForChannel != null && !previousKeyForChannel.equals(newKey)) {
            channelsBySession.remove(previousKeyForChannel, channel);
        }

        Channel previousChannel = channelsBySession.put(newKey, channel);
        channel.attr(SESSION_ATTRIBUTE).set(session);

        if (previousChannel != null && previousChannel != channel && previousChannel.isOpen()) {
            previousChannel.close();
        }
        return session;
    }

    public synchronized void remove(Channel channel) {
        SessionKey key = sessionsByChannel.remove(channel.id());
        if (key != null) {
            channelsBySession.remove(key, channel);
        }
        channel.attr(SESSION_ATTRIBUTE).set(null);
    }

    public Optional<Channel> findChannel(Long userId, String deviceId) {
        return Optional.ofNullable(channelsBySession.get(new SessionKey(userId, deviceId)))
                .filter(Channel::isActive);
    }

    public Optional<ImSession> getSession(Channel channel) {
        return Optional.ofNullable(channel.attr(SESSION_ATTRIBUTE).get());
    }

    public boolean isAuthenticated(Channel channel) {
        return getSession(channel).isPresent();
    }

    public int onlineSessionCount() {
        return channelsBySession.size();
    }
}
