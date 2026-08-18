package com.example.im.netty.handler;

import com.example.im.auth.security.JwtClaims;
import com.example.im.auth.security.JwtService;
import com.example.im.common.exception.AuthException;
import com.example.im.netty.protocol.ImProtocol.ConnectRequest;
import com.example.im.netty.session.ImSession;
import com.example.im.netty.session.SessionManager;
import com.example.im.route.ConnectionRouteService;
import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

@Service
public class NettyAuthService {

    private final JwtService jwtService;
    private final SessionManager sessionManager;
    private final ConnectionRouteService routeService;

    public NettyAuthService(JwtService jwtService, SessionManager sessionManager, ConnectionRouteService routeService) {
        this.jwtService = jwtService;
        this.sessionManager = sessionManager;
        this.routeService = routeService;
    }

    public ImSession authenticate(Channel channel, ConnectRequest request) {
        if (request == null || request.getDeviceId().isBlank()) {
            throw new AuthException("INVALID_CONNECT", "deviceId is required");
        }
        JwtClaims claims = jwtService.verify(request.getToken());
        ImSession session = sessionManager.bind(claims.userId(), request.getDeviceId(), channel);
        routeService.register(session);
        return session;
    }
}
