package com.example.im.conversation.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.im.conversation.model.ConversationMember;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMemberMapper extends BaseMapper<ConversationMember> {
}
