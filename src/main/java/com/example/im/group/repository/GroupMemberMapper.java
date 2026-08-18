package com.example.im.group.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.im.group.model.GroupMember;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GroupMemberMapper extends BaseMapper<GroupMember> {
}
