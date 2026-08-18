package com.example.im.message.ack;

import com.example.im.message.service.SendMessageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "im.chat.enabled", havingValue = "true", matchIfMissing = true)
public class AckService {

    private static final Logger log = LoggerFactory.getLogger(AckService.class);

    private final PendingAckRepository pendingAckRepository;
    private final AckProperties properties;

    public AckService(PendingAckRepository pendingAckRepository, AckProperties properties) {
        this.pendingAckRepository = pendingAckRepository;
        this.properties = properties;
    }

    public void recordPush(SendMessageResult message, String deviceId) {
        recordPush(message, message.receiverId(), deviceId);
    }

    public void recordPush(SendMessageResult message, Long userId, String deviceId) {
        if (!properties.isRetryEnabled()) {
            return;
        }
        if (properties.maxRetryAttempts() <= 0) {
            return;
        }
        long nextRetryAt = System.currentTimeMillis() + properties.delayForAttempt(0);
        pendingAckRepository.save(new PendingAck(
                userId,
                deviceId,
                message.messageId(),
                nextRetryAt,
                0));
        log.info("message push messageId={} userId={} deviceId={} attempt={}",
                message.messageId(), userId, deviceId, 0);
    }

    public void acknowledge(Long userId, String deviceId, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId is required");
        }
        pendingAckRepository.remove(userId, deviceId, messageId);
        log.info("message ack messageId={} userId={} deviceId={} attempt={}",
                messageId, userId, deviceId, -1);
    }

    public boolean isPending(Long userId, String deviceId, String messageId) {
        return pendingAckRepository.exists(userId, deviceId, messageId);
    }
}
