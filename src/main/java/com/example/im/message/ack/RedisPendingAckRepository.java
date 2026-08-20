package com.example.im.message.ack;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

@Repository
@ConditionalOnProperty(name = "im.ack.redis-enabled", havingValue = "true", matchIfMissing = true)
public class RedisPendingAckRepository implements PendingAckRepository {

    private static final String INDEX_KEY = "im:pending_ack:index";
    private static final String OWNER_INDEX_PREFIX = "im:pending_ack:owner:";
    private static final String DELIMITER = "|";

    private final StringRedisTemplate redisTemplate;

    public RedisPendingAckRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(PendingAck pendingAck) {
        String deviceKey = deviceKey(pendingAck.userId(), pendingAck.deviceId());
        String attemptKey = attemptKey(pendingAck.userId(), pendingAck.deviceId());
        String metadataKey = metadataKey(pendingAck.userId(), pendingAck.deviceId());
        String member = member(pendingAck.userId(), pendingAck.deviceId(), pendingAck.messageId());
        PendingAck existing = loadPendingAck(
                pendingAck.userId(),
                pendingAck.deviceId(),
                pendingAck.messageId());
        if (existing != null
                && existing.ownerServerId() != null
                && !existing.ownerServerId().isBlank()
                && !existing.ownerServerId().equals(pendingAck.ownerServerId())) {
            redisTemplate.opsForZSet().remove(
                    ownerIndexKey(existing.ownerServerId()),
                    member);
        }
        redisTemplate.opsForZSet().add(deviceKey, pendingAck.messageId(), pendingAck.nextRetryAtMillis());
        redisTemplate.opsForHash().put(attemptKey, pendingAck.messageId(), Integer.toString(pendingAck.attempt()));
        redisTemplate.opsForHash().put(
                metadataKey,
                pendingAck.messageId(),
                encodeMetadata(pendingAck));
        redisTemplate.opsForZSet().add(INDEX_KEY, member, pendingAck.nextRetryAtMillis());
        if (pendingAck.ownerServerId() != null && !pendingAck.ownerServerId().isBlank()) {
            redisTemplate.opsForZSet().add(
                    ownerIndexKey(pendingAck.ownerServerId()),
                    member,
                    pendingAck.nextRetryAtMillis());
        }
    }

    @Override
    public void remove(Long userId, String deviceId, String messageId) {
        PendingAck existing = loadPendingAck(userId, deviceId, messageId);
        redisTemplate.opsForZSet().remove(deviceKey(userId, deviceId), messageId);
        redisTemplate.opsForHash().delete(attemptKey(userId, deviceId), messageId);
        redisTemplate.opsForHash().delete(metadataKey(userId, deviceId), messageId);
        redisTemplate.opsForZSet().remove(INDEX_KEY, member(userId, deviceId, messageId));
        if (existing != null && existing.ownerServerId() != null && !existing.ownerServerId().isBlank()) {
            redisTemplate.opsForZSet().remove(
                    ownerIndexKey(existing.ownerServerId()),
                    member(userId, deviceId, messageId));
        }
    }

    @Override
    public void removeIfConnection(Long userId, String deviceId, String messageId, String connectionId) {
        Object metadata = redisTemplate.opsForHash().get(metadataKey(userId, deviceId), messageId);
        if (connectionId == null || connectionId.isBlank()) {
            remove(userId, deviceId, messageId);
            return;
        }
        if (metadata != null
                && connectionId.equals(decodeMetadata(metadata.toString()).connectionId())) {
            remove(userId, deviceId, messageId);
        }
    }

    @Override
    public List<PendingAck> findDue(long nowMillis, int limit) {
        return findDue(nowMillis, limit, null);
    }

