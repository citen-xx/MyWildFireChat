package com.example.im.mq;

public interface RelayDeliveryDeduplicator {

    boolean tryStart(String deliveryId);
}
