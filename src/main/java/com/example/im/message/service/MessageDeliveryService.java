package com.example.im.message.service;

import com.example.im.netty.session.ClientConnection;
import com.example.im.netty.session.SessionManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "im.chat.enabled", havingValue = "true", matchIfMissing = true)
public class MessageDeliveryService {

    private final SessionManager sessionManager;

    public MessageDeliveryService(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public int pushToLocalReceiverDevices(SendMessageResult message) {
        int pushed = 0;
        for (ClientConnection connection : sessionManager.findUserConnections(message.receiverId())) {
            connection.sendPush(message);
            pushed++;
        }
        return pushed;
    }
}
