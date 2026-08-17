package com.example.im.netty.handler;

import com.example.im.netty.session.SessionManager;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.springframework.stereotype.Component;

@Component
@Sharable
public class SessionCleanupHandler extends ChannelInboundHandlerAdapter {

    private final SessionManager sessionManager;

    public SessionCleanupHandler(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        sessionManager.remove(context.channel());
        super.channelInactive(context);
    }
}
