package com.example.im.netty.handler;

import com.example.im.netty.session.SessionManager;
import com.example.im.route.ConnectionRouteService;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.springframework.stereotype.Component;

@Component
@Sharable
public class SessionCleanupHandler extends ChannelInboundHandlerAdapter {

    private final SessionManager sessionManager;
    private final ConnectionRouteService routeService;

    public SessionCleanupHandler(SessionManager sessionManager, ConnectionRouteService routeService) {
        this.sessionManager = sessionManager;
        this.routeService = routeService;
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        sessionManager.remove(context.channel()).ifPresent(routeService::remove);
        super.channelInactive(context);
    }
}
