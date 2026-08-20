package com.example.im.mq;

import com.example.im.route.ServerProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableRabbit
@ConditionalOnProperty(name = "im.mq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqRelayConfiguration {

    @Bean
    DirectExchange messageRelayExchange(RabbitMqProperties properties) {
        return new DirectExchange(properties.getExchange(), true, false);
    }

    @Bean
    Queue messageRelayQueue(RabbitMqProperties properties, ServerProperties serverProperties) {
        return QueueBuilder.durable(properties.queueName(serverProperties.getId())).build();
    }

    @Bean
    Binding messageRelayBinding(
            Queue messageRelayQueue,
            DirectExchange messageRelayExchange,
            ServerProperties serverProperties) {
        return BindingBuilder.bind(messageRelayQueue)
                .to(messageRelayExchange)
                .with(serverProperties.getId());
    }

    @Bean
    MessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        return factory;
    }
}
