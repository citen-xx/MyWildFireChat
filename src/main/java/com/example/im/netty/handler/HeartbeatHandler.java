package com.example.im.netty.handler;

import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.springframework.stereotype.Component;

@Component
@Sharable
public class HeartbeatHandler extends SimpleChannelInboundHandler<MessageEnvelope> {

    @Override
    protected void channelRead0(ChannelHandlerContext context, MessageEnvelope envelope) {
        if (envelope.getMessageType() == MessageEnvelope.MessageType.PING) {
            MessageEnvelope pong = MessageEnvelope.newBuilder()
                    .setMessageType(MessageEnvelope.MessageType.PONG)
                    .setRequestId(envelope.getRequestId())
                    .setTimestamp(System.currentTimeMillis())
                    .build();
            context.writeAndFlush(pong);
            return;
        }
        context.fireChannelRead(envelope);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof IdleStateEvent idleEvent
                && idleEvent.state() == IdleState.READER_IDLE) {
            context.close();
            return;
        }
        context.fireUserEventTriggered(event);
    }
}
