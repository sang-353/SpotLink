package com.sang.config;

import com.sang.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.sang.utils.SystemConstants.SECKILL_QUEUE;


@Configuration
@Slf4j
public class RabbitMQConfig {
    // ========================死信队列========================
    @Bean
    public DirectExchange seckillDLX() {
        return new DirectExchange(SystemConstants.SECKILL_DLX);
    }

    @Bean
    public Queue seckillDLXQueue() {
        return new Queue(SystemConstants.SECKILL_DLQ);
    }

    @Bean
    public Binding dlxBinding() {
        return BindingBuilder.bind(seckillDLXQueue())
                .to(seckillDLX())
                .with(SystemConstants.SECKILL_DLX_ROUTING_KEY);
    }

    // ========================业务队列========================
    @Bean
    public DirectExchange seckillExchange() {
        log.info("Initializing RabbitMQ DirectExchange: {}", SystemConstants.SECKILL_EXCHANGE);
        return new DirectExchange(SystemConstants.SECKILL_EXCHANGE);
    }

    @Bean
    public Queue seckillQueue() {
        log.info("Initializing RabbitMQ Queue: {}", SECKILL_QUEUE);
        return QueueBuilder.durable(SECKILL_QUEUE)
                .deadLetterExchange(SystemConstants.SECKILL_DLX)
                .deadLetterRoutingKey(SystemConstants.SECKILL_DLX_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding seckillBinding() {
        return BindingBuilder.bind(seckillQueue())
                .to(seckillExchange())
                .with(SystemConstants.SECKILL_ROUTING_KEY);
    }

    // ========================消息转换器========================
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ==================== RabbitTemplate ====================
    /*@PostConstruct
    public void initRabbitTemplate() {
        // 设置confirmcallback
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            // 获取消息的唯一ID（前提是发送时传入了 CorrelationData）
            String msgId = (correlationData != null) ? correlationData.getId() : "未知ID";
            if (ack) {
                log.info("【RabbitMQ 确认】消息成功到达交换机，消息ID: {}", msgId);
            } else {
                log.error("【RabbitMQ 确认】消息未能到达交换机！消息ID: {}, 失败原因: {}", msgId, cause);
            }
        });

        // 设置returncallback （没必要）
        rabbitTemplate.setReturnsCallback(returned -> {
            log.error("【RabbitMQ 退回】消息成功投递到交换机，但未能路由到任何队列！" +
                            "状态码: {}, 原因: {}, 交换机: {}, 路由键: {}, 消息体: {}",
                    returned.getReplyCode(),
                    returned.getReplyText(),
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    new String(returned.getMessage().getBody()));
        });
    }*/
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack && correlationData != null) {
                log.error("消息未到达Exchange: id={}, cause={}", correlationData.getId(), cause);
            }
        });
        return template;
    }
}
