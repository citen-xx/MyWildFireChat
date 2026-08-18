package com.example.im;

import com.example.im.auth.service.LoginCommand;
import com.example.im.auth.service.LoginResult;
import com.example.im.message.ack.PendingAckRepository;
import com.example.im.message.service.ConversationSequenceGenerator;
import com.example.im.netty.protocol.ImProtocol.ConnectAck;
import com.example.im.netty.protocol.ImProtocol.ConnectRequest;
import com.example.im.netty.protocol.ImProtocol.ErrorPayload;
import com.example.im.netty.protocol.ImProtocol.MessageAck;
import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import com.example.im.netty.protocol.ImProtocol.PushMessage;
import com.example.im.netty.protocol.ImProtocol.SendMessageRequest;
import com.example.im.netty.protocol.ImProtocol.SendResult;
import com.example.im.netty.protocol.ImProtocol.SyncComplete;
import com.example.im.netty.protocol.ImProtocol.SyncRequest;
import com.example.im.netty.protocol.ImProtocol.SyncResponse;
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
import java.util.List;
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
                "spring.datasource.url=jdbc:h2:mem:phase5;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
class Phase5OfflineSyncIntegrationTest {

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
        jdbcTemplate.update("delete from user_account where id = 1003");
        sequenceGenerator.reset();
    }

    @AfterEach
    void waitForSessionsToClose() {
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(sessionManager.onlineSessionCount()).isZero());
    }

    @Test
    void tcpClientShouldRecoverOfflineMessagesBySequence() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        try {
            ConnectedClient alice = connect(group, "alice", "alice-web");
            ConnectedClient bob = connect(group, "bob", "bob-web");

            SendResult firstResult = alice.sendMessage("phase5-online-1", 1002L, "hello-1");
            PushMessage firstPush = PushMessage.parseFrom(
                    bob.nextOfType(MessageEnvelope.MessageType.PUSH_MESSAGE).getPayload());
            bob.ack(firstPush);
            bob.close();

            alice.sendMessage("phase5-offline-2", 1002L, "hello-2");
            alice.sendMessage("phase5-offline-3", 1002L, "hello-3");
            alice.sendMessage("phase5-offline-4", 1002L, "hello-4");

            assertThat(jdbcTemplate.queryForList(
                    "select sequence from message where conversation_id = ? order by sequence",
                    Long.class,
                    firstResult.getConversationId()))
                    .containsExactly(1L, 2L, 3L, 4L);

            ConnectedClient bobReconnected = connect(group, "bob", "bob-web");
            SyncResponse response = bobReconnected.sync(firstResult.getConversationId(), 1L, 100);
            SyncComplete complete = SyncComplete.parseFrom(
                    bobReconnected.nextOfType(MessageEnvelope.MessageType.SYNC_COMPLETE).getPayload());

            assertThat(response.getMessagesList())
                    .extracting(PushMessage::getSequence)
                    .containsExactly(2L, 3L, 4L);
            assertThat(response.getMessagesList())
                    .extracting(PushMessage::getContent)
                    .containsExactly("hello-2", "hello-3", "hello-4");
            assertThat(response.getHasMore()).isFalse();
            assertThat(response.getNextSequence()).isEqualTo(4L);
            assertThat(complete.getNextSequence()).isEqualTo(4L);
            assertThat(pendingAckRepository.count()).isZero();

            alice.close();
            bobReconnected.close();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void tcpSyncShouldPageAndCapOversizedLimit() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ConnectedClient alice = connect(group, "alice", "alice-web");
            SendResult first = alice.sendMessage("phase5-page-1", 1002L, "page-1");
            insertMessages(first.getConversationId(), 2, 205);

            ConnectedClient bob = connect(group, "bob", "bob-web");
            SyncResponse capped = bob.sync(first.getConversationId(), 0L, 100_000);
            assertThat(capped.getMessagesCount()).isEqualTo(200);
            assertThat(capped.getHasMore()).isTrue();
            assertThat(capped.getNextSequence()).isEqualTo(200L);

            SyncResponse secondPage = bob.sync(first.getConversationId(), 200L, 10);
            SyncComplete complete = SyncComplete.parseFrom(
                    bob.nextOfType(MessageEnvelope.MessageType.SYNC_COMPLETE).getPayload());
            assertThat(secondPage.getMessagesList())
                    .extracting(PushMessage::getSequence)
                    .containsExactly(201L, 202L, 203L, 204L, 205L);
            assertThat(secondPage.getHasMore()).isFalse();
            assertThat(secondPage.getNextSequence()).isEqualTo(205L);
            assertThat(complete.getNextSequence()).isEqualTo(205L);

            alice.close();
            bob.close();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void syncShouldRejectInvalidAndUnauthorizedRequests() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        try {
            ConnectedClient alice = connect(group, "alice", "alice-web");
            SendResult first = alice.sendMessage("phase5-auth-1", 1002L, "private");
            ConnectedClient bob = connect(group, "bob", "bob-web");

            ErrorPayload negativeSequence = bob.syncError(first.getConversationId(), -1L, 100);
            ErrorPayload zeroLimit = bob.syncError(first.getConversationId(), 0L, 0);
            ErrorPayload missingConversation = bob.syncError(99999L, 0L, 100);

            insertCharlie();
            ConnectedClient charlie = connect(group, "charlie", "charlie-web");
            ErrorPayload unauthorized = charlie.syncError(first.getConversationId(), 0L, 100);

            assertThat(negativeSequence.getCode()).isEqualTo("INVALID_SYNC_REQUEST");
            assertThat(zeroLimit.getCode()).isEqualTo("INVALID_SYNC_REQUEST");
            assertThat(missingConversation.getCode()).isEqualTo("INVALID_SYNC_REQUEST");
            assertThat(unauthorized.getCode()).isEqualTo("INVALID_SYNC_REQUEST");

            alice.close();
            bob.close();
            charlie.close();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void websocketSyncShouldReuseSyncService() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ConnectedClient alice = connect(group, "alice", "alice-web");
            SendResult first = alice.sendMessage("phase5-ws-1", 1002L, "ws-1");
            alice.sendMessage("phase5-ws-2", 1002L, "ws-2");
            alice.sendMessage("phase5-ws-3", 1002L, "ws-3");

            LoginResult bobLogin = login("bob");
            WsClient bob = openWebSocketClient();
            try {
                bob.send(Map.of(
                        "type", "CONNECT",
                        "requestId", "ws-bob",
                        "token", bobLogin.token(),
                        "deviceId", "bob-browser"));
                bob.nextType("CONNECT_ACK");

                bob.send(Map.of(
                        "type", "SYNC_REQUEST",
                        "requestId", "ws-sync",
                        "payload", Map.of(
                                "conversationId", first.getConversationId(),
                                "lastSequence", 1L,
                                "limit", 100)));

                JsonNode response = bob.nextType("SYNC_RESPONSE");
                JsonNode complete = bob.nextType("SYNC_COMPLETE");

                assertThat(response.path("payload").path("messages"))
                        .hasSize(2);
                assertThat(response.path("payload").path("messages").get(0).path("sequence").asLong())
                        .isEqualTo(2L);
                assertThat(response.path("payload").path("messages").get(1).path("sequence").asLong())
                        .isEqualTo(3L);
                assertThat(response.path("payload").path("hasMore").asBoolean()).isFalse();
                assertThat(complete.path("payload").path("nextSequence").asLong()).isEqualTo(3L);
            } finally {
                bob.close();
            }
            alice.close();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private void insertMessages(Long conversationId, int fromSequence, int toSequence) {
        for (int sequence = fromSequence; sequence <= toSequence; sequence++) {
            jdbcTemplate.update("""
                    insert into message (
                        message_id, client_message_id, conversation_id, sequence,
                        sender_id, receiver_id, content, message_type, created_at
                    ) values (?, ?, ?, ?, 1001, 1002, ?, 'TEXT', CURRENT_TIMESTAMP)
                    """,
                    "manual-" + sequence,
                    "manual-client-" + sequence,
                    conversationId,
                    sequence,
                    "page-" + sequence);
        }
    }

    private void insertCharlie() {
        jdbcTemplate.update("""
                insert into user_account (id, username, password_hash, status)
                values (1003, 'charlie', '{noop}password123', 'ACTIVE')
                """);
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

    private MessageEnvelope syncEnvelope(String requestId, Long conversationId, Long lastSequence, Integer limit) {
        SyncRequest.Builder request = SyncRequest.newBuilder()
                .setConversationId(conversationId)
                .setLastSequence(lastSequence);
        if (limit != null) {
            request.setLimit(limit);
        }
        return MessageEnvelope.newBuilder()
                .setMessageType(MessageEnvelope.MessageType.SYNC_REQUEST)
                .setRequestId(requestId)
                .setTimestamp(System.currentTimeMillis())
                .setPayload(request.build().toByteString())
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

        private SyncResponse sync(Long conversationId, Long lastSequence, Integer limit) throws Exception {
            channel.writeAndFlush(syncEnvelope("sync-" + conversationId + "-" + lastSequence,
                            conversationId,
                            lastSequence,
                            limit))
                    .syncUninterruptibly();
            return SyncResponse.parseFrom(nextOfType(MessageEnvelope.MessageType.SYNC_RESPONSE).getPayload());
        }

        private ErrorPayload syncError(Long conversationId, Long lastSequence, Integer limit) throws Exception {
            channel.writeAndFlush(syncEnvelope("sync-error-" + conversationId + "-" + lastSequence,
                            conversationId,
                            lastSequence,
                            limit))
                    .syncUninterruptibly();
            return ErrorPayload.parseFrom(nextOfType(MessageEnvelope.MessageType.ERROR).getPayload());
        }

        private void ack(PushMessage push) {
            channel.writeAndFlush(ackEnvelope(push)).syncUninterruptibly();
        }

        private MessageEnvelope nextOfType(MessageEnvelope.MessageType messageType) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                MessageEnvelope envelope = collector.messages.poll(50, TimeUnit.MILLISECONDS);
                if (envelope != null && envelope.getMessageType() == messageType) {
                    return envelope;
                }
            }
            throw new AssertionError("Timed out waiting for " + messageType);
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
