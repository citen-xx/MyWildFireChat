package com.example.im.mq;

import com.example.im.message.ack.AckService;
import com.example.im.message.ack.AckProperties;
import com.example.im.message.service.SendMessageResult;
import com.example.im.netty.session.ClientConnection;
import com.example.im.netty.session.SessionManager;
import com.example.im.route.ConnectionLocation;
import com.example.im.route.ConnectionLocationType;
import com.example.im.route.ConnectionLocator;
import com.example.im.route.ServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(name = {"im.mq.enabled", "im.chat.enabled"}, havingValue = "true", matchIfMissing = true)
public class RabbitMqRemoteMessageRelayListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqRemoteMessageRelayListener.class);

    private final SessionManager sessionManager;
    private final AckService ackService;
    private final RelayDeliveryDeduplicator deduplicator;
    private final ServerProperties serverProperties;
    private final ConnectionLocator connectionLocator;
    private final RemoteMessageRelayPublisher relayPublisher;
    private final AckProperties ackProperties;

    public RabbitMqRemoteMessageRelayListener(
            SessionManager sessionManager,
            AckService ackService,
            RelayDeliveryDeduplicator deduplicator,
            ServerProperties serverProperties,
            ConnectionLocator connectionLocator,
            RemoteMessageRelayPublisher relayPublisher,
            AckProperties ackProperties) {
        this.sessionManager = sessionManager;
        this.ackService = ackService;
        this.deduplicator = deduplicator;
        this.serverProperties = serverProperties;
        this.connectionLocator = connectionLocator;
        this.relayPublisher = relayPublisher;
        this.ackProperties = ackProperties;
    }

    @RabbitListener(queues = "#{@messageRelayQueue.name}")
    public void onMessage(RemoteMessageRelayEvent event) {
        if (event == null || event.getDeliveryId() == null || event.getDeliveryId().isBlank()) {
            log.warn("mq relay ignored because deliveryId is missing");
            return;
        }
        String eventId = event.getEventId();
        if (eventId == null || eventId.isBlank()) {
            eventId = RemoteMessageRelayEvent.stableEventId(
                    event.getSourceServerId(),
                    event.getDeliveryId(),
                    event.getTargetServerId(),
                    event.getAttempt(),
                    event.getHopCount());
            event.setEventId(eventId);
        }
        if (!serverProperties.getId().equals(event.getTargetServerId())) {
            log.warn("mq relay ignored because target server does not match deliveryId={} targetServerId={} currentServerId={}",
                    event.getDeliveryId(), event.getTargetServerId(), serverProperties.getId());
            return;
        }
        if (!deduplicator.tryStart(eventId)) {
            log.info("mq relay duplicate ignored deliveryId={} eventId={} messageId={} userId={} deviceId={}",
                    event.getDeliveryId(), eventId, event.getMessageId(), event.getTargetUserId(), event.getTargetDeviceId());
            return;
        }

        ConnectionLocation location = connectionLocator.locate(
                event.getTargetUserId(),
                event.getTargetDeviceId());
        if (location.type() == ConnectionLocationType.OFFLINE) {
            log.info("mq relay target offline deliveryId={} eventId={} messageId={} userId={} deviceId={}",
                    event.getDeliveryId(), event.getMessageId(), event.getTargetUserId(), event.getTargetDeviceId());
            return;
        }

        if (location.type() == ConnectionLocationType.REMOTE) {
            int nextHopCount = event.getHopCount() + 1;
            if (nextHopCount > Math.max(ackProperties.getMaxRelayHops(), 0)) {
                log.warn("mq relay hop exhausted deliveryId={} eventId={} messageId={} userId={} deviceId={} hopCount={}",
                        event.getDeliveryId(), eventId, event.getMessageId(), event.getTargetUserId(),
                        event.getTargetDeviceId(), nextHopCount);
                return;
            }
            boolean forwarded = relayPublisher.publish(
                    event.toMessageResult(),
                    location.route(),
                    event.getDeliveryId(),
                    event.getAttempt(),
                    nextHopCount);
            if (!forwarded) {
                deduplicator.release(eventId);
                throw new IllegalStateException("failed to forward relay event");
            }
            log.info("delivery migrated messageId={} deliveryId={} eventId={} userId={} deviceId={} ownerServerId={} targetServerId={} attempt={} hopCount={}",
                    event.getMessageId(), event.getDeliveryId(), eventId, event.getTargetUserId(),
                    event.getTargetDeviceId(), serverProperties.getId(), location.route().serverId(),
                    event.getAttempt(), nextHopCount);
            return;
        }

        ClientConnection targetConnection = location.connection();
        SendMessageResult message = event.toMessageResult();
        try {
            ackService.recordPush(
                    message,
                    event.getTargetUserId(),
                    event.getTargetDeviceId(),
                    location.route().connectionId(),
                    serverProperties.getId(),
                    event.getDeliveryId(),
                    event.getAttempt(),
                    event.getHopCount());
            targetConnection.sendPush(message);
            log.info("mq relay delivered deliveryId={} eventId={} messageId={} userId={} deviceId={} attempt={} hopCount={}",
                    event.getDeliveryId(), eventId, event.getMessageId(), event.getTargetUserId(),
                    event.getTargetDeviceId(), event.getAttempt(), event.getHopCount());
        } catch (Exception exception) {
            deduplicator.release(eventId);
            log.warn("mq relay delivery failed deliveryId={} eventId={} messageId={} userId={} deviceId={}",
                    event.getDeliveryId(), event.getMessageId(), event.getTargetUserId(), event.getTargetDeviceId(),
                    exception);
            throw exception;
        }
    }
}
