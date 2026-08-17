package com.example.im.message.service;

public record SendMessageResult(
        String clientMessageId,
        String messageId,
        Long conversationId,
        Long sequence,
        Long senderId,
        Long receiverId,
        String content,
        String messageType,
        long createdAt,
        boolean duplicate) {
}
