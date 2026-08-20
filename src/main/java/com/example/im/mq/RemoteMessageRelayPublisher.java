package com.example.im.mq;

import com.example.im.message.service.SendMessageResult;
import com.example.im.route.ConnectionRoute;

public interface RemoteMessageRelayPublisher {

    boolean publish(SendMessageResult message, ConnectionRoute targetRoute);
}
