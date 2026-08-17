package com.example.im;

import com.example.im.auth.service.LoginCommand;
import com.example.im.auth.service.LoginResult;
import com.example.im.message.ack.PendingAckRepository;
import com.example.im.message.service.ConversationSequenceGenerator;
import com.example.im.netty.protocol.ImProtocol.ConnectAck;
import com.example.im.netty.protocol.ImProtocol.ConnectRequest;
import com.example.im.netty.protocol.ImProtocol.MessageAck;
import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import com.example.im.netty.protocol.ImProtocol.PushMessage;
import com.example.im.netty.protocol.ImProtocol.SendMessageRequest;
import com.example.im.netty.protocol.ImProtocol.SendResult;
import com.example.im.netty.server.NettyServer;
import com.example.im.netty.session.SessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "im.netty.port=0",
                "im.auth.database-enabled=true",
                "im.chat.enabled=true",
                "im.mybatis.enabled=true",
                "im.sequence.redis-enabled=false",
                "im.ack.redis-enabled=false",
                "im.ack.retry-enabled=true",
                "im.ack.retry-delays-millis=150,150,200",
                "im.ack.scan-interval-millis=50",
                "spring.datasource.url=jdbc:h2:mem:phase4;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/schema-h2.sql",
                "spring.sql.init.data-locations=classpath:db/data-h2.sql",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        })
