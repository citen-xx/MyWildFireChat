package com.example.im.message.ack;

import com.example.im.message.service.SendMessageResult;
import com.example.im.mq.RemoteMessageRelayEvent;
import com.example.im.route.ConnectionRoute;
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
        recordPush(message, userId, deviceId, null, null, null);
    }

    public void recordPush(
            SendMessageResult message,
            Long userId,
            String deviceId,
            String connectionId,
            String ownerServerId,
            String deliveryId) {
        recordPush(message, userId, deviceId, connectionId, ownerServerId, deliveryId, 0, 0);
    }

    public void recordPush(
            SendMessageResult message,
            Long userId,
            String deviceId,
            String connectionId,
            String ownerServerId,
            String deliveryId,
            int hopCount) {
        recordPush(message, userId, deviceId, connectionId, ownerServerId, deliveryId, 0, hopCount);
    }

    public void recordPush(
            SendMessageResult message,
            Long userId,
            String deviceId,
            String connectionId,
            String ownerServerId,
            String deliveryId,
            int attempt,
            int hopCount) {
        if (!properties.isRetryEnabled()) {
            return;
        }
        if (properties.maxRetryAttempts() <= 0) {
            return;
        }
        if (attempt >= properties.maxRetryAttempts()) {
            return;
        }
        long nextRetryAt = System.currentTimeMillis() + properties.delayForAttempt(attempt);
        pendingAckRepository.save(new PendingAck(
                userId,
                deviceId,
                message.messageId(),
                nextRetryAt,
                attempt,
                connectionId,
                ownerServerId,
                deliveryId,
                hopCount));
        log.info("message push messageId={} userId={} deviceId={} attempt={}",
                message.messageId(), userId, deviceId, attempt);
    }

    public void acknowledge(Long userId, String deviceId, String messageId) {
        acknowledge(userId, deviceId, messageId, null);
    }

    public void acknowledge(Long userId, String deviceId, String messageId, String connectionId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId is required");
        }
        pendingAckRepository.removeIfConnection(userId, deviceId, messageId, connectionId);
        log.info("message ack messageId={} userId={} deviceId={} attempt={}",
                messageId, userId, deviceId, -1);
    }

    public void recordRetry(
            SendMessageResult message,
            ConnectionRoute route,
            int attempt,
            String deliveryId,
            int hopCount) {
        if (!properties.isRetryEnabled() || properties.maxRetryAttempts() <= 0) {
            return;
        }
        long nextRetryAt = System.currentTimeMillis() + properties.delayForAttempt(attempt);
        pendingAckRepository.save(new PendingAck(
                route.userId(),
                route.deviceId(),
                message.messageId(),
                nextRetryAt,
                attempt,
                route.connectionId(),
                route.serverId(),
                deliveryId == null
                        ? RemoteMessageRelayEvent.stableDeliveryId(
                        message, route.userId(), route.deviceId())
                        : deliveryId,
                hopCount));
    }

    public boolean isPending(Long userId, String deviceId, String messageId) {
        return pendingAckRepository.exists(userId, deviceId, messageId);
    }
}
