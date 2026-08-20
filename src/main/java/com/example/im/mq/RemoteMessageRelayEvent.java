package com.example.im.mq;

import com.example.im.message.service.SendMessageResult;

public class RemoteMessageRelayEvent {

    private String deliveryId;
    private String messageId;
    private String clientMessageId;
    private Long conversationId;
    private Long sequence;
    private Long senderId;
    private Long receiverId;
    private String content;
    private String messageType;
    private long createdAt;
    private Long targetUserId;
    private String targetDeviceId;
    private String targetConnectionId;
    private String targetServerId;
    private String sourceServerId;

    public RemoteMessageRelayEvent() {
    }

    public RemoteMessageRelayEvent(
            String deliveryId,
            String messageId,
            String clientMessageId,
            Long conversationId,
            Long sequence,
            Long senderId,
            Long receiverId,
            String content,
            String messageType,
            long createdAt,
            Long targetUserId,
            String targetDeviceId,
            String targetConnectionId,
            String targetServerId,
            String sourceServerId) {
        this.deliveryId = deliveryId;
        this.messageId = messageId;
        this.clientMessageId = clientMessageId;
        this.conversationId = conversationId;
        this.sequence = sequence;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.content = content;
        this.messageType = messageType;
        this.createdAt = createdAt;
        this.targetUserId = targetUserId;
        this.targetDeviceId = targetDeviceId;
        this.targetConnectionId = targetConnectionId;
        this.targetServerId = targetServerId;
        this.sourceServerId = sourceServerId;
    }

    public static RemoteMessageRelayEvent of(
            String deliveryId,
            SendMessageResult message,
            Long targetUserId,
            String targetDeviceId,
            String targetConnectionId,
            String targetServerId,
            String sourceServerId) {
        return new RemoteMessageRelayEvent(
                deliveryId,
                message.messageId(),
                message.clientMessageId(),
                message.conversationId(),
                message.sequence(),
                message.senderId(),
                message.receiverId(),
                message.content(),
                message.messageType(),
                message.createdAt(),
                targetUserId,
                targetDeviceId,
                targetConnectionId,
                targetServerId,
                sourceServerId);
    }

    public static String stableDeliveryId(
            String sourceServerId,
            SendMessageResult message,
            Long targetUserId,
            String targetDeviceId,
            String targetConnectionId) {
        return String.join("|",
                sourceServerId == null ? "" : sourceServerId,
                message.messageId() == null ? "" : message.messageId(),
                String.valueOf(targetUserId == null ? 0L : targetUserId),
                targetDeviceId == null ? "" : targetDeviceId,
                targetConnectionId == null ? "" : targetConnectionId);
    }

    public SendMessageResult toMessageResult() {
        return new SendMessageResult(
                clientMessageId,
                messageId,
                conversationId,
                sequence,
                senderId,
                receiverId,
                content,
                messageType,
                createdAt,
                false);
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public void setDeliveryId(String deliveryId) {
        this.deliveryId = deliveryId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getClientMessageId() {
        return clientMessageId;
    }

    public void setClientMessageId(String clientMessageId) {
        this.clientMessageId = clientMessageId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Long getSequence() {
        return sequence;
    }

    public void setSequence(Long sequence) {
        this.sequence = sequence;
    }

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public Long getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(Long receiverId) {
        this.receiverId = receiverId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getTargetUserId() {
        return targetUserId;
    }

    public void setTargetUserId(Long targetUserId) {
        this.targetUserId = targetUserId;
    }

    public String getTargetDeviceId() {
        return targetDeviceId;
    }

    public void setTargetDeviceId(String targetDeviceId) {
        this.targetDeviceId = targetDeviceId;
    }

    public String getTargetConnectionId() {
        return targetConnectionId;
    }

    public void setTargetConnectionId(String targetConnectionId) {
        this.targetConnectionId = targetConnectionId;
    }

    public String getTargetServerId() {
        return targetServerId;
    }

    public void setTargetServerId(String targetServerId) {
        this.targetServerId = targetServerId;
    }

    public String getSourceServerId() {
        return sourceServerId;
    }

    public void setSourceServerId(String sourceServerId) {
        this.sourceServerId = sourceServerId;
    }
}
