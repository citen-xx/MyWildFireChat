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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
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
                "im.auth.database-enabled=false",
                "im.chat.enabled=false",
                "im.mybatis.enabled=false",
                "im.sequence.redis-enabled=false",
                "im.websocket.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                        + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"
        })
class Phase2LoginConnectIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NettyServer nettyServer;

    @Autowired
    private SessionManager sessionManager;

    @Test
    void twoUsersShouldLoginConnectAndHeartbeat() throws Exception {
        LoginResult alice = login("alice");
        LoginResult bob = login("bob");

        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ConnectedClient aliceClient = connect(group, alice.token(), "alice-web");
            ConnectedClient bobClient = connect(group, bob.token(), "bob-web");

            assertConnectAck(aliceClient.nextMessage(), 1001L, "alice-web");
            assertConnectAck(bobClient.nextMessage(), 1002L, "bob-web");
            assertThat(sessionManager.onlineSessionCount()).isEqualTo(2);

            aliceClient.channel.writeAndFlush(MessageEnvelope.newBuilder()
                    .setMessageType(MessageEnvelope.MessageType.PING)
                    .setRequestId("ping-alice")
                    .setTimestamp(System.currentTimeMillis())
                    .build()).syncUninterruptibly();

            MessageEnvelope pong = aliceClient.nextMessage();
            assertThat(pong.getMessageType()).isEqualTo(MessageEnvelope.MessageType.PONG);
            assertThat(pong.getRequestId()).isEqualTo("ping-alice");

            aliceClient.close();
            bobClient.close();
            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(sessionManager.onlineSessionCount()).isZero());
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
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

    @TestConfiguration
    static class TestUsers {

        @Bean
        @Primary
        UserCredentialReader userCredentialReader() {
            return username -> {
                if ("alice".equals(username)) {
                    return Optional.of(new UserCredential(1001L, "alice", "{noop}password123", "ACTIVE"));
                }
                if ("bob".equals(username)) {
                    return Optional.of(new UserCredential(1002L, "bob", "{noop}password123", "ACTIVE"));
                }
                return Optional.empty();
            };
        }
    }
}
