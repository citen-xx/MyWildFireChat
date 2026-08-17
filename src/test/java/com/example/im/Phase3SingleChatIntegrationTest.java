package com.example.im;

import com.example.im.auth.service.LoginCommand;
import com.example.im.auth.service.LoginResult;
import com.example.im.message.service.ConversationSequenceGenerator;
import com.example.im.netty.protocol.ImProtocol.ConnectAck;
import com.example.im.netty.protocol.ImProtocol.ConnectRequest;
import com.example.im.netty.protocol.ImProtocol.ErrorPayload;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
                "spring.datasource.url=jdbc:h2:mem:phase3;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
class Phase3SingleChatIntegrationTest {

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
    private TestSequenceGenerator sequenceGenerator;

    @Autowired
    private SessionManager sessionManager;

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
    void onlineSingleChatShouldPersistAndPushToReceiver() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        try {
            ConnectedClient alice = connect(group, "alice", "alice-web");
            ConnectedClient bob = connect(group, "bob", "bob-web");

            SendResult result = alice.sendMessage("cm-online-1", 1002L, "hello bob");
            PushMessage push = PushMessage.parseFrom(bob.nextOfType(MessageEnvelope.MessageType.PUSH_MESSAGE).getPayload());

            assertThat(result.getClientMessageId()).isEqualTo("cm-online-1");
            assertThat(push.getMessageId()).isEqualTo(result.getMessageId());
            assertThat(push.getSenderId()).isEqualTo(1001L);
            assertThat(push.getReceiverId()).isEqualTo(1002L);
            assertThat(push.getContent()).isEqualTo("hello bob");

            Integer count = jdbcTemplate.queryForObject("select count(*) from message", Integer.class);
            Long senderId = jdbcTemplate.queryForObject("select sender_id from message where message_id = ?",
                    Long.class,
                    result.getMessageId());
            assertThat(count).isEqualTo(1);
            assertThat(senderId).isEqualTo(1001L);

            alice.close();
            bob.close();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void duplicateClientMessageIdShouldReturnOriginalMessage() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ConnectedClient alice = connect(group, "alice", "alice-web");

            SendResult first = alice.sendMessage("cm-duplicate", 1002L, "one");
            SendResult second = alice.sendMessage("cm-duplicate", 1002L, "one");

            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from message where sender_id = 1001 and client_message_id = 'cm-duplicate'",
                    Integer.class);
            assertThat(count).isEqualTo(1);
            assertThat(second.getMessageId()).isEqualTo(first.getMessageId());
            assertThat(second.getSequence()).isEqualTo(first.getSequence());

            alice.close();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void concurrentOppositeFirstMessagesShouldCreateOneConversation() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            ConnectedClient alice = connect(group, "alice", "alice-web");
            ConnectedClient bob = connect(group, "bob", "bob-web");

            CountDownLatch start = new CountDownLatch(1);
            var aliceFuture = executor.submit(() -> {
                start.await();
                return alice.sendMessage("cm-a-to-b", 1002L, "from alice");
            });
            var bobFuture = executor.submit(() -> {
                start.await();
                return bob.sendMessage("cm-b-to-a", 1001L, "from bob");
            });
            start.countDown();

            SendResult aliceResult = aliceFuture.get(5, TimeUnit.SECONDS);
            SendResult bobResult = bobFuture.get(5, TimeUnit.SECONDS);

            Integer conversationCount = jdbcTemplate.queryForObject(
                    "select count(*) from conversation where biz_key = 'single:1001:1002'",
                    Integer.class);
            Integer memberCount = jdbcTemplate.queryForObject("select count(*) from conversation_member", Integer.class);
            List<Long> sequences = new ArrayList<>(List.of(aliceResult.getSequence(), bobResult.getSequence()));
            sequences.sort(Comparator.naturalOrder());

            assertThat(conversationCount).isEqualTo(1);
            assertThat(memberCount).isEqualTo(2);
            assertThat(aliceResult.getConversationId()).isEqualTo(bobResult.getConversationId());
            assertThat(sequences).containsExactly(1L, 2L);

