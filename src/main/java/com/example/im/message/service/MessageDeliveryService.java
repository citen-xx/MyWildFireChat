package com.example.im.message.service;

import com.example.im.message.ack.AckService;
import com.example.im.netty.session.ActiveClientSession;
import com.example.im.netty.session.ClientConnection;
import com.example.im.netty.session.SessionManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "im.chat.enabled", havingValue = "true", matchIfMissing = true)
public class MessageDeliveryService {

    private final SessionManager sessionManager;
    private final AckService ackService;

    public MessageDeliveryService(SessionManager sessionManager, AckService ackService) {
        this.sessionManager = sessionManager;
        this.ackService = ackService;
    }

    public int pushToLocalReceiverDevices(SendMessageResult message) {
        int pushed = 0;
        for (ActiveClientSession session : sessionManager.findUserSessionConnections(message.receiverId())) {
            ClientConnection connection = session.connection();
            connection.sendPush(message);
            ackService.recordPush(message, session.key().deviceId());
            pushed++;
        }
        return pushed;
    }
}
