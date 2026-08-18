package com.example.im.route;

import java.util.List;
import java.util.Optional;

public interface RouteRegistry {

    void register(ConnectionRoute route);

    RouteRefreshResult refresh(Long userId, String deviceId, String connectionId, String serverId);

    boolean remove(Long userId, String deviceId, String connectionId);

    Optional<ConnectionRoute> find(Long userId, String deviceId);

    List<ConnectionRoute> findUserDevices(Long userId);
}
