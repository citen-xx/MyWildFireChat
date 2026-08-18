package com.example.im.netty.handler;

import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import com.example.im.netty.session.SessionManager;
import com.example.im.route.ConnectionRouteService;
import com.example.im.route.NoopRouteRegistry;
import com.example.im.route.ServerProperties;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatHandlerTest {

    @Test
    void pingShouldProducePongWithSameRequestId() {
        ServerProperties properties = new ServerProperties();
        properties.setId("test-server");
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler(
                new SessionManager(),
                new ConnectionRouteService(new NoopRouteRegistry(), properties)));
        MessageEnvelope ping = MessageEnvelope.newBuilder()
                .setMessageType(MessageEnvelope.MessageType.PING)
                .setRequestId("req-1")
                .setTimestamp(1L)
                .build();

        channel.writeInbound(ping);
        MessageEnvelope pong = channel.readOutbound();

        assertThat(pong.getMessageType()).isEqualTo(MessageEnvelope.MessageType.PONG);
        assertThat(pong.getRequestId()).isEqualTo("req-1");
        assertThat(pong.getTimestamp()).isPositive();
        channel.finishAndReleaseAll();
    }
}
