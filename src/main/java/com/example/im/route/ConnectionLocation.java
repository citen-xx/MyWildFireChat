package com.example.im.route;

import com.example.im.netty.session.ClientConnection;

public record ConnectionLocation(
        ConnectionLocationType type,
        ConnectionRoute route,
        ClientConnection connection) {

    public static ConnectionLocation local(ConnectionRoute route, ClientConnection connection) {
        return new ConnectionLocation(ConnectionLocationType.LOCAL, route, connection);
    }

    public static ConnectionLocation remote(ConnectionRoute route) {
        return new ConnectionLocation(ConnectionLocationType.REMOTE, route, null);
    }

    public static ConnectionLocation offline() {
        return new ConnectionLocation(ConnectionLocationType.OFFLINE, null, null);
    }
}
