package com.example.im.netty.session;

import com.example.im.message.service.SendMessageResult;

public interface ClientConnection {

    String id();

    void sendPush(SendMessageResult message);

    void close();

    boolean isActive();
}
