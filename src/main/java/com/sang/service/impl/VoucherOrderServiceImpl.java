package com.sang.service.impl;

import com.rabbitmq.client.Channel;
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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.Duration;
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

    /** 最大重试次数 */
    private static final int MAX_RETRY_COUNT = 3;

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
        // 发送消息到 RabbitMQ
        SeckillOrderMessage message = new SeckillOrderMessage(orderId, userId, voucherId, 0);
        try {
            rabbitTemplate.convertAndSend(SECKILL_EXCHANGE, SECKILL_ROUTING_KEY, message);
        } catch (Exception e) {
            // 消息发送失败 → 回滚 Redis 操作（补偿）
            log.error("秒杀消息发送失败: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId, e);
            rollbackSeckill(voucherId, userId);
            return Result.fail("系统繁忙，请稍后重试");
        }
        log.debug("秒杀消息已发送: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
        // 返回订单信息
        return Result.ok(orderId);
    }

    /** 消息发送失败时的 Redis 回滚 */
    // TODO 消息发送失败重试，待修改
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
    // ==================== 订单处理 ====================
    /**
     * 处理单个订单（分布式锁 + 代理调用保证事务生效）
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
        } finally {
            lock.unlock();
        }
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        // 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        // 判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order" + userId);
        // 获取锁 无参则为不等待，30秒释放
        boolean isLock = lock.tryLock();
        // 判断是否获取到锁
        if(!isLock) {
            return Result.fail("不能重复下单");
        }
        IVoucherOrderService proxy = null;
        try {
            // 获取代理对象,防止事务失效
            proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 返回订单ID
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        }finally {
            // 释放锁
            lock.unlock();
        }
    }*/

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
        // 保存订单
        save(voucherOrder);
    }
}
