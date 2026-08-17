package com.example.im.message.service;

public record SendMessageCommand(
        String clientMessageId,
        Long receiverId,
        String content,
        String messageType) {
}
