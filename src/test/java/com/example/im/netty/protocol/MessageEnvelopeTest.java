package com.example.im.netty.protocol;

import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageEnvelopeTest {

    @Test
    void envelopeShouldRoundTripThroughProtobuf() throws Exception {
        MessageEnvelope source = MessageEnvelope.newBuilder()
                .setMessageType(MessageEnvelope.MessageType.SEND_MESSAGE)
                .setRequestId("request-1")
                .setTimestamp(123L)
                .build();

        MessageEnvelope parsed = MessageEnvelope.parseFrom(source.toByteArray());

        assertThat(parsed).isEqualTo(source);
    }
}
