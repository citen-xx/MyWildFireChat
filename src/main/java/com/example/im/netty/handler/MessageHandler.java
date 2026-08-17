package com.example.im.netty.handler;

import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@Sharable
public class MessageHandler extends SimpleChannelInboundHandler<MessageEnvelope> {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    @Override
    protected void channelRead0(ChannelHandlerContext context, MessageEnvelope envelope) {
        log.debug("Received protocol message type={}, requestId={}",
                envelope.getMessageType(), envelope.getRequestId());
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        log.debug("Netty channel inactive: {}", context.channel().id().asShortText());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        log.warn("Closing Netty channel because of protocol error", cause);
        context.close();
    }
}
