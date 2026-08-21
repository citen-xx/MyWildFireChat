package com.example.im.message.ack;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@ConditionalOnProperty(name = "im.ack.redis-enabled", havingValue = "false")
public class InMemoryPendingAckRepository implements PendingAckRepository {

    private final ConcurrentMap<String, PendingAck> pending = new ConcurrentHashMap<>();

    @Override
    public void save(PendingAck pendingAck) {
        pending.put(key(pendingAck.userId(), pendingAck.deviceId(), pendingAck.messageId()), pendingAck);
    }

    @Override
    public void remove(Long userId, String deviceId, String messageId) {
        pending.remove(key(userId, deviceId, messageId));
    }

    @Override
    public List<PendingAck> findDue(long nowMillis, int limit) {
        return pending.values().stream()
                .filter(item -> item.nextRetryAtMillis() <= nowMillis)
                .sorted(Comparator.comparingLong(PendingAck::nextRetryAtMillis))
                .limit(limit)
                .toList();
    }

    @Override
    public List<PendingAck> findDue(long nowMillis, int limit, String ownerServerId) {
        return pending.values().stream()
                .filter(item -> item.nextRetryAtMillis() <= nowMillis)
                .filter(item -> ownerServerId == null
                        || ownerServerId.isBlank()
                        || item.ownerServerId() == null
                        || item.ownerServerId().isBlank()
                        || ownerServerId.equals(item.ownerServerId()))
                .sorted(Comparator.comparingLong(PendingAck::nextRetryAtMillis))
                .limit(limit)
                .toList();
    }

    @Override
    public boolean exists(Long userId, String deviceId, String messageId) {
        return pending.containsKey(key(userId, deviceId, messageId));
    }

    @Override
    public void removeIfConnection(Long userId, String deviceId, String messageId, String connectionId) {
        String key = key(userId, deviceId, messageId);
        pending.computeIfPresent(key, (ignored, current) -> {
            if (connectionId == null || connectionId.isBlank()
                    || connectionId.equals(current.connectionId())) {
                return null;
            }
            return current;
        });
    }

    @Override
    public long count() {
        return pending.size();
    }

    private String key(Long userId, String deviceId, String messageId) {
        return userId + "|" + deviceId + "|" + messageId;
    }
}
