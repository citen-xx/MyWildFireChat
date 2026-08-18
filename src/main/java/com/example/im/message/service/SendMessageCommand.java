package com.example.im.message.service;

public record SendMessageCommand(
        String clientMessageId,
        Long receiverId,
        Long groupId,
        String conversationType,
        String content,
        String messageType) {
}
