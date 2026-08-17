package com.example.im.netty.server;

import com.example.im.netty.handler.AuthHandler;
import com.example.im.netty.handler.HeartbeatHandler;
import com.example.im.netty.handler.MessageHandler;
import com.example.im.netty.handler.SessionCleanupHandler;
import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class NettyServerInitializer extends ChannelInitializer<SocketChannel> {

    private final NettyProperties properties;
    private final AuthHandler authHandler;
    private final HeartbeatHandler heartbeatHandler;
    private final MessageHandler messageHandler;
    private final SessionCleanupHandler sessionCleanupHandler;

    public NettyServerInitializer(
            NettyProperties properties,
            AuthHandler authHandler,
            HeartbeatHandler heartbeatHandler,
            MessageHandler messageHandler,
            SessionCleanupHandler sessionCleanupHandler) {
        this.properties = properties;
        this.authHandler = authHandler;
        this.heartbeatHandler = heartbeatHandler;
        this.messageHandler = messageHandler;
        this.sessionCleanupHandler = sessionCleanupHandler;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
                properties.getMaxFrameLength(), 0, 4, 0, 4));
        pipeline.addLast("protobufDecoder", new ProtobufDecoder(MessageEnvelope.getDefaultInstance()));
        pipeline.addLast("frameEncoder", new LengthFieldPrepender(4));
        pipeline.addLast("protobufEncoder", new ProtobufEncoder());
        pipeline.addLast("idleStateHandler", new IdleStateHandler(
                properties.getReaderIdleSeconds(), 0, 0, TimeUnit.SECONDS));
        pipeline.addLast("authHandler", authHandler);
        pipeline.addLast("heartbeatHandler", heartbeatHandler);
        pipeline.addLast("messageHandler", messageHandler);
        pipeline.addLast("sessionCleanupHandler", sessionCleanupHandler);
    }
}
