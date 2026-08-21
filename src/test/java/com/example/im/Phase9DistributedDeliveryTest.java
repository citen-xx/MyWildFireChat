package com.example.im;

import com.example.im.conversation.service.ConversationService;
import com.example.im.message.ack.AckProperties;
import com.example.im.message.ack.AckRetryScheduler;
import com.example.im.message.ack.AckService;
import com.example.im.message.ack.InMemoryPendingAckRepository;
import com.example.im.message.ack.PendingAck;
import com.example.im.message.service.MessageService;
import com.example.im.message.service.SendMessageResult;
import com.example.im.mq.InMemoryRelayDeliveryDeduplicator;
import com.example.im.mq.NoopRemoteMessageRelayPublisher;
import com.example.im.mq.RabbitMqRemoteMessageRelayListener;
import com.example.im.mq.RemoteMessageRelayEvent;
import com.example.im.mq.RemoteMessageRelayPublisher;
import com.example.im.mq.RelayDeliveryDeduplicator;
import com.example.im.netty.session.ClientConnection;
import com.example.im.netty.session.SessionManager;
import com.example.im.route.ConnectionLocation;
import com.example.im.route.ConnectionLocator;
import com.example.im.route.ConnectionRoute;
import com.example.im.route.NoopRouteRegistry;
import com.example.im.route.RouteRegistry;
import com.example.im.route.ServerProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Phase9DistributedDeliveryTest {

    @Test
    void sameEventIdShouldDeduplicateButDifferentEventIdRetryShouldDeliverAgain() {
        SessionManager sessionManager = new SessionManager();
        RecordingConnection connection = new RecordingConnection("tcp-bob-1");
        sessionManager.bind(1002L, "bob-web", connection);

        ServerProperties serverProperties = new ServerProperties();
        serverProperties.setId("server-1");

        AckProperties ackProperties = new AckProperties();
        ackProperties.setRetryEnabled(false);

        AckService ackService = new AckService(new InMemoryPendingAckRepository(), ackProperties);
        ConnectionLocator connectionLocator = new ConnectionLocator(new NoopRouteRegistry(), sessionManager, serverProperties);
        RelayDeliveryDeduplicator deduplicator = new InMemoryRelayDeliveryDeduplicator();
        RabbitMqRemoteMessageRelayListener listener = new RabbitMqRemoteMessageRelayListener(
                sessionManager,
                ackService,
                deduplicator,
                serverProperties,
                connectionLocator,
                new NoopRemoteMessageRelayPublisher(),
                ackProperties);

        SendMessageResult message = messageResult();
        RemoteMessageRelayEvent first = new RemoteMessageRelayEvent(
                "msg-1|1002|bob-web",
                "event-1",
                message.messageId(),
                message.clientMessageId(),
                message.conversationId(),
                message.sequence(),
                message.senderId(),
                message.receiverId(),
                message.content(),
                message.messageType(),
                message.createdAt(),
                1002L,
                "bob-web",
                connection.id(),
                "server-1",
                "server-0",
                0,
                0);
        RemoteMessageRelayEvent duplicateSameEvent = new RemoteMessageRelayEvent(
                first.getDeliveryId(),
                "event-1",
                first.getMessageId(),
                first.getClientMessageId(),
                first.getConversationId(),
                first.getSequence(),
                first.getSenderId(),
                first.getReceiverId(),
                first.getContent(),
                first.getMessageType(),
                first.getCreatedAt(),
                first.getTargetUserId(),
                first.getTargetDeviceId(),
                first.getTargetConnectionId(),
                first.getTargetServerId(),
                first.getSourceServerId(),
                first.getAttempt(),
                first.getHopCount());
        RemoteMessageRelayEvent retryDifferentEvent = new RemoteMessageRelayEvent(
                first.getDeliveryId(),
                "event-2",
                first.getMessageId(),
                first.getClientMessageId(),
                first.getConversationId(),
                first.getSequence(),
                first.getSenderId(),
                first.getReceiverId(),
                first.getContent(),
                first.getMessageType(),
                first.getCreatedAt(),
                first.getTargetUserId(),
                first.getTargetDeviceId(),
                first.getTargetConnectionId(),
                first.getTargetServerId(),
                first.getSourceServerId(),
                1,
                1);

        listener.onMessage(first);
        listener.onMessage(duplicateSameEvent);
        listener.onMessage(retryDifferentEvent);

        assertThat(connection.messages()).hasSize(2);
        assertThat(connection.messages()).extracting(SendMessageResult::messageId)
                .containsExactly("msg-1", "msg-1");
    }

    @Test
    void ackShouldRespectConnectionOwnership() {
        InMemoryPendingAckRepository repository = new InMemoryPendingAckRepository();
        AckProperties properties = new AckProperties();
        properties.setRetryEnabled(true);
        AckService ackService = new AckService(repository, properties);

        repository.save(new PendingAck(
                1002L,
                "bob-web",
                "msg-2",
                System.currentTimeMillis(),
                0,
                "tcp-old",
                "server-1",
                "msg-2|1002|bob-web",
                0));

        ackService.acknowledge(1002L, "bob-web", "msg-2", "tcp-new");
        assertThat(repository.exists(1002L, "bob-web", "msg-2")).isTrue();

        ackService.acknowledge(1002L, "bob-web", "msg-2", "tcp-old");
        assertThat(repository.exists(1002L, "bob-web", "msg-2")).isFalse();
    }

    @Test
    void retryShouldHandoffRemoteDeliveryAndClearOldOwnerPending() {
        InMemoryPendingAckRepository repository = new InMemoryPendingAckRepository();
        AckProperties ackProperties = new AckProperties();
        ackProperties.setRetryEnabled(true);
        ackProperties.setMaxRelayHops(2);
        AckService ackService = new AckService(repository, ackProperties);

        SessionManager sessionManager = new SessionManager();
        ServerProperties serverProperties = new ServerProperties();
        serverProperties.setId("server-1");

        RouteRegistry routeRegistry = mock(RouteRegistry.class);
        ConnectionRoute remoteRoute = new ConnectionRoute(1002L, "bob-web", "server-2", "tcp-bob-2", System.currentTimeMillis());
        when(routeRegistry.find(1002L, "bob-web")).thenReturn(Optional.of(remoteRoute));
        when(routeRegistry.findUserDevices(1002L)).thenReturn(List.of(remoteRoute));

        ConnectionLocator connectionLocator = new ConnectionLocator(routeRegistry, sessionManager, serverProperties);
        MessageService messageService = mock(MessageService.class);
        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.isMember(anyLong(), anyLong())).thenReturn(true);

        SendMessageResult message = messageResult();
        when(messageService.findResultByMessageId("msg-3")).thenReturn(Optional.of(message));
        RemoteMessageRelayPublisher relayPublisher = mock(RemoteMessageRelayPublisher.class);
        when(relayPublisher.publish(any(), any(), anyString(), anyInt(), anyInt())).thenReturn(true);

        AckRetryScheduler scheduler = new AckRetryScheduler(
                repository,
                ackService,
                messageService,
                sessionManager,
                ackProperties,
                conversationService,
                connectionLocator,
                relayPublisher,
                serverProperties);

        repository.save(new PendingAck(
                1002L,
                "bob-web",
                "msg-3",
                System.currentTimeMillis() - 1,
                0,
                "tcp-bob-1",
                "server-1",
                "msg-3|1002|bob-web",
                0));

        scheduler.scanOnce();

        ArgumentCaptor<String> deliveryIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(relayPublisher).publish(any(), any(), deliveryIdCaptor.capture(), anyInt(), anyInt());
        assertThat(deliveryIdCaptor.getValue()).isEqualTo("msg-3|1002|bob-web");
        assertThat(repository.exists(1002L, "bob-web", "msg-3")).isFalse();
    }

    private SendMessageResult messageResult() {
        return new SendMessageResult(
                "client-msg",
                "msg-1",
                1L,
                1L,
                1001L,
                1002L,
                "hello",
                "TEXT",
                System.currentTimeMillis(),
                false);
    }

    private static class RecordingConnection implements ClientConnection {

        private final String id;
        private final List<SendMessageResult> messages = new CopyOnWriteArrayList<>();

        private RecordingConnection(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void sendPush(SendMessageResult message) {
            messages.add(message);
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isActive() {
            return true;
        }

        private List<SendMessageResult> messages() {
            return List.copyOf(messages);
        }
    }
}
