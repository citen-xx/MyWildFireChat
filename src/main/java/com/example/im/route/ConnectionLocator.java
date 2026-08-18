package com.example.im.route;

import com.example.im.netty.session.ActiveClientSession;
import com.example.im.netty.session.ClientConnection;
import com.example.im.netty.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ConnectionLocator {

    private static final Logger log = LoggerFactory.getLogger(ConnectionLocator.class);

    private final RouteRegistry routeRegistry;
    private final SessionManager sessionManager;
    private final ServerProperties properties;

    public ConnectionLocator(RouteRegistry routeRegistry, SessionManager sessionManager, ServerProperties properties) {
        this.routeRegistry = routeRegistry;
        this.sessionManager = sessionManager;
        this.properties = properties;
    }

    public ConnectionLocation locate(Long userId, String deviceId) {
        Optional<ConnectionRoute> route = routeRegistry.find(userId, deviceId);
        Optional<ClientConnection> localConnection = sessionManager.findConnection(userId, deviceId);
        if (route.isEmpty()) {
            return localConnection
                    .map(connection -> ConnectionLocation.local(localRoute(userId, deviceId, connection), connection))
                    .orElseGet(ConnectionLocation::offline);
        }

        ConnectionRoute currentRoute = route.get();
        if (!properties.getId().equals(currentRoute.serverId())) {
            return ConnectionLocation.remote(currentRoute);
        }

        if (localConnection.isPresent()) {
            ClientConnection connection = localConnection.get();
            if (!connection.id().equals(currentRoute.connectionId())) {
                routeRegistry.remove(userId, deviceId, currentRoute.connectionId());
                log.info("route stale serverId={} userId={} deviceId={} connectionId={}",
                        properties.getId(), userId, deviceId, currentRoute.connectionId());
            }
            return ConnectionLocation.local(localRoute(userId, deviceId, connection), connection);
        }

        routeRegistry.remove(userId, deviceId, currentRoute.connectionId());
        log.info("route stale serverId={} userId={} deviceId={} connectionId={}",
                properties.getId(), userId, deviceId, currentRoute.connectionId());
        return ConnectionLocation.offline();
    }

    public List<ConnectionLocation> locateUserDevices(Long userId) {
        Map<String, ConnectionLocation> locations = new LinkedHashMap<>();
        for (ConnectionRoute route : routeRegistry.findUserDevices(userId)) {
            locations.put(route.deviceId(), locate(userId, route.deviceId()));
        }
        for (ActiveClientSession localSession : sessionManager.findUserSessionConnections(userId)) {
            locations.putIfAbsent(localSession.key().deviceId(), ConnectionLocation.local(
                    localRoute(userId, localSession.key().deviceId(), localSession.connection()),
                    localSession.connection()));
        }
        return new ArrayList<>(locations.values());
    }

    private ConnectionRoute localRoute(Long userId, String deviceId, ClientConnection connection) {
        return new ConnectionRoute(
                userId,
                deviceId,
                properties.getId(),
                connection.id(),
                Instant.now().toEpochMilli());
    }
}
