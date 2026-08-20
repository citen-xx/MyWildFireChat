package com.example.im.message.ack;

public record PendingAck(
        Long userId,
        String deviceId,
        String messageId,
        long nextRetryAtMillis,
        int attempt,
        String connectionId,
        String ownerServerId,
        String deliveryId,
        int hopCount) {

    public PendingAck(
            Long userId,
            String deviceId,
            String messageId,
            long nextRetryAtMillis,
            int attempt) {
        this(userId, deviceId, messageId, nextRetryAtMillis, attempt, null, null, null, 0);
    }
}
