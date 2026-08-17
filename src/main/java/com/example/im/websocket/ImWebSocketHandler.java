package com.example.im.websocket;

import com.example.im.auth.security.JwtClaims;
import com.example.im.auth.security.JwtService;
import com.example.im.common.exception.AuthException;
import com.example.im.message.service.MessageDeliveryService;
import com.example.im.message.service.MessageService;
import com.example.im.message.service.SendMessageCommand;
import com.example.im.message.service.SendMessageResult;
import com.example.im.netty.session.ImSession;
import com.example.im.netty.session.SessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "im.websocket.enabled", havingValue = "true", matchIfMissing = true)
public class ImWebSocketHandler extends TextWebSocketHandler {

    private static final String CONNECTION_ATTR = "im.ws.connection";
    private static final String SESSION_ATTR = "im.ws.session";

    private final ObjectMapper objectMapper;
    private final JwtService jwtService;
    private final SessionManager sessionManager;
    private final MessageService messageService;
    private final MessageDeliveryService deliveryService;

    public ImWebSocketHandler(
            ObjectMapper objectMapper,
            JwtService jwtService,
            SessionManager sessionManager,
            MessageService messageService,
            MessageDeliveryService deliveryService) {
        this.objectMapper = objectMapper;
        this.jwtService = jwtService;
        this.sessionManager = sessionManager;
        this.messageService = messageService;
        this.deliveryService = deliveryService;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode root = objectMapper.readTree(message.getPayload());
        String type = text(root, "type");
        String requestId = text(root, "requestId");

        if ("CONNECT".equals(type)) {
            handleConnect(session, root, requestId);
            return;
        }

        WebSocketClientConnection connection = connection(session);
        ImSession imSession = imSession(session);
        if (connection == null || imSession == null) {
            sendError(session, requestId, "UNAUTHENTICATED", "CONNECT is required before other messages");
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("CONNECT required"));
            return;
        }

        if ("PING".equals(type)) {
            connection.sendJson("PONG", requestId, Map.of());
            return;
        }

        if ("SEND_MESSAGE".equals(type)) {
            handleSendMessage(connection, imSession, root.path("payload"), requestId);
            return;
        }

        connection.sendJson("ERROR", requestId, Map.of(
                "code", "UNKNOWN_TYPE",
                "message", "unknown websocket message type"));
    }

    private void handleConnect(WebSocketSession session, JsonNode root, String requestId) throws Exception {
        String token = text(root, "token");
        String deviceId = text(root, "deviceId");
        JsonNode payload = root.path("payload");
        if (token.isBlank()) {
            token = text(payload, "token");
        }
        if (deviceId.isBlank()) {
            deviceId = text(payload, "deviceId");
        }

        try {
            if (deviceId.isBlank()) {
                throw new AuthException("INVALID_CONNECT", "deviceId is required");
            }
            JwtClaims claims = jwtService.verify(token);
            WebSocketClientConnection connection = new WebSocketClientConnection(session, objectMapper);
            ImSession imSession = sessionManager.bind(claims.userId(), deviceId, connection);
            session.getAttributes().put(CONNECTION_ATTR, connection);
            session.getAttributes().put(SESSION_ATTR, imSession);
            connection.sendJson("CONNECT_ACK", requestId, Map.of(
                    "userId", imSession.userId(),
                    "deviceId", imSession.deviceId(),
                    "serverTime", System.currentTimeMillis()));
        } catch (AuthException exception) {
            sendError(session, requestId, exception.getCode(), exception.getMessage());
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason(exception.getCode()));
        }
    }

    private void handleSendMessage(
            WebSocketClientConnection connection,
            ImSession imSession,
            JsonNode payload,
            String requestId) {
        try {
            SendMessageCommand command = new SendMessageCommand(
                    text(payload, "clientMessageId"),
                    payload.path("receiverId").asLong(),
                    text(payload, "content"),
                    text(payload, "messageType"));
            SendMessageResult result = messageService.sendSingleMessage(imSession.userId(), command);
            connection.sendJson("SEND_RESULT", requestId, Map.of(
                    "clientMessageId", result.clientMessageId(),
                    "messageId", result.messageId(),
                    "conversationId", result.conversationId(),
                    "sequence", result.sequence(),
                    "createdAt", result.createdAt()));
            deliveryService.pushToLocalReceiverDevices(result);
        } catch (IllegalArgumentException exception) {
            connection.sendJson("ERROR", requestId, Map.of(
                    "code", "INVALID_SEND_MESSAGE",
                    "message", exception.getMessage()));
        } catch (Exception exception) {
            connection.sendJson("ERROR", requestId, Map.of(
                    "code", "SEND_MESSAGE_FAILED",
                    "message", "failed to persist message"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        WebSocketClientConnection connection = connection(session);
        if (connection != null) {
            sessionManager.remove(connection);
        }
        super.afterConnectionClosed(session, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        WebSocketClientConnection connection = connection(session);
        if (connection != null) {
            sessionManager.remove(connection);
        }
        session.close(CloseStatus.SERVER_ERROR);
    }

    private WebSocketClientConnection connection(WebSocketSession session) {
        Object value = session.getAttributes().get(CONNECTION_ATTR);
        if (value instanceof WebSocketClientConnection connection) {
            return connection;
        }
        return null;
    }

    private ImSession imSession(WebSocketSession session) {
        Object value = session.getAttributes().get(SESSION_ATTR);
        if (value instanceof ImSession imSession) {
            return imSession;
        }
        return null;
    }

    private void sendError(WebSocketSession session, String requestId, String code, String message) {
        new WebSocketClientConnection(session, objectMapper)
                .sendJson("ERROR", requestId, Map.of("code", code, "message", message));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : "";
    }
}
