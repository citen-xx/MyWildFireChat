package com.example.im.message.ack;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "im.ack")
public class AckProperties {

    private boolean retryEnabled = true;
    private boolean redisEnabled = true;
    private List<Long> retryDelaysMillis = new ArrayList<>(List.of(3000L, 3000L, 5000L));
    private long scanIntervalMillis = 1000L;
    private int scanLimit = 100;

    public boolean isRetryEnabled() {
        return retryEnabled;
    }

    public void setRetryEnabled(boolean retryEnabled) {
        this.retryEnabled = retryEnabled;
    }

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public void setRedisEnabled(boolean redisEnabled) {
        this.redisEnabled = redisEnabled;
    }

    public List<Long> getRetryDelaysMillis() {
        return retryDelaysMillis;
    }

    public void setRetryDelaysMillis(List<Long> retryDelaysMillis) {
        this.retryDelaysMillis = retryDelaysMillis == null ? List.of() : new ArrayList<>(retryDelaysMillis);
    }

    public long getScanIntervalMillis() {
        return scanIntervalMillis;
    }

    public void setScanIntervalMillis(long scanIntervalMillis) {
        this.scanIntervalMillis = scanIntervalMillis;
    }

    public int getScanLimit() {
        return scanLimit;
    }

    public void setScanLimit(int scanLimit) {
        this.scanLimit = scanLimit;
    }

    public int maxRetryAttempts() {
        return retryDelaysMillis.size();
    }

    public long delayForAttempt(int attempt) {
        if (retryDelaysMillis.isEmpty()) {
            return 0L;
        }
        int index = Math.min(Math.max(attempt, 0), retryDelaysMillis.size() - 1);
        return retryDelaysMillis.get(index);
    }
}
