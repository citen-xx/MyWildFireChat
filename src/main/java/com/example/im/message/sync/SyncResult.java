package com.example.im.message.sync;

import com.example.im.message.service.SendMessageResult;

import java.util.List;

public record SyncResult(
        Long conversationId,
        List<SendMessageResult> messages,
        boolean hasMore,
        long nextSequence) {
}
