package com.example.im.route;

import java.util.List;
import java.util.Optional;

public class NoopRouteRegistry implements RouteRegistry {

    @Override
    public void register(ConnectionRoute route) {
        // Redis route is disabled or unavailable in this application context.
    }

    @Override
    public RouteRefreshResult refresh(Long userId, String deviceId, String connectionId, String serverId) {
        return RouteRefreshResult.FAILED;
    }

    @Override
    public boolean remove(Long userId, String deviceId, String connectionId) {
        return false;
    }

    @Override
    public Optional<ConnectionRoute> find(Long userId, String deviceId) {
        return Optional.empty();
    }

    @Override
    public List<ConnectionRoute> findUserDevices(Long userId) {
        return List.of();
    }
}
