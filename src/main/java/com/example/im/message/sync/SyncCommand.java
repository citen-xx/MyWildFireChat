package com.example.im.message.sync;

public record SyncCommand(
        Long conversationId,
        long lastSequence,
        Integer limit) {
}
