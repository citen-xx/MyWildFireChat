package com.example.im.message.ack;

import com.example.im.conversation.service.ConversationService;
import com.example.im.message.service.MessageService;
import com.example.im.message.service.SendMessageResult;
import com.example.im.mq.RemoteMessageRelayEvent;
import com.example.im.mq.RemoteMessageRelayPublisher;
import com.example.im.netty.session.ClientConnection;
import com.example.im.netty.session.SessionManager;
import com.example.im.route.ConnectionLocation;
import com.example.im.route.ConnectionLocationType;
import com.example.im.route.ConnectionLocator;
import com.example.im.route.ServerProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnBean(MessageService.class)
@ConditionalOnProperty(name = "im.ack.retry-enabled", havingValue = "true", matchIfMissing = true)
public class AckRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AckRetryScheduler.class);

    private final PendingAckRepository pendingAckRepository;
    private final AckService ackService;
    private final MessageService messageService;
    private final SessionManager sessionManager;
    private final AckProperties properties;
    private final ConversationService conversationService;
    private final ConnectionLocator connectionLocator;
    private final RemoteMessageRelayPublisher relayPublisher;
    private final ServerProperties serverProperties;
    private ScheduledExecutorService executor;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public AckRetryScheduler(
            PendingAckRepository pendingAckRepository,
            AckService ackService,
            MessageService messageService,
            SessionManager sessionManager,
            AckProperties properties,
            ConversationService conversationService,
            ConnectionLocator connectionLocator,
            RemoteMessageRelayPublisher relayPublisher,
            ServerProperties serverProperties) {
        this.pendingAckRepository = pendingAckRepository;
        this.ackService = ackService;
        this.messageService = messageService;
        this.sessionManager = sessionManager;
        this.properties = properties;
        this.conversationService = conversationService;
        this.connectionLocator = connectionLocator;
        this.relayPublisher = relayPublisher;
        this.serverProperties = serverProperties;
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "im-ack-retry");
            thread.setDaemon(true);
            return thread;
        });
        long interval = Math.max(properties.getScanIntervalMillis(), 50L);
        executor.scheduleWithFixedDelay(this::scanSafely, interval, interval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        shuttingDown.set(true);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void scanSafely() {
        if (shuttingDown.get()) {
            return;
        }
        try {
            scanOnce();
        } catch (Exception exception) {
            log.warn("failed to scan pending acknowledgements", exception);
        }
    }

    public void scanOnce() {
        List<PendingAck> dueItems = pendingAckRepository.findDue(
                System.currentTimeMillis(),
                Math.max(properties.getScanLimit(), 1),
                serverProperties.getId());
        for (PendingAck pendingAck : dueItems) {
            retry(pendingAck);
        }
    }

    private void retry(PendingAck pendingAck) {
        ConnectionLocation location = connectionLocator.locate(
                pendingAck.userId(),
                pendingAck.deviceId());
        if (location.type() == ConnectionLocationType.OFFLINE) {
            pendingAckRepository.remove(pendingAck.userId(), pendingAck.deviceId(), pendingAck.messageId());
            log.info("message retry stopped because device is offline messageId={} userId={} deviceId={} attempt={}",
                    pendingAck.messageId(), pendingAck.userId(), pendingAck.deviceId(), pendingAck.attempt());
            return;
        }

        Optional<SendMessageResult> message = messageService.findResultByMessageId(pendingAck.messageId());
        if (message.isEmpty()) {
            pendingAckRepository.remove(pendingAck.userId(), pendingAck.deviceId(), pendingAck.messageId());
            log.warn("message retry skipped because message is missing messageId={} userId={} deviceId={} attempt={}",
                    pendingAck.messageId(), pendingAck.userId(), pendingAck.deviceId(), pendingAck.attempt());
            return;
        }

        if (!conversationService.isMember(message.get().conversationId(), pendingAck.userId())) {
            pendingAckRepository.remove(pendingAck.userId(), pendingAck.deviceId(), pendingAck.messageId());
            log.info("message retry stopped because member is inactive messageId={} userId={} deviceId={} attempt={}",
                    pendingAck.messageId(), pendingAck.userId(), pendingAck.deviceId(), pendingAck.attempt());
            return;
        }

        if (location.type() == ConnectionLocationType.REMOTE) {
            handoffRemote(pendingAck, message.get(), location);
            return;
        }

        retryLocal(pendingAck, message.get(), location);
    }

    private void handoffRemote(
            PendingAck pendingAck,
            SendMessageResult message,
            ConnectionLocation location) {
        int nextAttempt = pendingAck.attempt() + 1;
        int nextHopCount = pendingAck.hopCount() + 1;
        if (nextHopCount > Math.max(properties.getMaxRelayHops(), 0)) {
            pendingAckRepository.remove(pendingAck.userId(), pendingAck.deviceId(), pendingAck.messageId());
            log.warn("delivery relay exhausted messageId={} deliveryId={} userId={} deviceId={} ownerServerId={} targetServerId={} attempt={} hopCount={}",
                    pendingAck.messageId(), pendingAck.deliveryId(), pendingAck.userId(), pendingAck.deviceId(),
                    serverProperties.getId(), location.route().serverId(), nextAttempt, nextHopCount);
            return;
        }
        String deliveryId = pendingAck.deliveryId() == null
                ? RemoteMessageRelayEvent.stableDeliveryId(
                message, pendingAck.userId(), pendingAck.deviceId())
                : pendingAck.deliveryId();
        boolean published = relayPublisher.publish(
                message,
                location.route(),
                deliveryId,
                nextAttempt,
                nextHopCount);
        if (published) {
            pendingAckRepository.remove(
                    pendingAck.userId(),
                    pendingAck.deviceId(),
                    pendingAck.messageId());
            log.info("delivery migrated messageId={} deliveryId={} userId={} deviceId={} ownerServerId={} targetServerId={} attempt={} hopCount={}",
                    pendingAck.messageId(), deliveryId, pendingAck.userId(), pendingAck.deviceId(),
                    serverProperties.getId(), location.route().serverId(), nextAttempt, nextHopCount);
            return;
        }
        rescheduleWithoutAttempt(pendingAck);
    }

    private void retryLocal(
            PendingAck pendingAck,
            SendMessageResult message,
            ConnectionLocation location) {
        int nextAttempt = pendingAck.attempt() + 1;
        try {
            location.connection().sendPush(message);
            String deliveryId = pendingAck.deliveryId() == null
                    ? RemoteMessageRelayEvent.stableDeliveryId(
                    message, pendingAck.userId(), pendingAck.deviceId())
                    : pendingAck.deliveryId();
            if (nextAttempt >= properties.maxRetryAttempts()) {
                pendingAckRepository.remove(
                        pendingAck.userId(),
                        pendingAck.deviceId(),
                        pendingAck.messageId());
                log.info("message retry exhausted messageId={} deliveryId={} userId={} deviceId={} attempt={}",
                        pendingAck.messageId(), deliveryId, pendingAck.userId(), pendingAck.deviceId(), nextAttempt);
                return;
            }
            ackService.recordPush(
                    message,
                    pendingAck.userId(),
                    pendingAck.deviceId(),
                    location.route().connectionId(),
                    location.route().serverId(),
                    deliveryId,
                    nextAttempt,
                    pendingAck.hopCount());
            log.info("delivery retry messageId={} deliveryId={} userId={} deviceId={} ownerServerId={} attempt={} hopCount={}",
                    pendingAck.messageId(), deliveryId, pendingAck.userId(), pendingAck.deviceId(),
                    serverProperties.getId(), nextAttempt, pendingAck.hopCount());
        } catch (Exception exception) {
            log.warn("message retry failed messageId={} deliveryId={} userId={} deviceId={} attempt={}",
                    pendingAck.messageId(), pendingAck.deliveryId(), pendingAck.userId(), pendingAck.deviceId(),
                    nextAttempt, exception);
            rescheduleWithoutAttempt(pendingAck);
        }
    }

    private void rescheduleWithoutAttempt(PendingAck pendingAck) {
        long nextRetryAt = System.currentTimeMillis()
                + properties.delayForAttempt(pendingAck.attempt());
        pendingAckRepository.save(new PendingAck(
                pendingAck.userId(),
                pendingAck.deviceId(),
                pendingAck.messageId(),
                nextRetryAt,
                pendingAck.attempt(),
                pendingAck.connectionId(),
                pendingAck.ownerServerId(),
                pendingAck.deliveryId(),
                pendingAck.hopCount()));
    }
}
