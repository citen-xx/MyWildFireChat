package com.example.im.group.service;

import java.util.List;

public record CreateGroupCommand(
        String name,
        List<Long> memberIds) {
}
