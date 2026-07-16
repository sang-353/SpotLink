package com.sang.config;

import com.rabbitmq.client.Channel;
import com.sang.dto.OrderDelayMessage;
import com.sang.entity.VoucherOrder;
import com.sang.service.ISeckillVoucherService;
import com.sang.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import static com.sang.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.sang.utils.SystemConstants.ORDER_DELAY_QUEUE;

/**
 * 订单超时取消监听器 — 15 分钟后检查订单状态，未支付则自动取消并回滚库存
 *
 * <p>需 RabbitMQ 安装 rabbitmq_delayed_message_exchange 插件</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spotlink.order.delay.enabled", havingValue = "true")
public class OrderDelayListener {

    private final IVoucherOrderService voucherOrderService;
    private final ISeckillVoucherService seckillVoucherService;
    private final StringRedisTemplate stringRedisTemplate;

    public OrderDelayListener(IVoucherOrderService voucherOrderService,
                              ISeckillVoucherService seckillVoucherService,
                              StringRedisTemplate stringRedisTemplate) {
        this.voucherOrderService = voucherOrderService;
        this.seckillVoucherService = seckillVoucherService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @RabbitListener(queues = ORDER_DELAY_QUEUE, ackMode = "MANUAL")
    public void onOrderDelayTimeout(
            OrderDelayMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {

        Long orderId = message.getOrderId();
        try {
            VoucherOrder order = voucherOrderService.getById(orderId);
            if (order == null) {
                log.warn("延迟取消：订单不存在 orderId={}", orderId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 仅取消未支付的订单（status=1）
            if (order.getStatus() == 1) {
                // ① 更新订单状态为"已取消"
                order.setStatus(4);
                voucherOrderService.updateById(order);

                // ② 回滚秒杀库存
                seckillVoucherService.update()
                        .setSql("stock = stock + 1")
                        .eq("voucher_id", message.getVoucherId())
                        .update();

                // ② bis 回滚 Redis 库存（Lua 脚本中 INCRBY -1 的逆操作）
                stringRedisTemplate.opsForValue()
                        .increment(SECKILL_STOCK_KEY + message.getVoucherId());

                // ③ 从 Redis Set 移除，允许用户重新秒杀该券
                stringRedisTemplate.opsForSet()
                        .remove("seckill:order" + message.getVoucherId(),
                                message.getUserId().toString());

                log.info("订单超时自动取消: orderId={}, userId={}, voucherId={}",
                        orderId, message.getUserId(), message.getVoucherId());
            } else {
                log.info("订单状态已变更，跳过取消: orderId={}, status={}", orderId, order.getStatus());
            }
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("订单超时取消异常: orderId={}", orderId, e);
            try {
                channel.basicReject(deliveryTag, false);
            } catch (Exception ignored) {
                // channel 可能已关闭
            }
        }
    }
}
