package com.example.im.netty.handler;

import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatHandlerTest {

    @Test
    void pingShouldProducePongWithSameRequestId() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());
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
