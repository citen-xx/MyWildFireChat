package com.example.im.netty.server;

import io.netty.util.concurrent.DefaultEventExecutorGroup;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

@Component
public class NettyBusinessExecutorGroup {

    private final DefaultEventExecutorGroup delegate =
            new DefaultEventExecutorGroup(Math.max(4, Runtime.getRuntime().availableProcessors()));

    public DefaultEventExecutorGroup delegate() {
        return delegate;
    }

    @PreDestroy
    void shutdown() {
        delegate.shutdownGracefully().syncUninterruptibly();
    }
}
