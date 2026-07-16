package com.sang.config;

import com.sang.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

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

    // ======================== 延迟队列（订单超时取消）========================
    /**
     * 延迟队列配置 — 需 RabbitMQ 安装 rabbitmq_delayed_message_exchange 插件
     * 未安装插件时设置 spotlink.order.delay.enabled=false 跳过此配置
     */
    @Configuration
    @ConditionalOnProperty(name = "spotlink.order.delay.enabled", havingValue = "true", matchIfMissing = false)
    public static class DelayQueueConfig {
        @Bean
        public CustomExchange orderDelayExchange() {
            return new CustomExchange(SystemConstants.ORDER_DELAY_EXCHANGE,
                    "x-delayed-message",        // 交换机类型
                    true,                        // 持久化
                    false,                       // 不自动删除
                    Map.of("x-delayed-type", "direct")  // 实际路由方式
            );
        }

        @Bean
        public Queue orderDelayQueue() {
            return new Queue(SystemConstants.ORDER_DELAY_QUEUE, true);
        }

        @Bean
        public Binding orderDelayBinding() {
            return BindingBuilder
                    .bind(orderDelayQueue())
                    .to(orderDelayExchange())
                    .with(SystemConstants.ORDER_DELAY_ROUTING_KEY)
                    .noargs();
        }
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
            if (ack) {
                log.info("消息已到达Exchange: id={}",
                        correlationData != null ? correlationData.getId() : "null");
            } else {
                log.error("消息未到达Exchange: id={}, cause={}", correlationData != null ? correlationData.getId() : "null", cause);
            }
        });
        return template;
    }
}
