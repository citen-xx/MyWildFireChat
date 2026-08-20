package com.example.im.mq;

import com.example.im.message.service.SendMessageResult;
import com.example.im.route.ConnectionRoute;
import com.example.im.route.ServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "im.mq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqRemoteMessageRelayPublisher implements RemoteMessageRelayPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqRemoteMessageRelayPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqProperties mqProperties;
    private final ServerProperties serverProperties;

    public RabbitMqRemoteMessageRelayPublisher(
            RabbitTemplate rabbitTemplate,
            RabbitMqProperties mqProperties,
            ServerProperties serverProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.mqProperties = mqProperties;
        this.serverProperties = serverProperties;
    }

    @Override
    public boolean publish(SendMessageResult message, ConnectionRoute targetRoute) {
        String deliveryId = RemoteMessageRelayEvent.stableDeliveryId(
                serverProperties.getId(),
                message,
                targetRoute.userId(),
                targetRoute.deviceId(),
                targetRoute.connectionId());
        RemoteMessageRelayEvent event = RemoteMessageRelayEvent.of(
                deliveryId,
                message,
                targetRoute.userId(),
                targetRoute.deviceId(),
                targetRoute.connectionId(),
                targetRoute.serverId(),
                serverProperties.getId());
        try {
            rabbitTemplate.convertAndSend(mqProperties.getExchange(), targetRoute.serverId(), event);
            log.info("mq relay published deliveryId={} messageId={} userId={} deviceId={} targetServerId={}",
                    deliveryId, message.messageId(), targetRoute.userId(), targetRoute.deviceId(), targetRoute.serverId());
            return true;
        } catch (AmqpException exception) {
            log.warn("mq relay publish failed deliveryId={} messageId={} userId={} deviceId={} targetServerId={}",
                    deliveryId, message.messageId(), targetRoute.userId(), targetRoute.deviceId(),
                    targetRoute.serverId(), exception);
            return false;
        }
    }
}
