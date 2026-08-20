package com.example.im;

import com.example.im.message.ack.AckProperties;
import com.example.im.message.ack.AckService;
import com.example.im.message.ack.InMemoryPendingAckRepository;
import com.example.im.message.service.MessageDeliveryService;
import com.example.im.message.service.SendMessageResult;
import com.example.im.mq.NoopRemoteMessageRelayPublisher;
import com.example.im.netty.session.ClientConnection;
import com.example.im.route.ConnectionLocator;
import com.example.im.route.NoopRouteRegistry;
import com.example.im.route.ServerProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class Phase10ReliableDeliveryRaceTest {

    @Test
    void fastAckDuringPushShouldNotLeavePendingAckBehind() {
        InMemoryPendingAckRepository repository = new InMemoryPendingAckRepository();
        AckProperties properties = new AckProperties();
        properties.setRetryEnabled(true);
        AckService ackService = new AckService(repository, properties);

        ServerProperties serverProperties = new ServerProperties();
        serverProperties.setId("phase10-server");
        SessionConnection connection = new SessionConnection("phase10-connection", ackService);
        com.example.im.netty.session.SessionManager sessionManager =
                new com.example.im.netty.session.SessionManager();
        sessionManager.bind(1002L, "bob-web", connection);

        MessageDeliveryService deliveryService = new MessageDeliveryService(
                new ConnectionLocator(new NoopRouteRegistry(), sessionManager, serverProperties),
                ackService,
                new NoopRemoteMessageRelayPublisher());

        SendMessageResult message = new SendMessageResult(
                "client-phase10",
                "message-phase10",
                9001L,
                1L,
                1001L,
                1002L,
                "hello",
                "TEXT",
                System.currentTimeMillis(),
                false);

        assertThat(deliveryService.pushToUserDevices(message, List.of(1002L))).isEqualTo(1);
        assertThat(connection.pushedMessageId()).isEqualTo(message.messageId());
        assertThat(repository.exists(1002L, "bob-web", message.messageId())).isFalse();
    }

    private static final class SessionConnection implements ClientConnection {

        private final String id;
        private final AckService ackService;
        private final AtomicReference<String> pushedMessageId = new AtomicReference<>();

        private SessionConnection(String id, AckService ackService) {
            this.id = id;
            this.ackService = ackService;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void sendPush(SendMessageResult message) {
            pushedMessageId.set(message.messageId());
            ackService.acknowledge(1002L, "bob-web", message.messageId(), id);
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isActive() {
            return true;
        }

        private String pushedMessageId() {
            return pushedMessageId.get();
        }
    }
}
