package com.example.im.mq;

import com.example.im.message.ack.AckService;
import com.example.im.message.service.SendMessageResult;
import com.example.im.netty.session.ClientConnection;
import com.example.im.netty.session.SessionManager;
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

    public RabbitMqRemoteMessageRelayListener(
            SessionManager sessionManager,
            AckService ackService,
            RelayDeliveryDeduplicator deduplicator,
            ServerProperties serverProperties) {
        this.sessionManager = sessionManager;
        this.ackService = ackService;
        this.deduplicator = deduplicator;
        this.serverProperties = serverProperties;
    }

    @RabbitListener(queues = "#{@messageRelayQueue.name}")
    public void onMessage(RemoteMessageRelayEvent event) {
        if (event == null || event.getDeliveryId() == null || event.getDeliveryId().isBlank()) {
            log.warn("mq relay ignored because deliveryId is missing");
            return;
        }
        if (!serverProperties.getId().equals(event.getTargetServerId())) {
            log.warn("mq relay ignored because target server does not match deliveryId={} targetServerId={} currentServerId={}",
                    event.getDeliveryId(), event.getTargetServerId(), serverProperties.getId());
            return;
        }
        if (!deduplicator.tryStart(event.getDeliveryId())) {
            log.info("mq relay duplicate ignored deliveryId={} messageId={} userId={} deviceId={}",
                    event.getDeliveryId(), event.getMessageId(), event.getTargetUserId(), event.getTargetDeviceId());
            return;
        }

        Optional<ClientConnection> connection = sessionManager.findConnection(
                event.getTargetUserId(),
                event.getTargetDeviceId());
        if (connection.isEmpty()) {
            log.info("mq relay target offline deliveryId={} messageId={} userId={} deviceId={}",
                    event.getDeliveryId(), event.getMessageId(), event.getTargetUserId(), event.getTargetDeviceId());
            return;
        }

        ClientConnection targetConnection = connection.get();
        if (event.getTargetConnectionId() != null
                && !event.getTargetConnectionId().isBlank()
                && !event.getTargetConnectionId().equals(targetConnection.id())) {
            log.info("mq relay stale connection skipped deliveryId={} messageId={} userId={} deviceId={} expectedConnectionId={} actualConnectionId={}",
                    event.getDeliveryId(),
                    event.getMessageId(),
                    event.getTargetUserId(),
                    event.getTargetDeviceId(),
                    event.getTargetConnectionId(),
                    targetConnection.id());
            return;
        }

        SendMessageResult message = event.toMessageResult();
        try {
            targetConnection.sendPush(message);
            ackService.recordPush(message, event.getTargetUserId(), event.getTargetDeviceId());
            log.info("mq relay delivered deliveryId={} messageId={} userId={} deviceId={} attempt={}",
                    event.getDeliveryId(), event.getMessageId(), event.getTargetUserId(), event.getTargetDeviceId(), 0);
        } catch (Exception exception) {
            log.warn("mq relay delivery failed deliveryId={} messageId={} userId={} deviceId={}",
                    event.getDeliveryId(), event.getMessageId(), event.getTargetUserId(), event.getTargetDeviceId(),
                    exception);
        }
    }
}
