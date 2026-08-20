package com.example.im.message.ack;

import java.util.List;

public interface PendingAckRepository {

    void save(PendingAck pendingAck);

    void remove(Long userId, String deviceId, String messageId);

    List<PendingAck> findDue(long nowMillis, int limit);

    default List<PendingAck> findDue(long nowMillis, int limit, String ownerServerId) {
        return findDue(nowMillis, limit).stream()
                .filter(item -> ownerServerId == null
                        || ownerServerId.isBlank()
                        || item.ownerServerId() == null
                        || item.ownerServerId().isBlank()
                        || ownerServerId.equals(item.ownerServerId()))
                .toList();
    }

    boolean exists(Long userId, String deviceId, String messageId);

    default void removeIfConnection(
            Long userId,
            String deviceId,
            String messageId,
            String connectionId) {
        remove(userId, deviceId, messageId);
    }

    long count();
}
