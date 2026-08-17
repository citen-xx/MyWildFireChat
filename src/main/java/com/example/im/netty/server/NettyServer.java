package com.example.im.netty.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class NettyServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

    private final NettyProperties properties;
    private final NettyServerInitializer initializer;
    private final AtomicBoolean running = new AtomicBoolean();

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyServer(NettyProperties properties, NettyServerInitializer initializer) {
        this.properties = properties;
        this.initializer = initializer;
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }

        bossGroup = new NioEventLoopGroup(properties.getBossThreads());
        workerGroup = new NioEventLoopGroup(properties.getWorkerThreads());

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(initializer)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, properties.isKeepAlive());

            serverChannel = bootstrap.bind(properties.getHost(), properties.getPort())
                    .syncUninterruptibly()
                    .channel();
            running.set(true);
            log.info("IM Netty server started on {}:{}", properties.getHost(), properties.getPort());
        } catch (RuntimeException exception) {
            stop();
            throw exception;
        }
    }

    @Override
    public synchronized void stop() {
        if (!running.get() && bossGroup == null && workerGroup == null) {
            return;
        }

        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup = null;
        }
        running.set(false);
        log.info("IM Netty server stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    public int boundPort() {
        if (serverChannel == null || serverChannel.localAddress() == null) {
            return -1;
        }
        return ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
