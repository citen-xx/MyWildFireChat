package com.example.im.auth.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.im.auth.model.UserAccount;
import com.example.im.auth.service.UserCredential;
import com.example.im.auth.service.UserCredentialReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "im.auth.database-enabled", havingValue = "true", matchIfMissing = true)
public class DatabaseUserCredentialReader implements UserCredentialReader {

    private final UserAccountMapper userAccountMapper;

    public DatabaseUserCredentialReader(UserAccountMapper userAccountMapper) {
        this.userAccountMapper = userAccountMapper;
    }

    @Override
    public Optional<UserCredential> findByUsername(String username) {
        UserAccount user = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUsername, username)
                .last("limit 1"));
        if (user == null) {
            return Optional.empty();
        }
        return Optional.of(new UserCredential(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getStatus()));
    }
}
