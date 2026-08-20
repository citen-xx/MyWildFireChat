package com.example.im.mq;

import com.example.im.message.service.SendMessageResult;
import com.example.im.route.ConnectionRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "im.mq.enabled", havingValue = "false")
public class NoopRemoteMessageRelayPublisher implements RemoteMessageRelayPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopRemoteMessageRelayPublisher.class);

    @Override
    public boolean publish(SendMessageResult message, ConnectionRoute targetRoute) {
        log.info("mq relay skipped because publisher is disabled messageId={} userId={} deviceId={} serverId={}",
                message.messageId(), targetRoute.userId(), targetRoute.deviceId(), targetRoute.serverId());
        return false;
    }
}
