package com.example.im.netty.handler;

import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandler.Sharable;
import org.springframework.stereotype.Component;

@Component
@Sharable
public class AuthHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (message instanceof MessageEnvelope envelope
                && envelope.getMessageType() == MessageEnvelope.MessageType.CONNECT) {
            context.fireChannelRead(message);
            return;
        }

        // CONNECT authentication is introduced in Phase 2.
        context.fireChannelRead(message);
    }
}
