package com.example.im;

import com.example.im.auth.service.LoginCommand;
import com.example.im.auth.service.LoginResult;
import com.example.im.conversation.service.ConversationService;
import com.example.im.group.service.AddGroupMembersCommand;
import com.example.im.group.service.CreateGroupCommand;
import com.example.im.group.service.GroupMemberView;
import com.example.im.group.service.GroupSummary;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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
                "im.ack.redis-enabled=false",
                "im.ack.retry-enabled=true",
                "im.ack.retry-delays-millis=150,150,200",
                "im.ack.scan-interval-millis=50",
                "spring.datasource.url=jdbc:h2:mem:phase6;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
class Phase6GroupChatIntegrationTest {

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
        jdbcTemplate.update("delete from group_member");
        jdbcTemplate.update("delete from chat_group");
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
    void groupCreationShouldFanOutToAllMembersAndSupportDuplicateMessageId() throws Exception {
        LoginResult aliceLogin = login("alice");
        LoginResult bobLogin = login("bob");
        LoginResult charlieLogin = login("charlie");
        GroupSummary group = createGroup(aliceLogin.token(), "Backend Team", List.of(1002L, 1003L));

        assertThat(listGroups(aliceLogin.token())).hasSize(1);
        List<GroupMemberView> members = listMembers(aliceLogin.token(), group.groupId());
        assertThat(members).extracting(GroupMemberView::userId).containsExactlyInAnyOrder(1001L, 1002L, 1003L);
        assertThat(members).filteredOn(member -> member.userId().equals(1001L))
                .first()
                .extracting(GroupMemberView::role)
                .isEqualTo("OWNER");

        EventLoopGroup groupLoop = new NioEventLoopGroup(3);
        try {
            ConnectedClient alice = connect(groupLoop, aliceLogin, "alice-group-web");
            ConnectedClient bob = connect(groupLoop, bobLogin, "bob-group-web");
            ConnectedClient charlie = connect(groupLoop, charlieLogin, "charlie-group-web");

            SendResult first = alice.sendGroupMessage(group.groupId(), "group-msg-1", "hello everyone");
            PushMessage alicePush = alice.nextPushAndAck();
            PushMessage bobPush = bob.nextPushAndAck();
            PushMessage charliePush = charlie.nextPushAndAck();

            SendResult duplicate = alice.sendGroupMessage(group.groupId(), "group-msg-1", "hello everyone");

            assertThat(first.getMessageId()).isEqualTo(duplicate.getMessageId());
            assertThat(first.getSequence()).isEqualTo(1L);
            assertThat(alicePush.getConversationId()).isEqualTo(group.conversationId());
            assertThat(bobPush.getConversationId()).isEqualTo(group.conversationId());
            assertThat(charliePush.getConversationId()).isEqualTo(group.conversationId());
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from message where conversation_id = ?",
                    Long.class,
                    group.conversationId())).isEqualTo(1L);
            assertThat(pendingAckRepository.count()).isZero();

