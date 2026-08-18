package com.example.im.message.ack;

import com.example.im.conversation.service.ConversationService;
import com.example.im.message.service.MessageService;
import com.example.im.message.service.SendMessageResult;
import com.example.im.netty.session.ClientConnection;
import com.example.im.netty.session.SessionManager;
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

@Component
@ConditionalOnBean(MessageService.class)
@ConditionalOnProperty(name = "im.ack.retry-enabled", havingValue = "true", matchIfMissing = true)
public class AckRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AckRetryScheduler.class);

    private final PendingAckRepository pendingAckRepository;
    private final MessageService messageService;
    private final SessionManager sessionManager;
    private final AckProperties properties;
    private final ConversationService conversationService;
    private ScheduledExecutorService executor;

    public AckRetryScheduler(
            PendingAckRepository pendingAckRepository,
            MessageService messageService,
            SessionManager sessionManager,
            AckProperties properties,
            ConversationService conversationService) {
        this.pendingAckRepository = pendingAckRepository;
        this.messageService = messageService;
        this.sessionManager = sessionManager;
        this.properties = properties;
        this.conversationService = conversationService;
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
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void scanSafely() {
        try {
            scanOnce();
        } catch (Exception exception) {
            log.warn("failed to scan pending acknowledgements", exception);
        }
    }

    void scanOnce() {
        List<PendingAck> dueItems = pendingAckRepository.findDue(
                System.currentTimeMillis(),
                Math.max(properties.getScanLimit(), 1));
        for (PendingAck pendingAck : dueItems) {
            retry(pendingAck);
        }
    }

    private void retry(PendingAck pendingAck) {
        Optional<ClientConnection> connection = sessionManager.findConnection(
                pendingAck.userId(),
                pendingAck.deviceId());
        if (connection.isEmpty()) {
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

        int nextAttempt = pendingAck.attempt() + 1;
        connection.get().sendPush(message.get());
        log.info("message retry messageId={} userId={} deviceId={} attempt={}",
                pendingAck.messageId(), pendingAck.userId(), pendingAck.deviceId(), nextAttempt);

        if (nextAttempt >= properties.maxRetryAttempts()) {
            pendingAckRepository.remove(pendingAck.userId(), pendingAck.deviceId(), pendingAck.messageId());
            log.info("message retry exhausted messageId={} userId={} deviceId={} attempt={}",
                    pendingAck.messageId(), pendingAck.userId(), pendingAck.deviceId(), nextAttempt);
            return;
        }

        long nextRetryAt = System.currentTimeMillis() + properties.delayForAttempt(nextAttempt);
        pendingAckRepository.save(new PendingAck(
                pendingAck.userId(),
                pendingAck.deviceId(),
                pendingAck.messageId(),
                nextRetryAt,
                nextAttempt));
    }
}
