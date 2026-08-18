package com.example.im.group.service;

import java.util.List;

public record GroupSendContext(
        Long groupId,
        Long conversationId,
        List<Long> activeMemberIds) {
}
