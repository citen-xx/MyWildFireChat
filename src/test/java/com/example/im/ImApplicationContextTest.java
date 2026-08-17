package com.example.im;

import com.example.im.auth.service.UserCredential;
import com.example.im.auth.service.UserCredentialReader;
import com.example.im.netty.server.NettyServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "im.netty.port=0",
                "im.auth.database-enabled=false",
                "im.chat.enabled=false",
                "im.mybatis.enabled=false",
                "im.sequence.redis-enabled=false",
                "im.websocket.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                        + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"
        })
class ImApplicationContextTest {

    @Autowired
    private NettyServer nettyServer;

    @Test
    void springContextShouldStartNettyServer() {
        assertThat(nettyServer.isRunning()).isTrue();
    }

    @TestConfiguration
    static class TestUsers {

        @Bean
        @Primary
        UserCredentialReader userCredentialReader() {
            return username -> Optional.of(new UserCredential(
                    1001L,
                    username,
                    "{noop}password123",
                    "ACTIVE"));
        }
    }
}
