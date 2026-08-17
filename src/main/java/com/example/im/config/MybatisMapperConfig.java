package com.example.im.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "im.mybatis.enabled", havingValue = "true", matchIfMissing = true)
@MapperScan(basePackages = {
        "com.example.im.auth.repository",
        "com.example.im.conversation.repository",
        "com.example.im.message.repository"
})
public class MybatisMapperConfig {
}