    @Override
    public List<PendingAck> findDue(long nowMillis, int limit, String ownerServerId) {
        String indexKey = ownerServerId == null || ownerServerId.isBlank()
                ? INDEX_KEY
                : ownerIndexKey(ownerServerId);
        Set<String> members = redisTemplate.opsForZSet().rangeByScore(indexKey, 0, nowMillis, 0, limit);
        if (members == null || members.isEmpty()) {
            return List.of();
        }

        List<PendingAck> due = new ArrayList<>();
        for (String member : members) {
            PendingMember pendingMember = parseMember(member);
            if (pendingMember == null) {
                redisTemplate.opsForZSet().remove(indexKey, member);
                continue;
            }
            Long userId = pendingMember.userId();
            String deviceId = pendingMember.deviceId();
            String messageId = pendingMember.messageId();
            Double score = redisTemplate.opsForZSet().score(deviceKey(userId, deviceId), messageId);
            if (score == null) {
                redisTemplate.opsForZSet().remove(indexKey, member);
                continue;
            }
            if (score.longValue() > nowMillis) {
                continue;
            }
            Object attemptValue = redisTemplate.opsForHash().get(attemptKey(userId, deviceId), messageId);
            int attempt = attemptValue == null ? 0 : Integer.parseInt(attemptValue.toString());
            Object metadataValue = redisTemplate.opsForHash().get(metadataKey(userId, deviceId), messageId);
            PendingMetadata metadata = metadataValue == null
                    ? PendingMetadata.empty()
                    : decodeMetadata(metadataValue.toString());
            if (ownerServerId != null
                    && !ownerServerId.isBlank()
                    && !ownerServerId.equals(metadata.ownerServerId())) {
                continue;
            }
            due.add(new PendingAck(
                    userId,
                    deviceId,
                    messageId,
                    score.longValue(),
                    attempt,
                    metadata.connectionId(),
                    metadata.ownerServerId(),
                    metadata.deliveryId(),
                    metadata.hopCount()));
        }
        return due;
    }

    @Override
    public boolean exists(Long userId, String deviceId, String messageId) {
        return redisTemplate.opsForZSet().score(deviceKey(userId, deviceId), messageId) != null;
    }

    @Override
    public long count() {
        Long size = redisTemplate.opsForZSet().zCard(INDEX_KEY);
        return size == null ? 0L : size;
    }

    private String deviceKey(Long userId, String deviceId) {
        return "im:pending_ack:" + userId + ":" + deviceId;
    }

    private String attemptKey(Long userId, String deviceId) {
        return "im:pending_ack_attempt:" + userId + ":" + deviceId;
    }

    private String metadataKey(Long userId, String deviceId) {
        return "im:pending_ack_meta:" + userId + ":" + deviceId;
    }

    private String ownerIndexKey(String ownerServerId) {
        return OWNER_INDEX_PREFIX + ownerServerId;
    }

    private String member(Long userId, String deviceId, String messageId) {
        return userId + DELIMITER + encode(deviceId) + DELIMITER + encode(messageId);
    }

    private PendingMember parseMember(String member) {
        String[] parts = member.split("\\" + DELIMITER, 3);
        if (parts.length != 3) {
            return null;
        }
        try {
            return new PendingMember(Long.valueOf(parts[0]), decode(parts[1]), decode(parts[2]));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private PendingAck loadPendingAck(Long userId, String deviceId, String messageId) {
        Double score = redisTemplate.opsForZSet().score(deviceKey(userId, deviceId), messageId);
        if (score == null) {
            return null;
        }
        Object attemptValue = redisTemplate.opsForHash().get(attemptKey(userId, deviceId), messageId);
        int attempt = attemptValue == null ? 0 : Integer.parseInt(attemptValue.toString());
        Object metadataValue = redisTemplate.opsForHash().get(metadataKey(userId, deviceId), messageId);
        PendingMetadata metadata = metadataValue == null
                ? PendingMetadata.empty()
                : decodeMetadata(metadataValue.toString());
        return new PendingAck(
                userId,
                deviceId,
                messageId,
                score.longValue(),
                attempt,
                metadata.connectionId(),
                metadata.ownerServerId(),
                metadata.deliveryId(),
                metadata.hopCount());
    }

    private String encodeMetadata(PendingAck pendingAck) {
        return encode(valueOrEmpty(pendingAck.connectionId()))
                + DELIMITER
                + encode(valueOrEmpty(pendingAck.ownerServerId()))
                + DELIMITER
                + encode(valueOrEmpty(pendingAck.deliveryId()))
                + DELIMITER
                + pendingAck.hopCount();
    }

    private PendingMetadata decodeMetadata(String value) {
        String[] parts = value.split("\\" + DELIMITER, 4);
        if (parts.length < 3) {
            return PendingMetadata.empty();
        }
        try {
            return new PendingMetadata(
                    emptyToNull(decode(parts[0])),
                    emptyToNull(decode(parts[1])),
                    emptyToNull(decode(parts[2])),
                    parts.length == 4 ? Integer.parseInt(parts[3]) : 0);
        } catch (RuntimeException exception) {
            return PendingMetadata.empty();
        }
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record PendingMember(Long userId, String deviceId, String messageId) {
    }

    private record PendingMetadata(
            String connectionId,
            String ownerServerId,
            String deliveryId,
            int hopCount) {

        private static PendingMetadata empty() {
            return new PendingMetadata(null, null, null, 0);
        }
    }
}
