package com.example.im.netty.handler;

import com.example.im.message.service.MessageDeliveryService;
import com.example.im.message.service.SendMessageCommand;
import com.example.im.message.service.MessageService;
import com.example.im.message.service.SendMessageResult;
import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import com.example.im.netty.protocol.ImProtocol.SendMessageRequest;
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

    public MessageHandler(
            SessionManager sessionManager,
            ObjectProvider<MessageService> messageServiceProvider,
            ObjectProvider<MessageDeliveryService> deliveryServiceProvider) {
        this.sessionManager = sessionManager;
        this.messageServiceProvider = messageServiceProvider;
        this.deliveryServiceProvider = deliveryServiceProvider;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, MessageEnvelope envelope) {
        if (envelope.getMessageType() == MessageEnvelope.MessageType.SEND_MESSAGE) {
            handleSendMessage(context, envelope);
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
                    request.getContent(),
                    request.getMessageType());
            SendMessageResult result = messageService.sendSingleMessage(session.userId(), command);
            context.writeAndFlush(ProtocolMessageFactory.sendResult(envelope.getRequestId(), result));
            deliveryService.pushToLocalReceiverDevices(result);
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
