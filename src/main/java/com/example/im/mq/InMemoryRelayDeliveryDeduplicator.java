package com.example.im.mq;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "im.mq.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnMissingBean(StringRedisTemplate.class)
public class InMemoryRelayDeliveryDeduplicator implements RelayDeliveryDeduplicator {

    private final Set<String> processedDeliveryIds = ConcurrentHashMap.newKeySet();

    @Override
    public boolean tryStart(String deliveryId) {
        return deliveryId != null
                && !deliveryId.isBlank()
                && processedDeliveryIds.add(deliveryId);
    }
}
