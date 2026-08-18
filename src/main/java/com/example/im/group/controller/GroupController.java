package com.example.im.group.controller;

import com.example.im.auth.security.JwtService;
import com.example.im.common.exception.AuthException;
import com.example.im.group.service.AddGroupMembersCommand;
import com.example.im.group.service.CreateGroupCommand;
import com.example.im.group.service.GroupMemberView;
import com.example.im.group.service.GroupService;
import com.example.im.group.service.GroupSummary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/groups")
@ConditionalOnProperty(name = "im.chat.enabled", havingValue = "true", matchIfMissing = true)
public class GroupController {

    private final GroupService groupService;
    private final JwtService jwtService;

    public GroupController(GroupService groupService, JwtService jwtService) {
        this.groupService = groupService;
        this.jwtService = jwtService;
    }

    @PostMapping
    public GroupSummary createGroup(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody CreateGroupCommand command) {
        return groupService.createGroup(currentUserId(authorization), command);
    }

    @GetMapping
    public List<GroupSummary> listGroups(
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        return groupService.listGroups(currentUserId(authorization));
    }

    @GetMapping("/{groupId}/members")
    public List<GroupMemberView> listMembers(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable Long groupId) {
        return groupService.listMembers(groupId, currentUserId(authorization));
    }

    @PostMapping("/{groupId}/members")
    public GroupSummary addMembers(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable Long groupId,
            @RequestBody AddGroupMembersCommand command) {
        return groupService.addMembers(groupId, currentUserId(authorization), command);
    }

    @PostMapping("/{groupId}/leave")
    public void leaveGroup(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable Long groupId) {
        groupService.leaveGroup(groupId, currentUserId(authorization));
    }

    @DeleteMapping("/{groupId}")
    public void disbandGroup(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable Long groupId) {
        groupService.disbandGroup(groupId, currentUserId(authorization));
    }

    private Long currentUserId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AuthException("INVALID_TOKEN", "Authorization Bearer token is required");
        }
        return jwtService.verify(authorization.substring("Bearer ".length())).userId();
    }
}
