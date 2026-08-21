package com.example.im;

import com.example.im.message.ack.AckProperties;
import com.example.im.message.ack.AckService;
import com.example.im.message.ack.InMemoryPendingAckRepository;
import com.example.im.message.ack.PendingAckRepository;
import com.example.im.message.service.MessageDeliveryService;
import com.example.im.message.service.SendMessageResult;
import com.example.im.mq.RabbitMqProperties;
import com.example.im.mq.RabbitMqRelayConfiguration;
import com.example.im.mq.RabbitMqRemoteMessageRelayListener;
import com.example.im.mq.RabbitMqRemoteMessageRelayPublisher;
import com.example.im.mq.RemoteMessageRelayPublisher;
import com.example.im.mq.RelayDeliveryDeduplicator;
import com.example.im.mq.InMemoryRelayDeliveryDeduplicator;
import com.example.im.mq.RemoteMessageRelayEvent;
import com.example.im.netty.session.ClientConnection;
import com.example.im.netty.session.ImSession;
import com.example.im.netty.session.SessionManager;
import com.example.im.route.ConnectionLocation;
import com.example.im.route.ConnectionLocator;
import com.example.im.route.ConnectionRoute;
import com.example.im.route.NoopRouteRegistry;
import com.example.im.route.ServerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class Phase8RabbitMqRelayIntegrationTest {

    @Test
    void shouldRelayRemoteMessageAndDeduplicateDuplicateDelivery() {
        try (ConfigurableApplicationContext targetContext = startTargetContext();
             ConfigurableApplicationContext sourceContext = startSourceContext()) {
            sourceContext.getBean(ServerProperties.class).setId("phase8-source");
            targetContext.getBean(ServerProperties.class).setId("phase8-target");
            sourceContext.getBean(RabbitMqProperties.class).setExchange("im.message.relay");
            sourceContext.getBean(RabbitMqProperties.class).setQueuePrefix("im.message.relay.");
            targetContext.getBean(RabbitMqProperties.class).setExchange("im.message.relay");
            targetContext.getBean(RabbitMqProperties.class).setQueuePrefix("im.message.relay.");

            declareRelayTopology(sourceContext, false);
            declareRelayTopology(targetContext, true);

            SessionManager targetSessionManager = targetContext.getBean(SessionManager.class);
            RecordingClientConnection bobConnection = new RecordingClientConnection("phase8-target-conn");
            ImSession session = targetSessionManager.bind(1002L, "bob-web", bobConnection);
            assertThat(session.userId()).isEqualTo(1002L);

            MessageDeliveryService messageDeliveryService = sourceContext.getBean(MessageDeliveryService.class);
            RabbitTemplate targetRabbitTemplate = targetContext.getBean(RabbitTemplate.class);
            String targetQueueName = targetContext.getBean(RabbitMqProperties.class)
                    .queueName(targetContext.getBean(ServerProperties.class).getId());
            RabbitMqRemoteMessageRelayListener relayListener =
                    targetContext.getBean(RabbitMqRemoteMessageRelayListener.class);
            SendMessageResult message = new SendMessageResult(
                    "client-msg-1",
                    "msg_phase8_1",
                    9001L,
                    1L,
                    1001L,
                    1002L,
                    "hello from server-1",
                    "TEXT",
                    System.currentTimeMillis(),
                    false);

            messageDeliveryService.pushToUserDevices(message, List.of(1002L));
            RemoteMessageRelayEvent firstEvent = receiveEvent(targetRabbitTemplate, targetQueueName, Duration.ofSeconds(5));
            assertThat(firstEvent).isNotNull();
            relayListener.onMessage(firstEvent);
            awaitTrue(() -> bobConnection.messages().size() == 1, Duration.ofSeconds(3),
                    "first relay should reach target server");
            assertThat(bobConnection.messages()).hasSize(1);
            assertThat(bobConnection.messages().get(0).messageId()).isEqualTo("msg_phase8_1");

            messageDeliveryService.pushToUserDevices(message, List.of(1002L));
            RemoteMessageRelayEvent secondEvent = receiveEvent(targetRabbitTemplate, targetQueueName, Duration.ofSeconds(5));
            assertThat(secondEvent).isNotNull();
            relayListener.onMessage(secondEvent);
            sleep(200);
            assertThat(bobConnection.messages()).hasSize(1);
        }
    }

    private void declareRelayTopology(ConfigurableApplicationContext context, boolean includeQueueBinding) {
        AmqpAdmin admin = context.getBean(AmqpAdmin.class);
        DirectExchange exchange = context.getBean("messageRelayExchange", DirectExchange.class);
        admin.declareExchange(exchange);
        if (includeQueueBinding) {
            ServerProperties properties = context.getBean(ServerProperties.class);
            Queue queue = new Queue("im.message.relay." + properties.getId(), true);
            Binding binding = BindingBuilder.bind(queue).to(exchange).with(properties.getId());
            admin.declareQueue(queue);
            admin.declareBinding(binding);
        }
    }

    private RemoteMessageRelayEvent receiveEvent(
            RabbitTemplate rabbitTemplate,
            String queueName,
            Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Object payload = rabbitTemplate.receiveAndConvert(queueName);
            if (payload instanceof RemoteMessageRelayEvent event) {
                return event;
            }
            if (payload != null) {
                fail("unexpected payload type: " + payload.getClass().getName());
            }
            sleep(50);
        }
        return null;
    }

    private ConfigurableApplicationContext startSourceContext() {
        return new SpringApplicationBuilder(SourceRelayTestConfig.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "im.mq.enabled=true",
                        "im.server.id=phase8-source",
                        "im.server.heartbeat-interval-seconds=60",
                        "im.server.offline-timeout-seconds=120",
                        "im.server.route-ttl-seconds=120",
                        "spring.rabbitmq.host=localhost",
                        "spring.rabbitmq.port=5672",
                        "spring.rabbitmq.username=im",
                        "spring.rabbitmq.password=im",
                        "spring.autoconfigure.exclude="
                                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                                + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
                .run();
    }

    private ConfigurableApplicationContext startTargetContext() {
        return new SpringApplicationBuilder(TargetRelayTestConfig.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "im.mq.enabled=true",
                        "im.server.id=phase8-target",
                        "im.server.heartbeat-interval-seconds=60",
                        "im.server.offline-timeout-seconds=120",
                        "im.server.route-ttl-seconds=120",
                        "spring.rabbitmq.host=localhost",
                        "spring.rabbitmq.port=5672",
                        "spring.rabbitmq.username=im",
                        "spring.rabbitmq.password=im",
                        "spring.autoconfigure.exclude="
                                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                                + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
                .run();
    }

    private void awaitTrue(BooleanSupplier condition, Duration timeout, String message) {
        long deadline = System.nanoTime() + timeout.toNanos();
        AssertionError lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (AssertionError error) {
                lastError = error;
            }
            sleep(50);
        }
        if (lastError != null) {
            throw lastError;
        }
        fail(message);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        }
    }

    private static class RecordingClientConnection implements ClientConnection {

        private final String id;
        private final List<SendMessageResult> messages = new CopyOnWriteArrayList<>();

        private RecordingClientConnection(String id) {
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
            return new ArrayList<>(messages);
        }
    }

    @Configuration
    @Import(RabbitMqRelayConfiguration.class)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            SqlInitializationAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class,
            MybatisPlusAutoConfiguration.class
    })
    static class SourceRelayTestConfig {

        @Bean
        SessionManager sessionManager() {
            return new SessionManager();
        }

        @Bean
        ServerProperties serverProperties() {
            ServerProperties properties = new ServerProperties();
            properties.setId("phase8-source");
            properties.setHeartbeatIntervalSeconds(60);
            properties.setOfflineTimeoutSeconds(120);
            properties.setRouteTtlSeconds(120);
            return properties;
        }

        @Bean
        RabbitMqProperties rabbitMqProperties() {
            RabbitMqProperties properties = new RabbitMqProperties();
            properties.setEnabled(true);
            properties.setExchange("im.message.relay");
            properties.setQueuePrefix("im.message.relay.");
            properties.setDeliveryDedupTtlSeconds(30);
            return properties;
        }

        @Bean
        AckProperties ackProperties() {
            AckProperties properties = new AckProperties();
            properties.setRetryEnabled(false);
            properties.setRedisEnabled(false);
            properties.setRetryDelaysMillis(List.of(3000L, 3000L, 5000L));
            properties.setScanIntervalMillis(1000L);
            properties.setScanLimit(100);
            return properties;
        }

        @Bean
        PendingAckRepository pendingAckRepository() {
            return new InMemoryPendingAckRepository();
        }

        @Bean
        AckService ackService(PendingAckRepository pendingAckRepository, AckProperties properties) {
            return new AckService(pendingAckRepository, properties);
        }

        @Bean
        ConnectionLocator connectionLocator(SessionManager sessionManager, ServerProperties serverProperties) {
            ConnectionRoute route = new ConnectionRoute(1002L, "bob-web", "phase8-target", "phase8-target-conn", System.currentTimeMillis());
            return new ConnectionLocator(new NoopRouteRegistry(), sessionManager, serverProperties) {
                @Override
                public List<ConnectionLocation> locateUserDevices(Long userId) {
                    if (Long.valueOf(1002L).equals(userId)) {
                        return List.of(ConnectionLocation.remote(route));
                    }
                    return List.of();
                }

                @Override
                public ConnectionLocation locate(Long userId, String deviceId) {
                    if (Long.valueOf(1002L).equals(userId) && "bob-web".equals(deviceId)) {
                        return ConnectionLocation.remote(route);
                    }
                    return ConnectionLocation.offline();
                }
            };
        }

        @Bean
        RemoteMessageRelayPublisher remoteMessageRelayPublisher(
                RabbitTemplate rabbitTemplate,
                RabbitMqProperties properties,
                ServerProperties serverProperties) {
            return new RabbitMqRemoteMessageRelayPublisher(rabbitTemplate, properties, serverProperties);
        }

        @Bean
        MessageDeliveryService messageDeliveryService(
                ConnectionLocator connectionLocator,
                AckService ackService,
                RemoteMessageRelayPublisher relayPublisher) {
            return new MessageDeliveryService(connectionLocator, ackService, relayPublisher);
        }
    }

    @Configuration
    @Import(RabbitMqRelayConfiguration.class)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            SqlInitializationAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class,
            MybatisPlusAutoConfiguration.class
    })
    static class TargetRelayTestConfig {

        @Bean
        SessionManager sessionManager() {
            return new SessionManager();
        }

        @Bean
        ServerProperties serverProperties() {
            ServerProperties properties = new ServerProperties();
            properties.setId("phase8-target");
            properties.setHeartbeatIntervalSeconds(60);
            properties.setOfflineTimeoutSeconds(120);
            properties.setRouteTtlSeconds(120);
            return properties;
        }

        @Bean
        RabbitMqProperties rabbitMqProperties() {
            RabbitMqProperties properties = new RabbitMqProperties();
            properties.setEnabled(true);
            properties.setExchange("im.message.relay");
            properties.setQueuePrefix("im.message.relay.");
            properties.setDeliveryDedupTtlSeconds(30);
            return properties;
        }

        @Bean
        AckProperties ackProperties() {
            AckProperties properties = new AckProperties();
            properties.setRetryEnabled(false);
            properties.setRedisEnabled(false);
            properties.setRetryDelaysMillis(List.of(3000L, 3000L, 5000L));
            properties.setScanIntervalMillis(1000L);
            properties.setScanLimit(100);
            return properties;
        }

        @Bean
        PendingAckRepository pendingAckRepository() {
            return new InMemoryPendingAckRepository();
        }

        @Bean
        RelayDeliveryDeduplicator relayDeliveryDeduplicator() {
            return new InMemoryRelayDeliveryDeduplicator();
        }

        @Bean
        AckService ackService(PendingAckRepository pendingAckRepository, AckProperties properties) {
            return new AckService(pendingAckRepository, properties);
        }

        @Bean
        RabbitMqRemoteMessageRelayListener rabbitMqRemoteMessageRelayListener(
                SessionManager sessionManager,
                AckService ackService,
                RelayDeliveryDeduplicator deduplicator,
                ServerProperties serverProperties,
                AckProperties ackProperties) {
            return new RabbitMqRemoteMessageRelayListener(
                    sessionManager,
                    ackService,
                    deduplicator,
                    serverProperties,
                    new ConnectionLocator(new NoopRouteRegistry(), sessionManager, serverProperties),
                    new com.example.im.mq.NoopRemoteMessageRelayPublisher(),
                    ackProperties);
        }
    }
}
