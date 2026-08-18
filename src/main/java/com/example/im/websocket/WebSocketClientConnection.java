package com.example.im.websocket;

import com.example.im.message.service.SendMessageResult;
import com.example.im.message.sync.SyncResult;
import com.example.im.netty.session.ClientConnection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

public class WebSocketClientConnection implements ClientConnection {

    private final WebSocketSession session;
    private final ObjectMapper objectMapper;

    public WebSocketClientConnection(WebSocketSession session, ObjectMapper objectMapper) {
        this.session = session;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return "ws:" + session.getId();
    }

    @Override
    public void sendPush(SendMessageResult message) {
        sendJson("PUSH_MESSAGE", "", Map.of(
                "clientMessageId", message.clientMessageId(),
                "messageId", message.messageId(),
                "conversationId", message.conversationId(),
                "sequence", message.sequence(),
                "senderId", message.senderId(),
                "receiverId", message.receiverId(),
                "content", message.content(),
                "messageType", message.messageType(),
                "createdAt", message.createdAt()));
    }

    public void sendSyncResponse(String requestId, SyncResult result) {
        sendJson("SYNC_RESPONSE", requestId, Map.of(
                "conversationId", result.conversationId(),
                "messages", result.messages().stream()
                        .map(this::messagePayload)
                        .toList(),
                "hasMore", result.hasMore(),
                "nextSequence", result.nextSequence()));
    }

    public void sendSyncComplete(String requestId, Long conversationId, long nextSequence) {
        sendJson("SYNC_COMPLETE", requestId, Map.of(
                "conversationId", conversationId,
                "nextSequence", nextSequence));
    }

    public void sendJson(String type, String requestId, Object payload) {
        if (!isActive()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(new WebSocketOutboundMessage(
                    type,
                    requestId == null ? "" : requestId,
                    System.currentTimeMillis(),
                    payload));
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception exception) {
            close();
        }
    }

    @Override
    public void close() {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean isActive() {
        return session.isOpen();
    }

    public record WebSocketOutboundMessage(
            String type,
            String requestId,
            long timestamp,
            Object payload) {
    }

    private Map<String, Object> messagePayload(SendMessageResult message) {
        return Map.of(
                "clientMessageId", message.clientMessageId(),
                "messageId", message.messageId(),
                "conversationId", message.conversationId(),
                "sequence", message.sequence(),
                "senderId", message.senderId(),
                "receiverId", message.receiverId(),
                "content", message.content(),
                "messageType", message.messageType(),
                "createdAt", message.createdAt());
    }
}
