package com.example.im.mq;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnBean(StringRedisTemplate.class)
@ConditionalOnProperty(name = "im.mq.enabled", havingValue = "true", matchIfMissing = true)
public class RedisRelayDeliveryDeduplicator implements RelayDeliveryDeduplicator {

    private static final String KEY_PREFIX = "im:relay:delivery:";

    private final StringRedisTemplate redisTemplate;
    private final RabbitMqProperties properties;

    public RedisRelayDeliveryDeduplicator(
            StringRedisTemplate redisTemplate,
            RabbitMqProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public boolean tryStart(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            return false;
        }
        Boolean created = redisTemplate.opsForValue().setIfAbsent(
                KEY_PREFIX + deliveryId,
                "1",
                Duration.ofSeconds(Math.max(properties.getDeliveryDedupTtlSeconds(), 1L)));
        return Boolean.TRUE.equals(created);
    }
}