            alice.close();
            bob.close();
        } finally {
            executor.shutdownNow();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void offlineReceiverShouldStillPersistMessagesWithIncreasingSequence() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ConnectedClient alice = connect(group, "alice", "alice-web");

            SendResult first = alice.sendMessage("cm-offline-1", 1002L, "offline 1");
            SendResult second = alice.sendMessage("cm-offline-2", 1002L, "offline 2");
            SendResult third = alice.sendMessage("cm-offline-3", 1002L, "offline 3");

            Integer count = jdbcTemplate.queryForObject("select count(*) from message", Integer.class);
            assertThat(count).isEqualTo(3);
            assertThat(List.of(first.getSequence(), second.getSequence(), third.getSequence()))
                    .containsExactly(1L, 2L, 3L);

            alice.close();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void receiverMultipleDevicesShouldAllReceivePush() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(3);
        try {
            ConnectedClient alice = connect(group, "alice", "alice-web");
            ConnectedClient bobWeb = connect(group, "bob", "bob-web");
            ConnectedClient bobMobile = connect(group, "bob", "bob-mobile");

            SendResult result = alice.sendMessage("cm-multi-device", 1002L, "to all bob devices");
            PushMessage webPush = PushMessage.parseFrom(
                    bobWeb.nextOfType(MessageEnvelope.MessageType.PUSH_MESSAGE).getPayload());
            PushMessage mobilePush = PushMessage.parseFrom(
                    bobMobile.nextOfType(MessageEnvelope.MessageType.PUSH_MESSAGE).getPayload());

            assertThat(webPush.getMessageId()).isEqualTo(result.getMessageId());
            assertThat(mobilePush.getMessageId()).isEqualTo(result.getMessageId());

            alice.close();
            bobWeb.close();
            bobMobile.close();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void webSocketClientsShouldReuseMessageServiceForSingleChat() throws Exception {
        LoginResult aliceLogin = login("alice");
        LoginResult bobLogin = login("bob");

        WsClient alice = openWebSocketClient();
        WsClient bob = openWebSocketClient();
        try {
            alice.send(Map.of(
                    "type", "CONNECT",
                    "requestId", "ws-connect-alice",
                    "token", aliceLogin.token(),
                    "deviceId", "alice-browser"));
            bob.send(Map.of(
                    "type", "CONNECT",
                    "requestId", "ws-connect-bob",
                    "token", bobLogin.token(),
                    "deviceId", "bob-browser"));

            assertThat(alice.nextType("CONNECT_ACK").path("payload").path("userId").asLong()).isEqualTo(1001L);
            assertThat(bob.nextType("CONNECT_ACK").path("payload").path("userId").asLong()).isEqualTo(1002L);

            String clientMessageId = "ws-cm-1";
            alice.send(Map.of(
                    "type", "SEND_MESSAGE",
                    "requestId", clientMessageId,
                    "payload", Map.of(
                            "clientMessageId", clientMessageId,
                            "receiverId", 1002L,
                            "content", "hello from websocket",
                            "messageType", "TEXT")));

            JsonNode sendResult = alice.nextType("SEND_RESULT");
            JsonNode push = bob.nextType("PUSH_MESSAGE");

            assertThat(push.path("payload").path("messageId").asText())
                    .isEqualTo(sendResult.path("payload").path("messageId").asText());
            assertThat(push.path("payload").path("senderId").asLong()).isEqualTo(1001L);
            assertThat(push.path("payload").path("content").asText()).isEqualTo("hello from websocket");
            assertThat(jdbcTemplate.queryForObject("select count(*) from message", Integer.class)).isEqualTo(1);
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    void unauthenticatedChannelCannotSendMessage() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ConnectedClient raw = openRawClient(group);
            raw.channel.writeAndFlush(sendEnvelope("send-without-connect", "cm-no-connect", 1002L, "nope"))
                    .syncUninterruptibly();

            MessageEnvelope error = raw.nextOfType(MessageEnvelope.MessageType.ERROR);
            ErrorPayload payload = ErrorPayload.parseFrom(error.getPayload());
            assertThat(payload.getCode()).isEqualTo("UNAUTHENTICATED");

            Integer count = jdbcTemplate.queryForObject("select count(*) from message", Integer.class);
            assertThat(count).isZero();
            raw.close();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private ConnectedClient connect(EventLoopGroup group, String username, String deviceId) throws Exception {
        LoginResult login = login(username);
        assertThat(login).isNotNull();

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

        private MessageEnvelope nextOfType(MessageEnvelope.MessageType messageType) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                MessageEnvelope envelope = collector.messages.poll(200, TimeUnit.MILLISECONDS);
                if (envelope != null && envelope.getMessageType() == messageType) {
                    return envelope;
                }
            }
            throw new AssertionError("Timed out waiting for " + messageType);
        }

        private void close() {
            channel.close().syncUninterruptibly();
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
                JsonNode message = messages.poll(200, TimeUnit.MILLISECONDS);
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
