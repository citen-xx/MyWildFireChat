package com.example.im.group.service;

public record GroupSummary(
        Long groupId,
        Long conversationId,
        String groupName,
        String role,
        int memberCount,
        String status) {
}
