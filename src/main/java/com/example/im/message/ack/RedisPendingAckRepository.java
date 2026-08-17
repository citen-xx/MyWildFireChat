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
    private static final String DELIMITER = "|";

    private final StringRedisTemplate redisTemplate;

    public RedisPendingAckRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(PendingAck pendingAck) {
        String deviceKey = deviceKey(pendingAck.userId(), pendingAck.deviceId());
        String attemptKey = attemptKey(pendingAck.userId(), pendingAck.deviceId());
        String member = member(pendingAck.userId(), pendingAck.deviceId(), pendingAck.messageId());
        redisTemplate.opsForZSet().add(deviceKey, pendingAck.messageId(), pendingAck.nextRetryAtMillis());
        redisTemplate.opsForHash().put(attemptKey, pendingAck.messageId(), Integer.toString(pendingAck.attempt()));
        redisTemplate.opsForZSet().add(INDEX_KEY, member, pendingAck.nextRetryAtMillis());
    }

    @Override
    public void remove(Long userId, String deviceId, String messageId) {
        redisTemplate.opsForZSet().remove(deviceKey(userId, deviceId), messageId);
        redisTemplate.opsForHash().delete(attemptKey(userId, deviceId), messageId);
        redisTemplate.opsForZSet().remove(INDEX_KEY, member(userId, deviceId, messageId));
    }

    @Override
    public List<PendingAck> findDue(long nowMillis, int limit) {
        Set<String> members = redisTemplate.opsForZSet().rangeByScore(INDEX_KEY, 0, nowMillis, 0, limit);
        if (members == null || members.isEmpty()) {
            return List.of();
        }

        List<PendingAck> due = new ArrayList<>();
        for (String member : members) {
            PendingMember pendingMember = parseMember(member);
            if (pendingMember == null) {
                redisTemplate.opsForZSet().remove(INDEX_KEY, member);
                continue;
            }
            Long userId = pendingMember.userId();
            String deviceId = pendingMember.deviceId();
            String messageId = pendingMember.messageId();
            Double score = redisTemplate.opsForZSet().score(deviceKey(userId, deviceId), messageId);
            if (score == null) {
                redisTemplate.opsForZSet().remove(INDEX_KEY, member);
                continue;
            }
            if (score.longValue() > nowMillis) {
                continue;
            }
            Object attemptValue = redisTemplate.opsForHash().get(attemptKey(userId, deviceId), messageId);
            int attempt = attemptValue == null ? 0 : Integer.parseInt(attemptValue.toString());
            due.add(new PendingAck(userId, deviceId, messageId, score.longValue(), attempt));
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

    private record PendingMember(Long userId, String deviceId, String messageId) {
    }
}
