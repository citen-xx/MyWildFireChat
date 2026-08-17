package com.example.im.message.ack;

public record PendingAck(
        Long userId,
        String deviceId,
        String messageId,
        long nextRetryAtMillis,
        int attempt) {
}
