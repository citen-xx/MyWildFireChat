package com.example.im.netty.handler;

import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import com.example.im.common.exception.AuthException;
import com.example.im.netty.protocol.ImProtocol.ConnectRequest;
import com.example.im.netty.session.ImSession;
import com.example.im.netty.session.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandler.Sharable;
import org.springframework.stereotype.Component;

@Component
@Sharable
public class AuthHandler extends ChannelInboundHandlerAdapter {

    private final NettyAuthService nettyAuthService;
    private final SessionManager sessionManager;

    public AuthHandler(NettyAuthService nettyAuthService, SessionManager sessionManager) {
        this.nettyAuthService = nettyAuthService;
        this.sessionManager = sessionManager;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!(message instanceof MessageEnvelope envelope)) {
            context.fireChannelRead(message);
            return;
        }

        if (envelope.getMessageType() == MessageEnvelope.MessageType.CONNECT) {
            handleConnect(context, envelope);
            return;
        }

        if (!sessionManager.isAuthenticated(context.channel())) {
            context.writeAndFlush(ProtocolMessageFactory.error(
                    envelope.getRequestId(),
                    "UNAUTHENTICATED",
                    "CONNECT is required before other messages"))
                    .addListener(future -> context.close());
            return;
        }

        context.fireChannelRead(envelope);
    }

    private void handleConnect(ChannelHandlerContext context, MessageEnvelope envelope) {
        if (sessionManager.isAuthenticated(context.channel())) {
            context.writeAndFlush(ProtocolMessageFactory.error(
                    envelope.getRequestId(),
                    "DUPLICATE_CONNECT",
                    "channel is already authenticated"));
            return;
        }

        try {
            ConnectRequest request = ConnectRequest.parseFrom(envelope.getPayload());
            ImSession session = nettyAuthService.authenticate(context.channel(), request);
            context.writeAndFlush(ProtocolMessageFactory.connectAck(
                    envelope.getRequestId(),
                    session.userId(),
                    session.deviceId()));
        } catch (AuthException exception) {
            context.writeAndFlush(ProtocolMessageFactory.error(
                    envelope.getRequestId(),
                    exception.getCode(),
                    exception.getMessage()))
                    .addListener(future -> context.close());
        } catch (Exception exception) {
            context.writeAndFlush(ProtocolMessageFactory.error(
                    envelope.getRequestId(),
                    "INVALID_CONNECT",
                    "CONNECT payload is invalid"))
                    .addListener(future -> context.close());
        }
    }
}
