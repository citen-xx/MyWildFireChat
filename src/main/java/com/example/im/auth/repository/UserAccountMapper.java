package com.example.im.auth.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.im.auth.model.UserAccount;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {
}
