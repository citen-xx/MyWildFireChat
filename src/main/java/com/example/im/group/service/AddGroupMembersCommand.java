package com.example.im.group.service;

import java.util.List;

public record AddGroupMembersCommand(List<Long> memberIds) {
}
