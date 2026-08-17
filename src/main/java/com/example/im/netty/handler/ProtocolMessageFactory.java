package com.example.im.netty.handler;

import com.example.im.netty.protocol.ImProtocol.ConnectAck;
import com.example.im.netty.protocol.ImProtocol.ErrorPayload;
import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import com.example.im.netty.protocol.ImProtocol.PushMessage;
import com.example.im.netty.protocol.ImProtocol.SendResult;
import com.example.im.message.service.SendMessageResult;
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

    public static MessageEnvelope sendResult(String requestId, SendMessageResult result) {
        SendResult payload = SendResult.newBuilder()
                .setClientMessageId(result.clientMessageId())
                .setMessageId(result.messageId())
                .setConversationId(result.conversationId())
                .setSequence(result.sequence())
                .setCreatedAt(result.createdAt())
                .build();
        return MessageEnvelope.newBuilder()
                .setMessageType(MessageEnvelope.MessageType.SEND_RESULT)
                .setRequestId(requestId)
                .setTimestamp(System.currentTimeMillis())
                .setPayload(payload.toByteString())
                .build();
    }

    public static MessageEnvelope pushMessage(SendMessageResult result) {
        PushMessage payload = PushMessage.newBuilder()
                .setClientMessageId(result.clientMessageId())
                .setMessageId(result.messageId())
                .setConversationId(result.conversationId())
                .setSequence(result.sequence())
                .setSenderId(result.senderId())
                .setReceiverId(result.receiverId())
                .setContent(result.content())
                .setMessageType(result.messageType())
                .setCreatedAt(result.createdAt())
                .build();
        return MessageEnvelope.newBuilder()
                .setMessageType(MessageEnvelope.MessageType.PUSH_MESSAGE)
                .setRequestId("")
                .setTimestamp(System.currentTimeMillis())
                .setPayload(payload.toByteString())
                .build();
    }
}
