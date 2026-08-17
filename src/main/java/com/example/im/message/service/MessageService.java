package com.example.im.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.im.conversation.model.Conversation;
import com.example.im.conversation.service.ConversationService;
import com.example.im.message.model.ChatMessage;
import com.example.im.message.repository.ChatMessageMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "im.chat.enabled", havingValue = "true", matchIfMissing = true)
public class MessageService {

    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    private final ConversationService conversationService;
    private final ConversationSequenceGenerator sequenceGenerator;
    private final ChatMessageMapper messageMapper;

    public MessageService(
            ConversationService conversationService,
            ConversationSequenceGenerator sequenceGenerator,
            ChatMessageMapper messageMapper) {
        this.conversationService = conversationService;
        this.sequenceGenerator = sequenceGenerator;
        this.messageMapper = messageMapper;
    }

    @Transactional
    public SendMessageResult sendSingleMessage(Long senderId, SendMessageCommand command) {
        validate(senderId, command);

        ChatMessage existing = findByClientMessageId(senderId, command.clientMessageId());
        if (existing != null) {
            return toResult(existing, true);
        }

        Conversation conversation = conversationService.getOrCreateSingleConversation(
                senderId,
                command.receiverId());
        long sequence = sequenceGenerator.nextSequence(conversation.getId());

        ChatMessage message = new ChatMessage();
        message.setMessageId(newMessageId());
        message.setClientMessageId(command.clientMessageId());
        message.setConversationId(conversation.getId());
        message.setSequence(sequence);
        message.setSenderId(senderId);
        message.setReceiverId(command.receiverId());
        message.setContent(command.content());
        message.setMessageType(command.messageType());
        message.setCreatedAt(LocalDateTime.now());

        try {
            messageMapper.insert(message);
            return toResult(message, false);
        } catch (DuplicateKeyException exception) {
            ChatMessage concurrent = findByClientMessageId(senderId, command.clientMessageId());
            if (concurrent == null) {
                throw exception;
            }
            return toResult(concurrent, true);
        }
    }

    public ChatMessage findByClientMessageId(Long senderId, String clientMessageId) {
        return messageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSenderId, senderId)
                .eq(ChatMessage::getClientMessageId, clientMessageId)
                .last("limit 1"));
    }

    private void validate(Long senderId, SendMessageCommand command) {
        if (senderId == null) {
            throw new IllegalArgumentException("senderId is required");
        }
        if (command == null) {
            throw new IllegalArgumentException("message request is required");
        }
        if (command.clientMessageId() == null || command.clientMessageId().isBlank()) {
            throw new IllegalArgumentException("clientMessageId is required");
        }
        if (command.receiverId() == null || command.receiverId() <= 0) {
            throw new IllegalArgumentException("receiverId is required");
        }
        if (command.content() == null || command.content().isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        if (command.messageType() == null || command.messageType().isBlank()) {
            throw new IllegalArgumentException("messageType is required");
        }
    }

    private String newMessageId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "");
    }

    private SendMessageResult toResult(ChatMessage message, boolean duplicate) {
        return new SendMessageResult(
                message.getClientMessageId(),
                message.getMessageId(),
                message.getConversationId(),
                message.getSequence(),
                message.getSenderId(),
                message.getReceiverId(),
                message.getContent(),
                message.getMessageType(),
                message.getCreatedAt().atZone(SYSTEM_ZONE).toInstant().toEpochMilli(),
                duplicate);
    }
}
