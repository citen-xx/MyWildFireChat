package com.example.im.netty.handler;

import com.example.im.message.ack.AckService;
import com.example.im.message.service.MessageDeliveryService;
import com.example.im.message.service.SendMessageCommand;
import com.example.im.message.service.MessageService;
import com.example.im.message.service.SendMessageResult;
import com.example.im.message.sync.SyncCommand;
import com.example.im.message.sync.SyncResult;
import com.example.im.message.sync.SyncService;
import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import com.example.im.netty.protocol.ImProtocol.MessageAck;
import com.example.im.netty.protocol.ImProtocol.SendMessageRequest;
import com.example.im.netty.protocol.ImProtocol.SyncRequest;
import com.example.im.netty.session.ImSession;
import com.example.im.netty.session.SessionManager;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@Sharable
public class MessageHandler extends SimpleChannelInboundHandler<MessageEnvelope> {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private final SessionManager sessionManager;
    private final ObjectProvider<MessageService> messageServiceProvider;
    private final ObjectProvider<MessageDeliveryService> deliveryServiceProvider;
    private final ObjectProvider<AckService> ackServiceProvider;
    private final ObjectProvider<SyncService> syncServiceProvider;

    public MessageHandler(
            SessionManager sessionManager,
            ObjectProvider<MessageService> messageServiceProvider,
            ObjectProvider<MessageDeliveryService> deliveryServiceProvider,
            ObjectProvider<AckService> ackServiceProvider,
            ObjectProvider<SyncService> syncServiceProvider) {
        this.sessionManager = sessionManager;
        this.messageServiceProvider = messageServiceProvider;
        this.deliveryServiceProvider = deliveryServiceProvider;
        this.ackServiceProvider = ackServiceProvider;
        this.syncServiceProvider = syncServiceProvider;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, MessageEnvelope envelope) {
        if (envelope.getMessageType() == MessageEnvelope.MessageType.SEND_MESSAGE) {
            handleSendMessage(context, envelope);
            return;
        }

        if (envelope.getMessageType() == MessageEnvelope.MessageType.MESSAGE_ACK) {
            handleMessageAck(context, envelope);
            return;
        }

        if (envelope.getMessageType() == MessageEnvelope.MessageType.SYNC_REQUEST) {
            handleSyncRequest(context, envelope);
            return;
        }

        log.debug("Received protocol message type={}, requestId={}",
                envelope.getMessageType(), envelope.getRequestId());
    }

    private void handleSendMessage(ChannelHandlerContext context, MessageEnvelope envelope) {
        try {
            ImSession session = sessionManager.getSession(context.channel())
                    .orElseThrow(() -> new IllegalStateException("channel is not authenticated"));
            MessageService messageService = messageServiceProvider.getIfAvailable();
            MessageDeliveryService deliveryService = deliveryServiceProvider.getIfAvailable();
            if (messageService == null || deliveryService == null) {
                context.writeAndFlush(ProtocolMessageFactory.error(
                        envelope.getRequestId(),
                        "CHAT_DISABLED",
                        "message handling is disabled"));
                return;
            }
            SendMessageRequest request = SendMessageRequest.parseFrom(envelope.getPayload());
            SendMessageCommand command = new SendMessageCommand(
                    request.getClientMessageId(),
                    request.getReceiverId(),
                    request.getGroupId(),
                    request.getConversationType(),
                    request.getContent(),
                    request.getMessageType());
            SendMessageResult result = messageService.sendMessage(session.userId(), command);
            context.writeAndFlush(ProtocolMessageFactory.sendResult(envelope.getRequestId(), result));
            if (!result.duplicate()) {
                deliveryService.pushToUserDevices(
                        result,
                        messageService.deliveryTargets(session.userId(), command, result));
            }
        } catch (IllegalArgumentException exception) {
            context.writeAndFlush(ProtocolMessageFactory.error(
                    envelope.getRequestId(),
                    "INVALID_SEND_MESSAGE",
                    exception.getMessage()));
        } catch (Exception exception) {
            log.warn("Failed to handle SEND_MESSAGE", exception);
            context.writeAndFlush(ProtocolMessageFactory.error(
                    envelope.getRequestId(),
                    "SEND_MESSAGE_FAILED",
                    "failed to persist message"));
        }
    }

    private void handleMessageAck(ChannelHandlerContext context, MessageEnvelope envelope) {
        try {
            ImSession session = sessionManager.getSession(context.channel())
                    .orElseThrow(() -> new IllegalStateException("channel is not authenticated"));
            AckService ackService = ackServiceProvider.getIfAvailable();
            if (ackService == null) {
                context.writeAndFlush(ProtocolMessageFactory.error(
                        envelope.getRequestId(),
                        "ACK_DISABLED",
                        "message acknowledgement is disabled"));
                return;
            }
            MessageAck ack = MessageAck.parseFrom(envelope.getPayload());
            ackService.acknowledge(
                    session.userId(),
                    session.deviceId(),
                    ack.getMessageId(),
                    session.connectionId());
        } catch (IllegalArgumentException exception) {
            context.writeAndFlush(ProtocolMessageFactory.error(
                    envelope.getRequestId(),
                    "INVALID_MESSAGE_ACK",
                    exception.getMessage()));
        } catch (Exception exception) {
            log.warn("Failed to handle MESSAGE_ACK", exception);
            context.writeAndFlush(ProtocolMessageFactory.error(
                    envelope.getRequestId(),
                    "MESSAGE_ACK_FAILED",
                    "failed to process acknowledgement"));
        }
    }

    private void handleSyncRequest(ChannelHandlerContext context, MessageEnvelope envelope) {
        try {
            ImSession session = sessionManager.getSession(context.channel())
                    .orElseThrow(() -> new IllegalStateException("channel is not authenticated"));
            SyncService syncService = syncServiceProvider.getIfAvailable();
            if (syncService == null) {
                context.writeAndFlush(ProtocolMessageFactory.error(
                        envelope.getRequestId(),
                        "SYNC_DISABLED",
                        "message sync is disabled"));
                return;
            }
            SyncRequest request = SyncRequest.parseFrom(envelope.getPayload());
            SyncCommand command = new SyncCommand(
                    request.getConversationId(),
                    request.getLastSequence(),
                    request.hasLimit() ? request.getLimit() : null);
            SyncResult result = syncService.sync(session.userId(), session.deviceId(), command);
            context.writeAndFlush(ProtocolMessageFactory.syncResponse(envelope.getRequestId(), result));
            if (!result.hasMore()) {
                context.writeAndFlush(ProtocolMessageFactory.syncComplete(
                        envelope.getRequestId(),
                        result.conversationId(),
                        result.nextSequence()));
            }
        } catch (IllegalArgumentException exception) {
            context.writeAndFlush(ProtocolMessageFactory.error(
                    envelope.getRequestId(),
                    "INVALID_SYNC_REQUEST",
                    exception.getMessage()));
        } catch (Exception exception) {
            log.warn("Failed to handle SYNC_REQUEST", exception);
            context.writeAndFlush(ProtocolMessageFactory.error(
                    envelope.getRequestId(),
                    "SYNC_REQUEST_FAILED",
                    "failed to sync messages"));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        log.debug("Netty channel inactive: {}", context.channel().id().asShortText());
        super.channelInactive(context);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        log.warn("Closing Netty channel because of protocol error", cause);
        context.close();
    }
}
