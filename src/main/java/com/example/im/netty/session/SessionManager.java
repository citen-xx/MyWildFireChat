package com.example.im.netty.session;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class SessionManager {

    private static final AttributeKey<ImSession> SESSION_ATTRIBUTE =
            AttributeKey.valueOf("im.session");

    private final ConcurrentMap<SessionKey, ClientConnection> connectionsBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SessionKey> sessionsByConnection = new ConcurrentHashMap<>();
    private final ConcurrentMap<SessionKey, ImSession> sessionByKey = new ConcurrentHashMap<>();

    public synchronized ImSession bind(Long userId, String deviceId, Channel channel) {
        ImSession session = bind(userId, deviceId, new NettyClientConnection(channel));
        channel.attr(SESSION_ATTRIBUTE).set(session);
        return session;
    }

    public synchronized ImSession bind(Long userId, String deviceId, ClientConnection connection) {
        if (userId == null || deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("userId and deviceId are required");
        }

        SessionKey newKey = new SessionKey(userId, deviceId);
        ImSession session = new ImSession(userId, deviceId, connection.id(), Instant.now());

        SessionKey previousKeyForConnection = sessionsByConnection.put(connection.id(), newKey);
        if (previousKeyForConnection != null && !previousKeyForConnection.equals(newKey)) {
            connectionsBySession.remove(previousKeyForConnection, connection);
            sessionByKey.remove(previousKeyForConnection);
        }

        ClientConnection previousConnection = connectionsBySession.put(newKey, connection);
        sessionByKey.put(newKey, session);

        if (previousConnection != null && previousConnection != connection && previousConnection.isActive()) {
            sessionsByConnection.remove(previousConnection.id(), newKey);
            previousConnection.close();
        }
        return session;
    }

    public synchronized Optional<ImSession> remove(Channel channel) {
        SessionKey key = sessionsByConnection.remove(NettyClientConnection.idOf(channel));
        if (key != null) {
            ClientConnection current = connectionsBySession.get(key);
            if (current instanceof NettyClientConnection nettyConnection
                    && nettyConnection.channel().equals(channel)) {
                connectionsBySession.remove(key, current);
                ImSession removedSession = sessionByKey.remove(key);
                channel.attr(SESSION_ATTRIBUTE).set(null);
                return Optional.ofNullable(removedSession);
            }
        }
        channel.attr(SESSION_ATTRIBUTE).set(null);
        return Optional.empty();
    }

    public synchronized Optional<ImSession> remove(ClientConnection connection) {
        SessionKey key = sessionsByConnection.remove(connection.id());
        if (key != null) {
            connectionsBySession.remove(key, connection);
            return Optional.ofNullable(sessionByKey.remove(key));
        }
        return Optional.empty();
    }

    public Optional<Channel> findChannel(Long userId, String deviceId) {
        return Optional.ofNullable(connectionsBySession.get(new SessionKey(userId, deviceId)))
                .filter(NettyClientConnection.class::isInstance)
                .map(NettyClientConnection.class::cast)
                .map(NettyClientConnection::channel)
                .filter(Channel::isActive);
    }

    public List<Channel> findUserChannels(Long userId) {
        return connectionsBySession.entrySet().stream()
                .filter(entry -> entry.getKey().userId().equals(userId))
                .map(java.util.Map.Entry::getValue)
                .filter(NettyClientConnection.class::isInstance)
                .map(NettyClientConnection.class::cast)
                .map(NettyClientConnection::channel)
                .filter(Channel::isActive)
                .toList();
    }

    public List<ClientConnection> findUserConnections(Long userId) {
        return connectionsBySession.entrySet().stream()
                .filter(entry -> entry.getKey().userId().equals(userId))
                .map(java.util.Map.Entry::getValue)
                .filter(ClientConnection::isActive)
                .toList();
    }

    public List<ActiveClientSession> findUserSessionConnections(Long userId) {
        return connectionsBySession.entrySet().stream()
                .filter(entry -> entry.getKey().userId().equals(userId))
                .filter(entry -> entry.getValue().isActive())
                .map(entry -> new ActiveClientSession(entry.getKey(), entry.getValue()))
                .toList();
    }

    public Optional<ClientConnection> findConnection(Long userId, String deviceId) {
        return Optional.ofNullable(connectionsBySession.get(new SessionKey(userId, deviceId)))
                .filter(ClientConnection::isActive);
    }

    public Optional<ImSession> getSession(Channel channel) {
        return Optional.ofNullable(channel.attr(SESSION_ATTRIBUTE).get());
    }

    public boolean isAuthenticated(Channel channel) {
        return getSession(channel).isPresent();
    }

    public int onlineSessionCount() {
        return connectionsBySession.size();
    }
}
