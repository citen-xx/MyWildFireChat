package com.example.im.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.mq")
public class RabbitMqProperties {

    private boolean enabled = true;
    private String exchange = "im.message.relay";
    private String queuePrefix = "im.message.relay.";
    private long deliveryDedupTtlSeconds = 30L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getQueuePrefix() {
        return queuePrefix;
    }

    public void setQueuePrefix(String queuePrefix) {
        this.queuePrefix = queuePrefix;
    }

    public long getDeliveryDedupTtlSeconds() {
        return deliveryDedupTtlSeconds;
    }

    public void setDeliveryDedupTtlSeconds(long deliveryDedupTtlSeconds) {
        this.deliveryDedupTtlSeconds = deliveryDedupTtlSeconds;
    }

    public String queueName(String serverId) {
        return queuePrefix + serverId;
    }
}
