package com.example.im.netty.session;

import com.example.im.message.service.SendMessageResult;
import com.example.im.netty.handler.ProtocolMessageFactory;
import io.netty.channel.Channel;

public class NettyClientConnection implements ClientConnection {

    private final Channel channel;

    public NettyClientConnection(Channel channel) {
        this.channel = channel;
    }

    public static String idOf(Channel channel) {
        return "tcp:" + channel.id().asLongText();
    }

    @Override
    public String id() {
        return idOf(channel);
    }

    @Override
    public void sendPush(SendMessageResult message) {
        channel.writeAndFlush(ProtocolMessageFactory.pushMessage(message));
    }

    @Override
    public void close() {
        channel.close();
    }

    @Override
    public boolean isActive() {
        return channel.isActive();
    }

    public Channel channel() {
        return channel;
    }
}
