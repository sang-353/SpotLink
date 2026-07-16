package com.sang.service.impl;

import com.rabbitmq.client.Channel;
import com.sang.dto.OrderDelayMessage;
import com.sang.dto.Result;
import com.sang.dto.SeckillOrderMessage;
import com.sang.entity.VoucherOrder;
import com.sang.mapper.VoucherOrderMapper;
import com.sang.service.ISeckillVoucherService;
import com.sang.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sang.utils.RedisConstants;
import com.sang.utils.RedisIdWorker;
import com.sang.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

import static com.sang.utils.RedisConstants.SECKILL_ORDER_DEDUP_KEY;
import static com.sang.utils.RedisConstants.SECKILL_ORDER_DEDUP_TTL;
import static com.sang.utils.SystemConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RabbitTemplate rabbitTemplate;

    /** 延迟队列功能开关（需 RabbitMQ 安装 delayed_message 插件） */
    @Value("${spotlink.order.delay.enabled:false}")
    private boolean orderDelayEnabled;

    /** 消费者处理最大重试次数 */
    private static final int MAX_RETRY_COUNT = 3;
    /** 生产者发送最大重试次数 */
    private static final int MAX_SEND_RETRY = 3;
    /** 发送重试间隔（毫秒） */
    private static final long SEND_RETRY_INTERVAL_MS = 200;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    // ==================== 秒杀入口 ====================
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        int r = result.intValue();
        // 判断结果是否为0
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 为0，有购买资格,生成订单 ID
        long orderId = redisIdWorker.nextId("order");
        // 发送消息到 RabbitMQ（带重试）
        SeckillOrderMessage message = new SeckillOrderMessage(orderId, userId, voucherId, 0);
        CorrelationData correlationData = new CorrelationData(String.valueOf(orderId));
        boolean sent = false;
        for (int attempt = 1; attempt <= MAX_SEND_RETRY; attempt++) {
            try {
                rabbitTemplate.convertAndSend(SECKILL_EXCHANGE, SECKILL_ROUTING_KEY, message, correlationData);
                sent = true;
                break;
            } catch (Exception e) {
                log.warn("秒杀消息发送失败 (第{}次): orderId={}, userId={}", attempt, orderId, userId, e);
                if (attempt < MAX_SEND_RETRY) {
                    try {
                        Thread.sleep(SEND_RETRY_INTERVAL_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (!sent) {
            // 重试耗尽 → 回滚 Redis 操作（补偿）
            log.error("秒杀消息发送失败（已达最大重试次数）: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
            rollbackSeckill(voucherId, userId);
            return Result.fail("系统繁忙，请稍后重试");
        }
        log.debug("秒杀消息已发送: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
        // 返回订单信息
        return Result.ok(orderId);
    }

    /** 消息发送失败时的 Redis 回滚 */
    private void rollbackSeckill(Long voucherId, Long userId) {
        stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
        stringRedisTemplate.opsForSet().remove("seckill:order" + voucherId, userId.toString());
    }
    // ==================== 消费者 ====================
    /**
     * RabbitMQ 监听器 — 异步创建订单
     *
     * <p>幂等机制：Redis SETNX 去重 + DB 主键兜底</p>
     * <p>重试策略：失败后重发至原队列（retryCount+1），超限后进入 DLQ</p>
     */
    @RabbitListener(queues = SECKILL_QUEUE, ackMode = "MANUAL")
    public void onSeckillOrderMessage(
            SeckillOrderMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {

        Long orderId = message.getOrderId();
        log.debug("收到秒杀消息: orderId={}, userId={}, voucherId={}, retryCount={}",
                orderId, message.getUserId(), message.getVoucherId(), message.getRetryCount());

        String dedupKey = SECKILL_ORDER_DEDUP_KEY + orderId;

        try {
            // ---- 步骤 1：幂等检查 ----
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(dedupKey, "1", Duration.ofSeconds(SECKILL_ORDER_DEDUP_TTL));
            if (Boolean.FALSE.equals(acquired)) {
                log.warn("订单已处理（幂等拦截），直接 ACK: orderId={}", orderId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ---- 步骤 2：处理订单（分布式锁 + 事务） ----
            handleVoucherOrder(message);

            // ---- 步骤 3：成功 → ACK ----
            channel.basicAck(deliveryTag, false);
            log.info("订单处理成功: orderId={}", orderId);

        } catch (Exception e) {
            log.error("订单处理失败: orderId={}, retryCount={}", orderId, message.getRetryCount(), e);

            // 删除幂等 Key，允许重试
            stringRedisTemplate.delete(dedupKey);

            try {
                int retried = message.getRetryCount() + 1;
                if (retried <= MAX_RETRY_COUNT) {
                    // 未达上限 → 重新发送到业务队列（重试）
                    message.setRetryCount(retried);
                    rabbitTemplate.convertAndSend(
                            SECKILL_EXCHANGE,
                            SECKILL_ROUTING_KEY,
                            message
                    );
                    log.warn("订单重试 {}/{}: orderId={}", retried, MAX_RETRY_COUNT, orderId);
                    channel.basicAck(deliveryTag, false);   // 确认原消息（已转存为新消息）
                } else {
                    // 达到上限 → 拒绝且不重入队 → 进入 DLQ
                    log.error("订单超过最大重试次数，进入死信队列: orderId={}", orderId);
                    channel.basicReject(deliveryTag, false); // requeue=false
                }
            } catch (Exception ackEx) {
                log.error("消息确认/重发异常: orderId={}", orderId, ackEx);
            }
        }
    }

    @RabbitListener(queues = SECKILL_DLQ)
    public void onSeckillDlqMessage(SeckillOrderMessage message) {
        // 死信消息仅做告警记录，后续由人工或补偿任务处理
        log.error("收到死信队列消息,订单需人工处理: orderId={}, userId={}, voucherId={}, retryCount={}",
                message.getOrderId(), message.getUserId(), message.getVoucherId(), message.getRetryCount());
    }

    // ==================== 订单处理 ====================
    /**
     * 处理单个订单（分布式锁 + 代理调用保证事务生效）
     *
     * <p>订单创建成功后发送 15 分钟延迟消息，用于超时自动取消</p>
     */
    private void handleVoucherOrder(SeckillOrderMessage message) {
        Long userId = message.getUserId();
        RLock lock = redissonClient.getLock("lock:order" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            throw new RuntimeException("获取分布式锁失败，用户正在处理中: userId=" + userId);
        }
        try {
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(message.getOrderId());
            voucherOrder.setUserId(message.getUserId());
            voucherOrder.setVoucherId(message.getVoucherId());

            // 通过代理对象调用，确保 @Transactional 生效
            IVoucherOrderService currentProxy = (IVoucherOrderService) AopContext.currentProxy();
            currentProxy.createVoucherOrder(voucherOrder);

            // 发送 15 分钟延迟消息，用于超时自动取消（需启用延迟队列功能）
            if (orderDelayEnabled) {
                OrderDelayMessage delayMsg = new OrderDelayMessage(
                        message.getOrderId(), message.getUserId(), message.getVoucherId());
                rabbitTemplate.convertAndSend(
                        ORDER_DELAY_EXCHANGE,
                        ORDER_DELAY_ROUTING_KEY,
                        delayMsg,
                        msg -> {
                            msg.getMessageProperties()
                                    .setHeader("x-delay", ORDER_DELAY_MS);
                            return msg;
                        });
                log.debug("延迟取消消息已发送: orderId={}, delay={}ms", message.getOrderId(), ORDER_DELAY_MS);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 一人一单
        Long count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if (count > 0) {
            log.warn("用户已经购买过一次了");
            return;
        }
        // 扣减库存 乐观锁
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0) // 库存大于0 乐观锁
                .update();
        if (!success) {
            log.error("库存不足");
            throw new RuntimeException("库存不足: voucherId=" + voucherOrder.getVoucherId());
        }
        // 保存订单（初始状态：未支付）
        voucherOrder.setStatus(1);
        save(voucherOrder);
    }

    @Override
    public Result payOrder(Long orderId, Long userId) {
        VoucherOrder order = getById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            return Result.fail("订单不存在");
        }
        if (order.getStatus() != 1) {
            return Result.fail("订单状态不允许支付");
        }
        order.setStatus(2);
        order.setPayTime(LocalDateTime.now());
        updateById(order);
        log.info("订单支付成功: orderId={}, userId={}", orderId, userId);
        return Result.ok();
    }
}
