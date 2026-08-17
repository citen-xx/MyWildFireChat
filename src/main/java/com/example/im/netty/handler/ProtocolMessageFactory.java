package com.example.im.netty.handler;

import com.example.im.netty.protocol.ImProtocol.ConnectAck;
import com.example.im.netty.protocol.ImProtocol.ErrorPayload;
import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import com.google.protobuf.ByteString;

public final class ProtocolMessageFactory {

    private ProtocolMessageFactory() {
    }

    public static MessageEnvelope connectAck(String requestId, Long userId, String deviceId) {
        ConnectAck ack = ConnectAck.newBuilder()
                .setUserId(userId)
                .setDeviceId(deviceId)
                .setServerTime(System.currentTimeMillis())
                .build();
        return MessageEnvelope.newBuilder()
                .setMessageType(MessageEnvelope.MessageType.CONNECT_ACK)
                .setRequestId(requestId)
                .setTimestamp(System.currentTimeMillis())
                .setPayload(ack.toByteString())
                .build();
    }

    public static MessageEnvelope error(String requestId, String code, String message) {
        ErrorPayload payload = ErrorPayload.newBuilder()
                .setCode(code)
                .setMessage(message)
                .build();
        return MessageEnvelope.newBuilder()
                .setMessageType(MessageEnvelope.MessageType.ERROR)
                .setRequestId(requestId == null ? "" : requestId)
                .setTimestamp(System.currentTimeMillis())
                .setPayload(ByteString.copyFrom(payload.toByteArray()))
                .build();
    }
}
