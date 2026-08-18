package com.example.im;

import com.example.im.auth.security.JwtProperties;
import com.example.im.message.ack.AckProperties;
import com.example.im.netty.server.NettyProperties;
import com.example.im.route.ServerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({NettyProperties.class, JwtProperties.class, AckProperties.class, ServerProperties.class})
public class ImApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImApplication.class, args);
    }
}
