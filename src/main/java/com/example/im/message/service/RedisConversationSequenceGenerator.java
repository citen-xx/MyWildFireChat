package com.example.im.message.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "im.sequence.redis-enabled", havingValue = "true", matchIfMissing = true)
public class RedisConversationSequenceGenerator implements ConversationSequenceGenerator {

    private final StringRedisTemplate redisTemplate;

    public RedisConversationSequenceGenerator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public long nextSequence(Long conversationId) {
        Long sequence = redisTemplate.opsForValue().increment("im:seq:" + conversationId);
        if (sequence == null) {
            throw new IllegalStateException("Failed to allocate conversation sequence");
        }
        return sequence;
    }
}
