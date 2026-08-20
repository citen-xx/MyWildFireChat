package com.example.im.load;

import com.example.im.netty.protocol.ImProtocol.ConnectAck;
import com.example.im.netty.protocol.ImProtocol.ConnectRequest;
import com.example.im.netty.protocol.ImProtocol.ErrorPayload;
import com.example.im.netty.protocol.ImProtocol.MessageAck;
import com.example.im.netty.protocol.ImProtocol.MessageEnvelope;
import com.example.im.netty.protocol.ImProtocol.PushMessage;
import com.example.im.netty.protocol.ImProtocol.SendMessageRequest;
import com.example.im.netty.protocol.ImProtocol.SendResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Small, independent TCP benchmark client.
 *
 * It is intentionally outside the application Maven source tree. The runner
 * compiles it against the application's generated protocol classes and runtime
 * dependencies, so the benchmark exercises the real protocol instead of a mock.
 */
public final class LoadTestClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LoadTestClient() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.from(args);
        Result result = new Runner(config).run();
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result.values));
        if (result.failed) {
            System.exit(2);
        }
    }

    private static final class Runner {

        private final Config config;
        private final EventLoopGroup eventLoopGroup = new NioEventLoopGroup();
        private final ScheduledExecutorService scheduler =
                Executors.newScheduledThreadPool(Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())));
        private final List<TcpClient> clients = new CopyOnWriteArrayList<>();
        private final List<TcpClient> senderClients = new CopyOnWriteArrayList<>();
        private final List<TcpClient> receiverClients = new CopyOnWriteArrayList<>();
        private final Metrics metrics = new Metrics();
        private final ConcurrentMap<String, Long> sentAtNanos = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AtomicInteger> pushCountsByClientMessageId = new ConcurrentHashMap<>();
        private final AtomicInteger senderIndex = new AtomicInteger();

        private Runner(Config config) {
            this.config = config;
        }

        private Result run() throws Exception {
            long startedAt = System.currentTimeMillis();
            try {
                connectClients();
                if (config.warmupSeconds > 0) {
                    Thread.sleep(config.warmupSeconds * 1000L);
                    metrics.reset();
                }
                scheduler.scheduleAtFixedRate(
                        () -> clients.forEach(TcpClient::sendPing),
                        10,
                        20,
                        TimeUnit.SECONDS);
                if (config.mode == Mode.CHAT && config.messagesPerSecond > 0) {
                    scheduleSending();
                }
                Thread.sleep(config.durationSeconds * 1000L);
                return new Result(buildResult(startedAt), metrics.errors.sum() > 0);
            } finally {
                closeClients();
                scheduler.shutdownNow();
                eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            }
        }

        private void connectClients() throws Exception {
            String token = login(config.username, config.password);
            CountDownLatch latch = new CountDownLatch(config.connections);
            for (int index = 0; index < config.connections; index++) {
                TcpClient client = new TcpClient(
                        config.host,
                        config.port,
                        token,
                        config.devicePrefix + index,
                        config.conversationType,
                        config.groupId,
                        eventLoopGroup,
                        metrics,
                        sentAtNanos,
                        pushCountsByClientMessageId,
                        config.expectedPushesPerMessage());
                clients.add(client);
                senderClients.add(client);
                long started = System.nanoTime();
                try {
                    client.connectAndAuthenticate();
                    metrics.connectionLatencyNanos.add(System.nanoTime() - started);
                    metrics.connected.incrementAndGet();
                } catch (Exception exception) {
                    metrics.connectionFailures.incrementAndGet();
                    metrics.errors.increment();
                    System.err.printf(
                            "CONNECT failed device=%s error=%s%n",
                            config.devicePrefix + index,
                            exception.getMessage());
                } finally {
                    latch.countDown();
                }
            }
            if (config.mode == Mode.CHAT && !config.receiverUsernames.isEmpty()) {
                for (int index = 0; index < config.receiverUsernames.size(); index++) {
                    String receiverUsername = config.receiverUsernames.get(index);
                    String receiverToken = login(receiverUsername, config.receiverPassword, config.receiverHttpUrl());
                    TcpClient client = new TcpClient(
                            config.receiverHost,
                            config.receiverPort,
                            receiverToken,
                            config.receiverDevicePrefix + index,
                            config.conversationType,
                            config.groupId,
                            eventLoopGroup,
                            metrics,
                            sentAtNanos,
                            pushCountsByClientMessageId,
                            config.expectedPushesPerMessage());
                    clients.add(client);
                    receiverClients.add(client);
                    long started = System.nanoTime();
                    try {
                        client.connectAndAuthenticate();
                        metrics.connectionLatencyNanos.add(System.nanoTime() - started);
                        metrics.connected.incrementAndGet();
                    } catch (Exception exception) {
                        metrics.connectionFailures.incrementAndGet();
                        metrics.errors.increment();
                        System.err.printf(
                                "CONNECT failed receiverDevice=%s error=%s%n",
                                config.receiverDevicePrefix + index,
                                exception.getMessage());
                    }
                }
            }
            latch.await(30, TimeUnit.SECONDS);
        }

        private void scheduleSending() {
            long intervalNanos = Math.max(1L, 1_000_000_000L / config.messagesPerSecond);
            scheduler.scheduleAtFixedRate(() -> {
                List<TcpClient> activeSenders = senderClients.stream()
                        .filter(TcpClient::isAuthenticated)
                        .limit(Math.max(1, config.senders))
                        .toList();
                if (activeSenders.isEmpty()) {
                    return;
                }
                TcpClient sender = activeSenders.get(
                        Math.floorMod(senderIndex.getAndIncrement(), activeSenders.size()));
                String clientMessageId = "load-" + UUID.randomUUID();
                String requestId = UUID.randomUUID().toString();
                try {
                    sender.sendMessage(
                            requestId,
                            clientMessageId,
                            config.receiverId,
                            config.messageSize);
                    metrics.sent.increment();
                } catch (RuntimeException exception) {
                    metrics.sendFailures.increment();
                    metrics.errors.increment();
                }
            }, 0, intervalNanos, TimeUnit.NANOSECONDS);
        }

        private String login(String username, String password) throws Exception {
            return login(username, password, config.httpUrl());
        }

        private String login(String username, String password, String baseUrl) throws Exception {
            String body = JSON.writeValueAsString(Map.of("username", username, "password", password));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/auth/login"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "login user=" + username + " HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = JSON.readTree(response.body());
            String token = root.path("token").asText("");
            if (token.isBlank()) {
                throw new IllegalStateException("login response did not contain token");
            }
            return token;
        }

        private Map<String, Object> buildResult(long startedAt) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("startedAt", startedAt);
            result.put("mode", config.mode.name().toLowerCase());
            result.put("host", config.host);
            result.put("tcpPort", config.port);
            result.put("httpPort", config.httpPort);
            result.put("receiverHost", config.receiverHost);
            result.put("receiverTcpPort", config.receiverPort);
            result.put("receiverHttpPort", config.receiverHttpPort);
            result.put("connectionsRequested", config.connections);
            result.put("connectionsSucceeded", metrics.connected.get());
            result.put("connectionsFailed", metrics.connectionFailures.get());
            result.put("durationSeconds", config.durationSeconds);
            result.put("warmupSeconds", config.warmupSeconds);
            result.put("senders", config.senders);
            result.put("messagesPerSecond", config.messagesPerSecond);
            result.put("messageSizeBytes", config.messageSize);
            result.put("sent", metrics.sent.sum());
            result.put("sendFailures", metrics.sendFailures.sum());
            result.put("sendResultReceived", metrics.sendResults.sum());
            result.put("pushReceived", metrics.pushReceived.sum());
            result.put("pushDuplicates", metrics.pushDuplicates.sum());
            result.put("acksSent", metrics.acksSent.sum());
            result.put("pongsReceived", metrics.pongsReceived.sum());
            result.put("protocolErrors", metrics.protocolErrors.sum());
            result.put("errors", metrics.errors.sum());
            result.put("connectionLatencyMs", summary(metrics.connectionLatencyNanos));
            result.put("sendResultLatencyMs", summary(metrics.sendResultLatencyNanos));
            result.put("pushLatencyMs", summary(metrics.pushLatencyNanos));
            result.put("elapsedSeconds", (System.currentTimeMillis() - startedAt) / 1000.0);
            return result;
        }

        private Map<String, Object> summary(List<Long> values) {
            if (values.isEmpty()) {
                return Map.of("count", 0);
            }
            List<Long> sorted = values.stream().sorted().toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", sorted.size());
            result.put("min", millis(sorted.get(0)));
            result.put("p50", millis(percentile(sorted, 0.50)));
            result.put("p95", millis(percentile(sorted, 0.95)));
            result.put("p99", millis(percentile(sorted, 0.99)));
            result.put("max", millis(sorted.get(sorted.size() - 1)));
            return result;
        }

        private long percentile(List<Long> sorted, double percentile) {
            int index = (int) Math.ceil(percentile * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }

        private double millis(long nanos) {
            return Math.round(nanos / 1_000_000.0 * 100.0) / 100.0;
        }

        private void closeClients() {
            clients.forEach(TcpClient::close);
        }
    }

    private static final class TcpClient {

        private final String host;
        private final int port;
        private final String token;
        private final String deviceId;
        private final String conversationType;
        private final long groupId;
        private final EventLoopGroup eventLoopGroup;
        private final Metrics metrics;
        private final ConcurrentMap<String, Long> sentAtNanos;
        private final ConcurrentMap<String, AtomicInteger> pushCountsByClientMessageId;
        private final int expectedPushesPerMessage;
        private final CountDownLatch connectAck = new CountDownLatch(1);
        private final AtomicBoolean authenticated = new AtomicBoolean();
        private volatile Channel channel;

        private TcpClient(
                String host,
                int port,
                String token,
                String deviceId,
                String conversationType,
                long groupId,
                EventLoopGroup eventLoopGroup,
                Metrics metrics,
                ConcurrentMap<String, Long> sentAtNanos,
                ConcurrentMap<String, AtomicInteger> pushCountsByClientMessageId,
                int expectedPushesPerMessage) {
            this.host = host;
            this.port = port;
            this.token = token;
            this.deviceId = deviceId;
            this.conversationType = conversationType;
            this.groupId = groupId;
            this.eventLoopGroup = eventLoopGroup;
            this.metrics = metrics;
            this.sentAtNanos = sentAtNanos;
            this.pushCountsByClientMessageId = pushCountsByClientMessageId;
            this.expectedPushesPerMessage = expectedPushesPerMessage;
        }

        private void connectAndAuthenticate() throws Exception {
            Bootstrap bootstrap = new Bootstrap()
                    .group(eventLoopGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ClientInitializer(this));
            channel = bootstrap.connect(host, port).sync().channel();
            MessageEnvelope connect = MessageEnvelope.newBuilder()
                    .setMessageType(MessageEnvelope.MessageType.CONNECT)
                    .setRequestId(UUID.randomUUID().toString())
                    .setTimestamp(System.currentTimeMillis())
                    .setPayload(ConnectRequest.newBuilder()
                            .setToken(token)
                            .setDeviceId(deviceId)
                            .build()
                            .toByteString())
                    .build();
            channel.writeAndFlush(connect).sync();
            if (!connectAck.await(10, TimeUnit.SECONDS) || !authenticated.get()) {
                throw new IllegalStateException("CONNECT_ACK not received");
            }
        }

        private void sendMessage(String requestId, String clientMessageId, long receiverId, int messageSize) {
            if (!isAuthenticated()) {
                throw new IllegalStateException("client is not authenticated");
            }
            String content = "x".repeat(Math.max(1, messageSize));
            MessageEnvelope envelope = MessageEnvelope.newBuilder()
                    .setMessageType(MessageEnvelope.MessageType.SEND_MESSAGE)
                    .setRequestId(requestId)
                    .setTimestamp(System.currentTimeMillis())
                    .setPayload(SendMessageRequest.newBuilder()
                            .setClientMessageId(clientMessageId)
                            .setReceiverId(conversationType.equals("GROUP") ? 0L : receiverId)
                            .setContent(content)
                            .setMessageType("TEXT")
                            .setConversationType(conversationType)
                            .setGroupId(groupId)
                            .build()
                            .toByteString())
                    .build();
            sentAtNanos.put(clientMessageId, System.nanoTime());
            pushCountsByClientMessageId.put(clientMessageId, new AtomicInteger());
            channel.writeAndFlush(envelope);
        }

        private boolean isAuthenticated() {
            return authenticated.get() && channel != null && channel.isActive();
        }

        private void onEnvelope(MessageEnvelope envelope) {
            try {
                if (envelope.getMessageType() == MessageEnvelope.MessageType.CONNECT_ACK) {
                    ConnectAck.parseFrom(envelope.getPayload());
                    authenticated.set(true);
                    connectAck.countDown();
                    return;
                }
                if (envelope.getMessageType() == MessageEnvelope.MessageType.PONG) {
                    metrics.pongsReceived.increment();
                    return;
                }
                if (envelope.getMessageType() == MessageEnvelope.MessageType.SEND_RESULT) {
                    SendResult result = SendResult.parseFrom(envelope.getPayload());
                    metrics.sendResults.increment();
                    Long started = sentAtNanos.get(result.getClientMessageId());
                    if (started != null) {
                        metrics.sendResultLatencyNanos.add(System.nanoTime() - started);
                    }
                    return;
                }
                if (envelope.getMessageType() == MessageEnvelope.MessageType.PUSH_MESSAGE) {
                    PushMessage push = PushMessage.parseFrom(envelope.getPayload());
                    metrics.pushReceived.increment();
                    Long started = sentAtNanos.get(push.getClientMessageId());
                    if (started != null) {
                        metrics.pushLatencyNanos.add(System.nanoTime() - started);
                    }
                    if (!metrics.seenPushMessageIds.add(deviceId + "|" + push.getMessageId())) {
                        metrics.pushDuplicates.increment();
                    }
                    AtomicInteger pushCount = pushCountsByClientMessageId.get(push.getClientMessageId());
                    if (pushCount != null && pushCount.incrementAndGet() >= expectedPushesPerMessage) {
                        sentAtNanos.remove(push.getClientMessageId());
                        pushCountsByClientMessageId.remove(push.getClientMessageId());
                    }
                    MessageEnvelope ack = MessageEnvelope.newBuilder()
                            .setMessageType(MessageEnvelope.MessageType.MESSAGE_ACK)
                            .setRequestId(UUID.randomUUID().toString())
                            .setTimestamp(System.currentTimeMillis())
                            .setPayload(MessageAck.newBuilder()
                                    .setMessageId(push.getMessageId())
                                    .setConversationId(push.getConversationId())
                                    .setSequence(push.getSequence())
                                    .build()
                                    .toByteString())
                            .build();
                    channel.writeAndFlush(ack);
                    metrics.acksSent.increment();
                    return;
                }
                if (envelope.getMessageType() == MessageEnvelope.MessageType.ERROR) {
                    metrics.protocolErrors.increment();
                    ErrorPayload error = ErrorPayload.parseFrom(envelope.getPayload());
                    if (metrics.protocolErrorLogs.incrementAndGet() <= 5) {
                        System.err.printf(
                                "server ERROR code=%s message=%s%n",
                                error.getCode(),
                                error.getMessage());
                    }
                }
            } catch (Exception exception) {
                metrics.protocolErrors.increment();
            }
        }

        private void sendPing() {
            if (!isAuthenticated()) {
                return;
            }
            channel.writeAndFlush(MessageEnvelope.newBuilder()
                    .setMessageType(MessageEnvelope.MessageType.PING)
                    .setRequestId(UUID.randomUUID().toString())
                    .setTimestamp(System.currentTimeMillis())
                    .build());
        }

        private void close() {
            Channel current = channel;
            if (current != null) {
                current.close();
            }
        }
    }

    private static final class ClientInitializer extends io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel> {

        private final TcpClient client;

        private ClientInitializer(TcpClient client) {
            this.client = client;
        }

        @Override
        protected void initChannel(io.netty.channel.socket.SocketChannel channel) {
            channel.pipeline()
                    .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(1_048_576, 0, 4, 0, 4))
                    .addLast("protobufDecoder", new ProtobufDecoder(MessageEnvelope.getDefaultInstance()))
                    .addLast("frameEncoder", new LengthFieldPrepender(4))
                    .addLast("protobufEncoder", new ProtobufEncoder())
                    .addLast("collector", new SimpleChannelInboundHandler<MessageEnvelope>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext context, MessageEnvelope message) {
                            client.onEnvelope(message);
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                            client.metrics.errors.increment();
                            context.close();
                        }
                    });
        }
    }

    private static final class Metrics {

        private final AtomicInteger connected = new AtomicInteger();
        private final AtomicInteger connectionFailures = new AtomicInteger();
        private final LongAdder sent = new LongAdder();
        private final LongAdder sendFailures = new LongAdder();
        private final LongAdder sendResults = new LongAdder();
        private final LongAdder pushReceived = new LongAdder();
        private final LongAdder pushDuplicates = new LongAdder();
        private final LongAdder acksSent = new LongAdder();
        private final LongAdder pongsReceived = new LongAdder();
        private final LongAdder protocolErrors = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final AtomicInteger protocolErrorLogs = new AtomicInteger();
        private final List<Long> connectionLatencyNanos = new CopyOnWriteArrayList<>();
        private final List<Long> sendResultLatencyNanos = new CopyOnWriteArrayList<>();
        private final List<Long> pushLatencyNanos = new CopyOnWriteArrayList<>();
        private final Set<String> seenPushMessageIds = ConcurrentHashMap.newKeySet();

        private void reset() {
            sent.reset();
            sendFailures.reset();
            sendResults.reset();
            pushReceived.reset();
            pushDuplicates.reset();
            acksSent.reset();
            pongsReceived.reset();
            protocolErrors.reset();
            errors.reset();
            protocolErrorLogs.set(0);
            sendResultLatencyNanos.clear();
            pushLatencyNanos.clear();
            seenPushMessageIds.clear();
        }
    }

    private record Result(Map<String, Object> values, boolean failed) {
    }

    private enum Mode {
        CONNECTIONS,
        CHAT;

        private static Mode parse(String value) {
            return valueOf(value.trim().toUpperCase());
        }
    }

    private static final class Config {

        private final String host;
        private final int port;
        private final int httpPort;
        private final String receiverHost;
        private final int receiverPort;
        private final int receiverHttpPort;
        private final Mode mode;
        private final int connections;
        private final int durationSeconds;
        private final int warmupSeconds;
        private final int senders;
        private final int messagesPerSecond;
        private final int messageSize;
        private final long receiverId;
        private final long groupId;
        private final String conversationType;
        private final String username;
        private final String password;
        private final String devicePrefix;
        private final String receiverPassword;
        private final List<String> receiverUsernames;
        private final String receiverDevicePrefix;

        private Config(Map<String, String> values) {
            host = values.getOrDefault("host", "127.0.0.1");
            port = integer(values, "port", 9000);
            httpPort = integer(values, "http-port", 8080);
            receiverHost = values.getOrDefault("receiver-host", host);
            receiverPort = integer(values, "receiver-port", port);
            receiverHttpPort = integer(values, "receiver-http-port", httpPort);
            mode = Mode.parse(values.getOrDefault("mode", "connections"));
            connections = positive(values, "connections", 10);
            durationSeconds = positive(values, "duration", 20);
            warmupSeconds = nonNegative(values, "warmup", 3);
            senders = positive(values, "senders", Math.min(connections, 4));
            messagesPerSecond = nonNegative(values, "mps", 0);
            messageSize = positive(values, "message-size", 32);
            receiverId = longValue(values, "receiver-id", 1002L);
            groupId = longValue(values, "group-id", 0L);
            conversationType = values.getOrDefault(
                    "conversation-type",
                    groupId > 0 ? "GROUP" : "DIRECT").trim().toUpperCase();
            username = values.getOrDefault("username", "alice");
            password = values.getOrDefault("password", "password123");
            devicePrefix = values.getOrDefault("device-prefix", "load-" + System.currentTimeMillis() + "-");
            receiverPassword = values.getOrDefault("receiver-password", password);
            receiverUsernames = receiverUsernames(values);
            receiverDevicePrefix = values.getOrDefault(
                    "receiver-device-prefix",
                    "load-receiver-" + System.currentTimeMillis() + "-");
        }

        private static Config from(String[] args) {
            Map<String, String> values = new HashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--")) {
                    continue;
                }
                String[] pair = arg.substring(2).split("=", 2);
                values.put(pair[0], pair.length == 2 ? pair[1] : "true");
            }
            return new Config(values);
        }

        private static int integer(Map<String, String> values, String key, int fallback) {
            return values.containsKey(key) ? Integer.parseInt(values.get(key)) : fallback;
        }

        private static int positive(Map<String, String> values, String key, int fallback) {
            int value = integer(values, key, fallback);
            if (value <= 0) {
                throw new IllegalArgumentException("--" + key + " must be greater than zero");
            }
            return value;
        }

        private static int nonNegative(Map<String, String> values, String key, int fallback) {
            int value = integer(values, key, fallback);
            if (value < 0) {
                throw new IllegalArgumentException("--" + key + " must not be negative");
            }
            return value;
        }

        private static long longValue(Map<String, String> values, String key, long fallback) {
            return values.containsKey(key) ? Long.parseLong(values.get(key)) : fallback;
        }

        private static List<String> receiverUsernames(Map<String, String> values) {
            String multiple = values.getOrDefault("receiver-usernames", "");
            if (!multiple.isBlank()) {
                return Arrays.stream(multiple.split(","))
                        .map(String::trim)
                        .filter(item -> !item.isBlank())
                        .toList();
            }
            String single = values.getOrDefault("receiver-username", "");
            if (single.isBlank()) {
                return List.of();
            }
            int count = positive(values, "receiver-connections", 1);
            return java.util.stream.IntStream.range(0, count)
                    .mapToObj(ignored -> single)
                    .toList();
        }

        private String httpUrl() {
            return "http://" + host + ":" + httpPort;
        }

        private String receiverHttpUrl() {
            return "http://" + receiverHost + ":" + receiverHttpPort;
        }

        private int expectedPushesPerMessage() {
            if ("GROUP".equals(conversationType)) {
                return Math.max(1, receiverUsernames.size() + 1);
            }
            return Math.max(1, receiverUsernames.isEmpty() ? 1 : receiverUsernames.size());
        }
    }
}
