package com.example.im.message.ack;

import java.util.List;

public interface PendingAckRepository {

    void save(PendingAck pendingAck);

    void remove(Long userId, String deviceId, String messageId);

    List<PendingAck> findDue(long nowMillis, int limit);

    boolean exists(Long userId, String deviceId, String messageId);

    long count();
}
