package com.example.im.mq;

public interface RelayDeliveryDeduplicator {

    boolean tryStart(String eventId);

    void release(String eventId);
}
