package com.example.im.netty.handler;

import com.example.im.auth.security.JwtProperties;
import com.example.im.auth.security.JwtService;
import com.example.im.netty.protocol.ImProtocol.ConnectAck;
import com.example.im.netty.protocol.ImProtocol.ConnectRequest;
import com.example.im.netty.protocol.ImProtocol.ErrorPayload;
import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import com.example.im.netty.session.SessionManager;
import com.example.im.route.ConnectionRouteService;
import com.example.im.route.NoopRouteRegistry;
import com.example.im.route.ServerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthHandlerTest {

    @Test
    void connectShouldBindSessionAndReturnAck() throws Exception {
        SessionManager sessionManager = new SessionManager();
        JwtService jwtService = jwtService();
        AuthHandler authHandler = new AuthHandler(
                new NettyAuthService(jwtService, sessionManager, routeService()),
                sessionManager);
        EmbeddedChannel channel = new EmbeddedChannel(authHandler);

        MessageEnvelope connect = connectEnvelope(jwtService.generate(1001L).token(), "web");
        channel.writeInbound(connect);

        MessageEnvelope response = channel.readOutbound();
        ConnectAck ack = ConnectAck.parseFrom(response.getPayload());

        assertThat(response.getMessageType()).isEqualTo(MessageEnvelope.MessageType.CONNECT_ACK);
        assertThat(ack.getUserId()).isEqualTo(1001L);
        assertThat(ack.getDeviceId()).isEqualTo("web");
        assertThat(sessionManager.findChannel(1001L, "web")).contains(channel);
        channel.finishAndReleaseAll();
    }

    @Test
    void messageBeforeConnectShouldReturnErrorAndClose() throws Exception {
        SessionManager sessionManager = new SessionManager();
        AuthHandler authHandler = new AuthHandler(
                new NettyAuthService(jwtService(), sessionManager, routeService()),
                sessionManager);
        EmbeddedChannel channel = new EmbeddedChannel(authHandler);

        channel.writeInbound(MessageEnvelope.newBuilder()
                .setMessageType(MessageEnvelope.MessageType.PING)
                .setRequestId("ping-1")
                .build());

        MessageEnvelope response = channel.readOutbound();
        ErrorPayload error = ErrorPayload.parseFrom(response.getPayload());

        assertThat(response.getMessageType()).isEqualTo(MessageEnvelope.MessageType.ERROR);
        assertThat(error.getCode()).isEqualTo("UNAUTHENTICATED");
        assertThat(channel.isOpen()).isFalse();
    }

    private MessageEnvelope connectEnvelope(String token, String deviceId) {
        ConnectRequest request = ConnectRequest.newBuilder()
                .setToken(token)
                .setDeviceId(deviceId)
                .build();
        return MessageEnvelope.newBuilder()
                .setMessageType(MessageEnvelope.MessageType.CONNECT)
                .setRequestId("connect-1")
                .setPayload(request.toByteString())
                .build();
    }

    private JwtService jwtService() {
        JwtProperties properties = new JwtProperties();
        properties.setIssuer("test-im");
        properties.setSecret("test-secret-test-secret-test-secret");
        properties.setTtlSeconds(3600);
        return new JwtService(properties, new ObjectMapper());
    }

    private ConnectionRouteService routeService() {
        ServerProperties properties = new ServerProperties();
        properties.setId("test-server");
        return new ConnectionRouteService(new NoopRouteRegistry(), properties);
    }
}
