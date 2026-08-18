package com.example.im.message.service;

import com.example.im.message.ack.AckService;
import com.example.im.netty.session.ActiveClientSession;
import com.example.im.netty.session.ClientConnection;
import com.example.im.netty.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collection;

@Service
@ConditionalOnProperty(name = "im.chat.enabled", havingValue = "true", matchIfMissing = true)
public class MessageDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(MessageDeliveryService.class);

    private final SessionManager sessionManager;
    private final AckService ackService;

    public MessageDeliveryService(SessionManager sessionManager, AckService ackService) {
        this.sessionManager = sessionManager;
        this.ackService = ackService;
    }

    public int pushToLocalReceiverDevices(SendMessageResult message) {
        if (message.receiverId() == null || message.receiverId() <= 0) {
            return 0;
        }
        return pushToUserDevices(message, java.util.List.of(message.receiverId()));
    }

    public int pushToUserDevices(SendMessageResult message, Collection<Long> userIds) {
        int pushed = 0;
        for (Long userId : userIds) {
            if (userId == null || userId <= 0) {
                continue;
            }
            for (ActiveClientSession session : sessionManager.findUserSessionConnections(userId)) {
                try {
                    ClientConnection connection = session.connection();
                    connection.sendPush(message);
                    ackService.recordPush(message, session.key().userId(), session.key().deviceId());
                    pushed++;
                } catch (Exception exception) {
                    log.warn("message push failed messageId={} userId={} deviceId={}",
                            message.messageId(), session.key().userId(), session.key().deviceId(), exception);
                }
            }
        }
        return pushed;
    }
}