            alice.close();
            bob.close();
            charlie.close();
        } finally {
            groupLoop.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void offlineGroupMemberShouldRecoverBySequenceSync() throws Exception {
        LoginResult aliceLogin = login("alice");
        LoginResult bobLogin = login("bob");
        LoginResult charlieLogin = login("charlie");
        GroupSummary group = createGroup(aliceLogin.token(), "Backend Team", List.of(1002L, 1003L));

        EventLoopGroup groupLoop = new NioEventLoopGroup(3);
        try {
            ConnectedClient alice = connect(groupLoop, aliceLogin, "alice-offline-web");
            ConnectedClient bob = connect(groupLoop, bobLogin, "bob-offline-web");
            ConnectedClient charlie = connect(groupLoop, charlieLogin, "charlie-offline-web");

            SendResult first = alice.sendGroupMessage(group.groupId(), "offline-1", "hello-1");
            alice.nextPushAndAck();
            bob.nextPushAndAck();
            charlie.nextPushAndAck();

            charlie.close();

            alice.sendGroupMessage(group.groupId(), "offline-2", "hello-2");
            alice.sendGroupMessage(group.groupId(), "offline-3", "hello-3");
            alice.sendGroupMessage(group.groupId(), "offline-4", "hello-4");

            ConnectedClient charlieReconnected = connect(groupLoop, charlieLogin, "charlie-offline-web");
            SyncResponse response = charlieReconnected.sync(group.conversationId(), first.getSequence(), 100);
            SyncComplete complete = SyncComplete.parseFrom(
                    charlieReconnected.nextOfType(MessageEnvelope.MessageType.SYNC_COMPLETE).getPayload());

            assertThat(response.getMessagesList())
                    .extracting(PushMessage::getSequence)
                    .containsExactly(2L, 3L, 4L);
            assertThat(response.getMessagesList())
                    .extracting(PushMessage::getContent)
                    .containsExactly("hello-2", "hello-3", "hello-4");
            assertThat(response.getHasMore()).isFalse();
            assertThat(complete.getNextSequence()).isEqualTo(4L);

            alice.close();
            bob.close();
            charlieReconnected.close();
        } finally {
            groupLoop.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void joinSequenceShouldHidePreJoinHistory() throws Exception {
        LoginResult aliceLogin = login("alice");
        LoginResult bobLogin = login("bob");
        LoginResult charlieLogin = login("charlie");
        GroupSummary group = createGroup(aliceLogin.token(), "Backend Team", List.of(1002L));

        EventLoopGroup groupLoop = new NioEventLoopGroup(3);
        try {
            ConnectedClient alice = connect(groupLoop, aliceLogin, "alice-join-web");
            ConnectedClient bob = connect(groupLoop, bobLogin, "bob-join-web");

            alice.sendGroupMessage(group.groupId(), "join-1", "seq-1");
            alice.nextPushAndAck();
            bob.nextPushAndAck();
            alice.sendGroupMessage(group.groupId(), "join-2", "seq-2");
            alice.nextPushAndAck();
            bob.nextPushAndAck();
            alice.sendGroupMessage(group.groupId(), "join-3", "seq-3");
            alice.nextPushAndAck();
            bob.nextPushAndAck();

            addMembers(aliceLogin.token(), group.groupId(), List.of(1003L));
            List<GroupMemberView> members = listMembers(aliceLogin.token(), group.groupId());
            assertThat(members).filteredOn(member -> member.userId().equals(1003L))
                    .first()
                    .extracting(GroupMemberView::joinSequence)
                    .isEqualTo(3L);

            alice.sendGroupMessage(group.groupId(), "join-4", "seq-4");
            alice.nextPushAndAck();
            bob.nextPushAndAck();
            alice.sendGroupMessage(group.groupId(), "join-5", "seq-5");
            alice.nextPushAndAck();
            bob.nextPushAndAck();

            ConnectedClient charlie = connect(groupLoop, charlieLogin, "charlie-join-web");
            SyncResponse response = charlie.sync(group.conversationId(), 0L, 100);

            assertThat(response.getMessagesList())
                    .extracting(PushMessage::getSequence)
                    .containsExactly(4L, 5L);

            alice.close();
            bob.close();
            charlie.close();
        } finally {
            groupLoop.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void leaveAndDisbandShouldBlockAccessAndSending() throws Exception {
        LoginResult aliceLogin = login("alice");
        LoginResult bobLogin = login("bob");
        LoginResult charlieLogin = login("charlie");
        GroupSummary group = createGroup(aliceLogin.token(), "Backend Team", List.of(1002L, 1003L));

        EventLoopGroup groupLoop = new NioEventLoopGroup(3);
        try {
            ConnectedClient alice = connect(groupLoop, aliceLogin, "alice-leave-web");
            ConnectedClient bob = connect(groupLoop, bobLogin, "bob-leave-web");
            ConnectedClient charlie = connect(groupLoop, charlieLogin, "charlie-leave-web");

            leaveGroup(bobLogin.token(), group.groupId());
            ErrorPayload bobSendError = bob.sendGroupError(group.groupId(), "leave-send", "should fail");
            ErrorPayload bobSyncError = bob.syncError(group.conversationId(), 0L, 100);

            disbandGroup(aliceLogin.token(), group.groupId());
            ErrorPayload charlieSendError = charlie.sendGroupError(group.groupId(), "disband-send", "should fail");

            assertThat(bobSendError.getCode()).isEqualTo("INVALID_SEND_MESSAGE");
            assertThat(bobSyncError.getCode()).isEqualTo("INVALID_SYNC_REQUEST");
            assertThat(charlieSendError.getCode()).isEqualTo("INVALID_SEND_MESSAGE");

            alice.close();
            bob.close();
            charlie.close();
        } finally {
            groupLoop.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void websocketGroupSendShouldReuseSameDeliveryStack() throws Exception {
        LoginResult aliceLogin = login("alice");
        LoginResult bobLogin = login("bob");
        GroupSummary group = createGroup(aliceLogin.token(), "Web Team", List.of(1002L));

        WsClient alice = openWebSocketClient();
        WsClient bob = openWebSocketClient();
        try {
            alice.send(Map.of(
                    "type", "CONNECT",
                    "requestId", "ws-alice-connect",
                    "token", aliceLogin.token(),
                    "deviceId", "alice-websocket"));
            bob.send(Map.of(
                    "type", "CONNECT",
                    "requestId", "ws-bob-connect",
                    "token", bobLogin.token(),
                    "deviceId", "bob-websocket"));
            alice.nextType("CONNECT_ACK");
            bob.nextType("CONNECT_ACK");

            alice.send(Map.of(
                    "type", "SEND_MESSAGE",
                    "requestId", "ws-group-send",
                    "payload", Map.of(
                            "clientMessageId", "ws-group-1",
                            "groupId", group.groupId(),
                            "conversationType", "GROUP",
                            "content", "websocket group hello",
                            "messageType", "TEXT")));

            JsonNode sendResult = alice.nextType("SEND_RESULT");
            JsonNode bobPush = bob.nextType("PUSH_MESSAGE");
            alice.nextType("PUSH_MESSAGE");

            assertThat(sendResult.path("payload").path("conversationId").asLong()).isEqualTo(group.conversationId());
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from message where conversation_id = ?",
                    Long.class,
                    group.conversationId())).isEqualTo(1L);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(pendingAckRepository.count()).isZero());
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    void concurrentGroupMessagesShouldKeepUniqueSequence() throws Exception {
        LoginResult aliceLogin = login("alice");
        LoginResult bobLogin = login("bob");
        GroupSummary group = createGroup(aliceLogin.token(), "Concurrent Team", List.of(1002L));

        EventLoopGroup groupLoop = new NioEventLoopGroup(2);
        try {
            ConnectedClient alice = connect(groupLoop, aliceLogin, "alice-concurrent-web");
            ConnectedClient bob = connect(groupLoop, bobLogin, "bob-concurrent-web");

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(20);
            for (int i = 0; i < 10; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        alice.sendGroupMessage(group.groupId(), "alice-" + index, "alice-" + index);
                        alice.nextPushAndAck();
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    } finally {
                        latch.countDown();
                    }
                });
                executor.submit(() -> {
                    try {
                        bob.sendGroupMessage(group.groupId(), "bob-" + index, "bob-" + index);
                        bob.nextPushAndAck();
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdownNow();

            List<Long> sequences = jdbcTemplate.queryForList(
                    "select sequence from message where conversation_id = ? order by sequence",
                    Long.class,
                    group.conversationId());
            assertThat(sequences).hasSize(20);
            assertThat(sequences).doesNotHaveDuplicates();

            alice.close();
            bob.close();
        } finally {
            groupLoop.shutdownGracefully().syncUninterruptibly();
        }
    }

    private GroupSummary createGroup(String token, String name, List<Long> memberIds) {
        ResponseEntity<GroupSummary> response = restTemplate.exchange(
                "/api/groups",
                HttpMethod.POST,
                new HttpEntity<>(new CreateGroupCommand(name, memberIds), jsonHeaders(token)),
                GroupSummary.class);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private List<GroupSummary> listGroups(String token) {
        ResponseEntity<GroupSummary[]> response = restTemplate.exchange(
                "/api/groups",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(token)),
                GroupSummary[].class);
        return response.getBody() == null ? List.of() : List.of(response.getBody());
    }

    private List<GroupMemberView> listMembers(String token, Long groupId) {
        ResponseEntity<GroupMemberView[]> response = restTemplate.exchange(
                "/api/groups/" + groupId + "/members",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(token)),
                GroupMemberView[].class);
        return response.getBody() == null ? List.of() : List.of(response.getBody());
    }

    private void addMembers(String token, Long groupId, List<Long> memberIds) {
        restTemplate.exchange(
                "/api/groups/" + groupId + "/members",
                HttpMethod.POST,
                new HttpEntity<>(new AddGroupMembersCommand(memberIds), jsonHeaders(token)),
                GroupSummary.class);
    }

    private void leaveGroup(String token, Long groupId) {
        restTemplate.exchange(
                "/api/groups/" + groupId + "/leave",
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders(token)),
                Void.class);
    }

    private void disbandGroup(String token, Long groupId) {
        restTemplate.exchange(
                "/api/groups/" + groupId,
                HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders(token)),
                Void.class);
    }

    private HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    private LoginResult login(String username) {
        return restTemplate.postForObject(
                "/api/auth/login",
                new LoginCommand(username, "password123"),
                LoginResult.class);
    }

    private ConnectedClient connect(EventLoopGroup group, LoginResult login, String deviceId) throws Exception {
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

    private MessageEnvelope sendGroupEnvelope(String requestId, Long groupId, String clientMessageId, String content) {
        SendMessageRequest request = SendMessageRequest.newBuilder()
                .setClientMessageId(clientMessageId)
                .setGroupId(groupId)
                .setConversationType("GROUP")
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

        private SendResult sendGroupMessage(Long groupId, String clientMessageId, String content) throws Exception {
            channel.writeAndFlush(sendGroupEnvelope("send-" + clientMessageId, groupId, clientMessageId, content))
                    .syncUninterruptibly();
            MessageEnvelope envelope = nextOfType(MessageEnvelope.MessageType.SEND_RESULT);
            return SendResult.parseFrom(envelope.getPayload());
        }

        private PushMessage nextPushAndAck() throws Exception {
            MessageEnvelope envelope = nextOfType(MessageEnvelope.MessageType.PUSH_MESSAGE);
            PushMessage push = PushMessage.parseFrom(envelope.getPayload());
            ack(push);
            return push;
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

        private ErrorPayload sendGroupError(Long groupId, String clientMessageId, String content) throws Exception {
            channel.writeAndFlush(sendGroupEnvelope("send-error-" + clientMessageId, groupId, clientMessageId, content))
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
            JsonNode envelope = objectMapper.readTree(message.getPayload());
            messages.add(envelope);
            if ("PUSH_MESSAGE".equals(envelope.path("type").asText())) {
                JsonNode payload = envelope.path("payload");
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                        "type", "MESSAGE_ACK",
                        "requestId", "ack-" + payload.path("messageId").asText(),
                        "payload", Map.of(
                                "messageId", payload.path("messageId").asText(),
                                "conversationId", payload.path("conversationId").asLong(),
                                "sequence", payload.path("sequence").asLong())))));
            }
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
