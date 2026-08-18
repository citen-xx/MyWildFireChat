package com.example.im;

import com.example.im.auth.service.LoginCommand;
import com.example.im.auth.service.LoginResult;
import com.example.im.auth.service.UserCredential;
import com.example.im.auth.service.UserCredentialReader;
import com.example.im.netty.protocol.ImProtocol.ConnectAck;
import com.example.im.netty.protocol.ImProtocol.ConnectRequest;
import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import com.example.im.netty.server.NettyServer;
import com.example.im.netty.session.SessionManager;
import com.example.im.route.RedisServerRegistry;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "im.netty.port=0",
                "im.server.id=phase7-connect-server-1",
                "im.server.heartbeat-interval-seconds=1",
                "im.server.offline-timeout-seconds=2",
                "im.server.route-ttl-seconds=3",
                "im.auth.database-enabled=false",
                "im.chat.enabled=false",
                "im.mybatis.enabled=false",
                "im.sequence.redis-enabled=false",
                "im.ack.redis-enabled=false",
                "im.ack.retry-enabled=false",
                "im.websocket.enabled=false",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration,"
                        + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"
        })
class Phase7RedisRouteIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NettyServer nettyServer;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private SessionManager sessionManager;

    @AfterEach
    void cleanRedis() {
        redisTemplate.delete(List.of(
                "im:route:1001:phase7-web",
                "im:route:1001:phase7-race-web",
                "im:user:devices:1001"));
        redisTemplate.opsForZSet().remove(RedisServerRegistry.REGISTRY_KEY, "phase7-connect-server-1");
    }

    @Test
    void connectPingAndDisconnectShouldCreateRefreshAndRemoveRoute() throws Exception {
        LoginResult alice = login("alice");
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ConnectedClient client = connect(group, alice.token(), "phase7-web");
            assertConnectAck(client.nextMessage(), 1001L, "phase7-web");

            String routeKey = "im:route:1001:phase7-web";
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(redisTemplate.opsForHash().get(routeKey, "serverId")).isEqualTo("phase7-connect-server-1");
                assertThat(redisTemplate.opsForHash().get(routeKey, "connectionId")).isInstanceOf(String.class);
            });
            String serverConnectionId = (String) redisTemplate.opsForHash().get(routeKey, "connectionId");
            assertThat(serverConnectionId).isNotBlank();

            redisTemplate.delete(routeKey);
            client.ping("phase7-ping");
            assertThat(client.nextMessage().getMessageType()).isEqualTo(MessageEnvelope.MessageType.PONG);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(redisTemplate.opsForHash().get(routeKey, "connectionId"))
                            .isEqualTo(serverConnectionId));

            client.close();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(redisTemplate.opsForHash().entries(routeKey)).isEmpty();
                assertThat(sessionManager.onlineSessionCount()).isZero();
            });
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void oldConnectionCleanupShouldNotDeleteNewConnectionRoute() throws Exception {
        LoginResult alice = login("alice");
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ConnectedClient oldClient = connect(group, alice.token(), "phase7-race-web");
            assertConnectAck(oldClient.nextMessage(), 1001L, "phase7-race-web");
            String routeKey = "im:route:1001:phase7-race-web";
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(redisTemplate.opsForHash().get(routeKey, "connectionId")).isInstanceOf(String.class));
            String oldConnectionId = (String) redisTemplate.opsForHash().get(routeKey, "connectionId");
            assertThat(oldConnectionId).isNotBlank();

            ConnectedClient newClient = connect(group, alice.token(), "phase7-race-web");
            assertConnectAck(newClient.nextMessage(), 1001L, "phase7-race-web");

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(redisTemplate.opsForHash().get(routeKey, "connectionId")).isNotEqualTo(oldConnectionId));
            String newConnectionId = (String) redisTemplate.opsForHash().get(routeKey, "connectionId");
            assertThat(newConnectionId).isNotBlank();

            oldClient.close();
            await().during(Duration.ofMillis(250)).atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThat(redisTemplate.opsForHash().get(routeKey, "connectionId")).isEqualTo(newConnectionId));

            newClient.close();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(redisTemplate.opsForHash().entries(routeKey)).isEmpty());
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void serverHeartbeatShouldRegisterCurrentServer() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(redisTemplate.opsForZSet()
                        .score(RedisServerRegistry.REGISTRY_KEY, "phase7-connect-server-1"))
                        .isNotNull());
    }

    private LoginResult login(String username) {
        return restTemplate.postForObject(
                "/api/auth/login",
                new LoginCommand(username, "password123"),
                LoginResult.class);
    }

    private ConnectedClient connect(EventLoopGroup group, String token, String deviceId) {
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

        ConnectRequest request = ConnectRequest.newBuilder()
                .setToken(token)
                .setDeviceId(deviceId)
                .build();
        channel.writeAndFlush(MessageEnvelope.newBuilder()
                .setMessageType(MessageEnvelope.MessageType.CONNECT)
                .setRequestId("connect-" + deviceId)
                .setTimestamp(System.currentTimeMillis())
                .setPayload(request.toByteString())
                .build()).syncUninterruptibly();

        return new ConnectedClient(channel, collector);
    }

    private void assertConnectAck(MessageEnvelope envelope, Long userId, String deviceId) throws Exception {
        assertThat(envelope.getMessageType()).isEqualTo(MessageEnvelope.MessageType.CONNECT_ACK);
        ConnectAck ack = ConnectAck.parseFrom(envelope.getPayload());
        assertThat(ack.getUserId()).isEqualTo(userId);
        assertThat(ack.getDeviceId()).isEqualTo(deviceId);
    }

    private record ConnectedClient(Channel channel, ClientMessageCollector collector) {

        private MessageEnvelope nextMessage() throws InterruptedException {
            MessageEnvelope envelope = collector.messages.poll(3, TimeUnit.SECONDS);
            assertThat(envelope).isNotNull();
            return envelope;
        }

        private void ping(String requestId) {
            channel.writeAndFlush(MessageEnvelope.newBuilder()
                    .setMessageType(MessageEnvelope.MessageType.PING)
                    .setRequestId(requestId)
                    .setTimestamp(System.currentTimeMillis())
                    .build()).syncUninterruptibly();
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

    @TestConfiguration
    static class TestUsers {

        @Bean
        @Primary
        UserCredentialReader userCredentialReader() {
            return username -> {
                if ("alice".equals(username)) {
                    return Optional.of(new UserCredential(1001L, "alice", "{noop}password123", "ACTIVE"));
                }
                return Optional.empty();
            };
        }
    }
}
