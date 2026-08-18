package com.example.im.conversation.model;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("conversation_member")
public class ConversationMember {

    private Long conversationId;
    private Long userId;
    private String role;
    private String status;
    private Long joinSequence;
    private Long leaveSequence;
    private LocalDateTime joinedAt;
    private LocalDateTime leftAt;

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getJoinSequence() {
        return joinSequence;
    }

    public void setJoinSequence(Long joinSequence) {
        this.joinSequence = joinSequence;
    }

    public Long getLeaveSequence() {
        return leaveSequence;
    }

    public void setLeaveSequence(Long leaveSequence) {
        this.leaveSequence = leaveSequence;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    public LocalDateTime getLeftAt() {
        return leftAt;
    }

    public void setLeftAt(LocalDateTime leftAt) {
        this.leftAt = leftAt;
    }
}
