package com.example.im.route;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RouteRegistryConfiguration {

    @Bean
    public RouteRegistry routeRegistry(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ServerProperties serverProperties,
            @Value("${im.route.redis-enabled:true}") boolean redisEnabled) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisEnabled && redisTemplate != null) {
            return new RedisRouteRegistry(redisTemplate, serverProperties);
        }
        return new NoopRouteRegistry();
    }

    @Bean
    public ServerRegistry serverRegistry(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${im.route.redis-enabled:true}") boolean redisEnabled) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisEnabled && redisTemplate != null) {
            return new RedisServerRegistry(redisTemplate);
        }
        return new NoopServerRegistry();
    }
}