class Phase4ReliableDeliveryIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int httpPort;

    @Autowired
    private NettyServer nettyServer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PendingAckRepository pendingAckRepository;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private TestSequenceGenerator sequenceGenerator;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("delete from message");
        jdbcTemplate.update("delete from conversation_member");
        jdbcTemplate.update("delete from conversation");
        sequenceGenerator.reset();
    }

    @AfterEach
    void waitForSessionsToClose() {
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(sessionManager.onlineSessionCount()).isZero());
    }

    @Test
    void tcpAckShouldRemovePendingAckAndAllowDuplicateAck() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        try {
            ConnectedClient alice = connect(group, "alice", "alice-web");
            ConnectedClient bob = connect(group, "bob", "bob-web");

            SendResult sendResult = alice.sendMessage("ack-tcp-1", 1002L, "need ack");
            PushMessage push = PushMessage.parseFrom(
                    bob.nextOfType(MessageEnvelope.MessageType.PUSH_MESSAGE).getPayload());

            assertThat(push.getMessageId()).isEqualTo(sendResult.getMessageId());
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThat(pendingAckRepository.exists(1002L, "bob-web", push.getMessageId())).isTrue());

            bob.ack(push);
            bob.ack(push);

            await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThat(pendingAckRepository.exists(1002L, "bob-web", push.getMessageId())).isFalse());

            alice.close();
            bob.close();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void missingTcpAckShouldRetryAndStopAfterAck() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        try {
            ConnectedClient alice = connect(group, "alice", "alice-web");
            ConnectedClient bob = connect(group, "bob", "bob-web");

            SendResult sendResult = alice.sendMessage("ack-retry-1", 1002L, "retry me");
            PushMessage firstPush = PushMessage.parseFrom(
                    bob.nextOfType(MessageEnvelope.MessageType.PUSH_MESSAGE).getPayload());
            PushMessage retryPush = PushMessage.parseFrom(
                    bob.nextOfType(MessageEnvelope.MessageType.PUSH_MESSAGE).getPayload());

            assertThat(firstPush.getMessageId()).isEqualTo(sendResult.getMessageId());
            assertThat(retryPush.getMessageId()).isEqualTo(firstPush.getMessageId());

            bob.ack(retryPush);

            await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThat(pendingAckRepository.exists(1002L, "bob-web", retryPush.getMessageId())).isFalse());
            assertThat(bob.pollOfType(MessageEnvelope.MessageType.PUSH_MESSAGE, Duration.ofMillis(350))).isEmpty();

            alice.close();
            bob.close();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void sameUserDevicesShouldAckIndependently() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(3);
        try {
            ConnectedClient alice = connect(group, "alice", "alice-web");
            ConnectedClient bobWeb = connect(group, "bob", "bob-web");
            ConnectedClient bobPc = connect(group, "bob", "bob-pc");

            alice.sendMessage("ack-multi-device", 1002L, "both devices");
            PushMessage webPush = PushMessage.parseFrom(
                    bobWeb.nextOfType(MessageEnvelope.MessageType.PUSH_MESSAGE).getPayload());
            PushMessage pcPush = PushMessage.parseFrom(
                    bobPc.nextOfType(MessageEnvelope.MessageType.PUSH_MESSAGE).getPayload());

            assertThat(webPush.getMessageId()).isEqualTo(pcPush.getMessageId());
            bobWeb.ack(webPush);

            await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
                assertThat(pendingAckRepository.exists(1002L, "bob-web", webPush.getMessageId())).isFalse();
                assertThat(pendingAckRepository.exists(1002L, "bob-pc", pcPush.getMessageId())).isTrue();
            });

            bobPc.ack(pcPush);
            alice.close();
            bobWeb.close();
            bobPc.close();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void offlineDeviceShouldStopOnlineRetry() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        try {
            ConnectedClient alice = connect(group, "alice", "alice-web");
            ConnectedClient bob = connect(group, "bob", "bob-web");

            alice.sendMessage("ack-offline", 1002L, "disconnect");
            PushMessage push = PushMessage.parseFrom(
                    bob.nextOfType(MessageEnvelope.MessageType.PUSH_MESSAGE).getPayload());
            bob.close();

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(pendingAckRepository.exists(1002L, "bob-web", push.getMessageId())).isFalse());

            alice.close();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void sendResultShouldNotMeanReceiverAck() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        try {
            ConnectedClient alice = connect(group, "alice", "alice-web");
            ConnectedClient bob = connect(group, "bob", "bob-web");

            SendResult sendResult = alice.sendMessage("ack-independent", 1002L, "independent");
            PushMessage push = PushMessage.parseFrom(
                    bob.nextOfType(MessageEnvelope.MessageType.PUSH_MESSAGE).getPayload());

            assertThat(sendResult.getMessageId()).isEqualTo(push.getMessageId());
            assertThat(pendingAckRepository.exists(1002L, "bob-web", push.getMessageId())).isTrue();

            bob.ack(push);
            alice.close();
            bob.close();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void websocketAckShouldRemovePendingAck() throws Exception {
        LoginResult aliceLogin = login("alice");
        LoginResult bobLogin = login("bob");

        WsClient alice = openWebSocketClient();
        WsClient bob = openWebSocketClient();
        try {
            alice.send(Map.of(
                    "type", "CONNECT",
                    "requestId", "ws-alice",
                    "token", aliceLogin.token(),
                    "deviceId", "alice-browser"));
            bob.send(Map.of(
                    "type", "CONNECT",
                    "requestId", "ws-bob",
                    "token", bobLogin.token(),
                    "deviceId", "bob-browser"));

            alice.nextType("CONNECT_ACK");
            bob.nextType("CONNECT_ACK");

            alice.send(Map.of(
                    "type", "SEND_MESSAGE",
                    "requestId", "ws-ack-1",
                    "payload", Map.of(
                            "clientMessageId", "ws-ack-1",
                            "receiverId", 1002L,
                            "content", "websocket ack",
                            "messageType", "TEXT")));

            JsonNode sendResult = alice.nextType("SEND_RESULT");
            JsonNode push = bob.nextType("PUSH_MESSAGE");
            String messageId = push.path("payload").path("messageId").asText();

            assertThat(messageId).isEqualTo(sendResult.path("payload").path("messageId").asText());
            assertThat(pendingAckRepository.exists(1002L, "bob-browser", messageId)).isTrue();

            bob.send(Map.of(
                    "type", "MESSAGE_ACK",
                    "requestId", "ws-message-ack",
                    "payload", Map.of(
                            "messageId", messageId,
                            "conversationId", push.path("payload").path("conversationId").asLong(),
                            "sequence", push.path("payload").path("sequence").asLong())));

            await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThat(pendingAckRepository.exists(1002L, "bob-browser", messageId)).isFalse());
        } finally {
            alice.close();
            bob.close();
        }
    }

    private ConnectedClient connect(EventLoopGroup group, String username, String deviceId) throws Exception {
        LoginResult login = login(username);
        ConnectedClient client = openRawClient(group);
        ConnectRequest request = ConnectRequest.newBuilder()
                .setToken(login.token())
                .setDeviceId(deviceId)
                .build();
        client.channel.writeAndFlush(MessageEnvelope.newBuilder()
                .setMessageType(MessageEnvelope.MessageType.CONNECT)
                .setRequestId("connect-" + deviceId)
                .setTimestamp(System.currentTimeMillis())
                .setPayload(request.toByteString())
                .build()).syncUninterruptibly();

        MessageEnvelope envelope = client.nextOfType(MessageEnvelope.MessageType.CONNECT_ACK);
        ConnectAck ack = ConnectAck.parseFrom(envelope.getPayload());
        assertThat(ack.getDeviceId()).isEqualTo(deviceId);
        return client;
    }

    private LoginResult login(String username) {
        return restTemplate.postForObject(
                "/api/auth/login",
                new LoginCommand(username, "password123"),
                LoginResult.class);
    }

    private ConnectedClient openRawClient(EventLoopGroup group) {
        ClientMessageCollector collector = new ClientMessageCollector();
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new io.netty.channel.ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel channel) {
                        channel.pipeline().addLast("frameDecoder",
                                new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4));
                        channel.pipeline().addLast("protobufDecoder",
                                new ProtobufDecoder(MessageEnvelope.getDefaultInstance()));
                        channel.pipeline().addLast("frameEncoder", new LengthFieldPrepender(4));
                        channel.pipeline().addLast("protobufEncoder", new ProtobufEncoder());
                        channel.pipeline().addLast("collector", collector);
                    }
                });

        Channel channel = bootstrap.connect("127.0.0.1", nettyServer.boundPort())
                .syncUninterruptibly()
                .channel();
        return new ConnectedClient(channel, collector);
    }

    private MessageEnvelope sendEnvelope(String requestId, String clientMessageId, Long receiverId, String content) {
        SendMessageRequest request = SendMessageRequest.newBuilder()
                .setClientMessageId(clientMessageId)
                .setReceiverId(receiverId)
                .setContent(content)
                .setMessageType("TEXT")
                .build();
        return MessageEnvelope.newBuilder()
                .setMessageType(MessageEnvelope.MessageType.SEND_MESSAGE)
                .setRequestId(requestId)
                .setTimestamp(System.currentTimeMillis())
                .setPayload(request.toByteString())
                .build();
    }

    private MessageEnvelope ackEnvelope(PushMessage push) {
        MessageAck ack = MessageAck.newBuilder()
                .setMessageId(push.getMessageId())
                .setConversationId(push.getConversationId())
                .setSequence(push.getSequence())
                .build();
        return MessageEnvelope.newBuilder()
                .setMessageType(MessageEnvelope.MessageType.MESSAGE_ACK)
                .setRequestId("ack-" + push.getMessageId())
                .setTimestamp(System.currentTimeMillis())
                .setPayload(ack.toByteString())
                .build();
    }

    private final class ConnectedClient {

        private final Channel channel;
        private final ClientMessageCollector collector;

        private ConnectedClient(Channel channel, ClientMessageCollector collector) {
            this.channel = channel;
            this.collector = collector;
        }

        private SendResult sendMessage(String clientMessageId, Long receiverId, String content) throws Exception {
            channel.writeAndFlush(sendEnvelope("send-" + clientMessageId, clientMessageId, receiverId, content))
                    .syncUninterruptibly();
            MessageEnvelope envelope = nextOfType(MessageEnvelope.MessageType.SEND_RESULT);
            return SendResult.parseFrom(envelope.getPayload());
        }

        private void ack(PushMessage push) {
            channel.writeAndFlush(ackEnvelope(push)).syncUninterruptibly();
        }

        private MessageEnvelope nextOfType(MessageEnvelope.MessageType messageType) throws InterruptedException {
            return pollOfType(messageType, Duration.ofSeconds(5))
                    .orElseThrow(() -> new AssertionError("Timed out waiting for " + messageType));
        }

        private Optional<MessageEnvelope> pollOfType(
                MessageEnvelope.MessageType messageType,
                Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                MessageEnvelope envelope = collector.messages.poll(50, TimeUnit.MILLISECONDS);
                if (envelope != null && envelope.getMessageType() == messageType) {
                    return Optional.of(envelope);
                }
            }
            return Optional.empty();
        }

        private void close() {
            if (channel.isOpen()) {
                channel.close().syncUninterruptibly();
            }
        }
    }

    private static class ClientMessageCollector extends SimpleChannelInboundHandler<MessageEnvelope> {

        private final BlockingQueue<MessageEnvelope> messages = new LinkedBlockingQueue<>();

        @Override
        protected void channelRead0(ChannelHandlerContext context, MessageEnvelope envelope) {
            messages.add(envelope);
        }
    }

    private WsClient openWebSocketClient() throws Exception {
        WsClient handler = new WsClient();
        WebSocketSession session = new StandardWebSocketClient()
                .execute(handler, "ws://localhost:" + httpPort + "/ws/im")
                .get(5, TimeUnit.SECONDS);
        handler.session = session;
        return handler;
    }

    private final class WsClient extends TextWebSocketHandler {

        private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private WebSocketSession session;

        private void send(Map<String, Object> message) throws Exception {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        }

        private JsonNode nextType(String type) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                JsonNode message = messages.poll(50, TimeUnit.MILLISECONDS);
                if (message != null && type.equals(message.path("type").asText())) {
                    return message;
                }
            }
            throw new AssertionError("Timed out waiting for websocket " + type);
        }

        private void close() throws Exception {
            if (session != null && session.isOpen()) {
                session.close();
            }
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            messages.add(objectMapper.readTree(message.getPayload()));
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        TestSequenceGenerator testSequenceGenerator() {
            return new TestSequenceGenerator();
        }
    }

    static class TestSequenceGenerator implements ConversationSequenceGenerator {

        private final ConcurrentHashMap<Long, AtomicLong> sequences = new ConcurrentHashMap<>();

        @Override
        public long nextSequence(Long conversationId) {
            return sequences.computeIfAbsent(conversationId, ignored -> new AtomicLong()).incrementAndGet();
        }

        void reset() {
            sequences.clear();
        }
    }
}
