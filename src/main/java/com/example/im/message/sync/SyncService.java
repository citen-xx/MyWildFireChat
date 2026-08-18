package com.example.im.message.sync;

import com.example.im.conversation.service.ConversationService;
import com.example.im.message.service.MessageService;
import com.example.im.message.service.SendMessageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(name = "im.chat.enabled", havingValue = "true", matchIfMissing = true)
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    private final ConversationService conversationService;
    private final MessageService messageService;

    public SyncService(ConversationService conversationService, MessageService messageService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
    }

    public SyncResult sync(Long userId, String deviceId, SyncCommand command) {
        validate(userId, command);
        int limit = normalizeLimit(command.limit());

        if (!conversationService.isMember(command.conversationId(), userId)) {
            log.info("sync denied userId={} deviceId={} conversationId={} fromSequence={} count={}",
                    userId, deviceId, command.conversationId(), command.lastSequence(), 0);
            throw new IllegalArgumentException("user is not a member of the conversation");
        }

        List<SendMessageResult> rows = messageService.findConversationMessagesAfter(
                command.conversationId(),
                command.lastSequence(),
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<SendMessageResult> page = hasMore ? rows.subList(0, limit) : rows;
        long nextSequence = page.isEmpty()
                ? command.lastSequence()
                : page.get(page.size() - 1).sequence();

        log.info("sync result userId={} deviceId={} conversationId={} fromSequence={} count={}",
                userId, deviceId, command.conversationId(), command.lastSequence(), page.size());
        return new SyncResult(command.conversationId(), page, hasMore, nextSequence);
    }

    private void validate(Long userId, SyncCommand command) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId is required");
        }
        if (command == null) {
            throw new IllegalArgumentException("sync request is required");
        }
        if (command.conversationId() == null || command.conversationId() <= 0) {
            throw new IllegalArgumentException("conversationId is required");
        }
        if (command.lastSequence() < 0) {
            throw new IllegalArgumentException("lastSequence must be greater than or equal to 0");
        }
    }

    private int normalizeLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (requestedLimit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }
}
