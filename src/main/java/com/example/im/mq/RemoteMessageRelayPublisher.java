package com.example.im.mq;

import com.example.im.message.service.SendMessageResult;
import com.example.im.route.ConnectionRoute;

public interface RemoteMessageRelayPublisher {

    boolean publish(SendMessageResult message, ConnectionRoute targetRoute);

    default boolean publish(
            SendMessageResult message,
            ConnectionRoute targetRoute,
            String deliveryId,
            int attempt,
            int hopCount) {
        return publish(message, targetRoute);
    }
}
