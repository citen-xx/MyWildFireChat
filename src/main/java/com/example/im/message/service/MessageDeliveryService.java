package com.example.im.message.service;

import com.example.im.message.ack.AckService;
import com.example.im.mq.RemoteMessageRelayEvent;
import com.example.im.netty.session.ClientConnection;
import com.example.im.mq.RemoteMessageRelayPublisher;
import com.example.im.route.ConnectionLocation;
import com.example.im.route.ConnectionLocationType;
import com.example.im.route.ConnectionLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collection;

@Service
@ConditionalOnProperty(name = "im.chat.enabled", havingValue = "true", matchIfMissing = true)
public class MessageDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(MessageDeliveryService.class);

    private final ConnectionLocator connectionLocator;
    private final AckService ackService;
    private final RemoteMessageRelayPublisher relayPublisher;

    public MessageDeliveryService(
            ConnectionLocator connectionLocator,
            AckService ackService,
            RemoteMessageRelayPublisher relayPublisher) {
        this.connectionLocator = connectionLocator;
        this.ackService = ackService;
        this.relayPublisher = relayPublisher;
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
            for (ConnectionLocation location : connectionLocator.locateUserDevices(userId)) {
                if (location.type() == ConnectionLocationType.REMOTE) {
                    boolean published = relayPublisher.publish(message, location.route());
                    if (!published) {
                        log.warn("message remote relay skipped messageId={} userId={} deviceId={} serverId={}",
                                message.messageId(), userId, location.route().deviceId(), location.route().serverId());
                    }
                    continue;
                }
                if (location.type() == ConnectionLocationType.OFFLINE) {
                    continue;
                }
                try {
                    ClientConnection connection = location.connection();
                    String deliveryId = RemoteMessageRelayEvent.stableDeliveryId(
                            message, userId, location.route().deviceId());
                    ackService.recordPush(
                            message,
                            userId,
                            location.route().deviceId(),
                            location.route().connectionId(),
                            location.route().serverId(),
                            deliveryId);
                    connection.sendPush(message);
                    pushed++;
                } catch (Exception exception) {
                    log.warn("message push failed messageId={} userId={} deviceId={}",
                            message.messageId(), userId, location.route().deviceId(), exception);
                }
            }
        }
        return pushed;
    }
}
