package com.sang.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.sang.dto.Result;
import com.sang.entity.VoucherOrder;
import com.sang.mapper.VoucherOrderMapper;
import com.sang.service.ISeckillVoucherService;
import com.sang.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sang.utils.RedisIdWorker;
import com.sang.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

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

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private static final ExecutorService EXECUTOR_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private IVoucherOrderService proxy;
    private volatile boolean running = true;


    @PostConstruct
    private void init() {
        // 启动线程处理订单
        EXECUTOR_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @jakarta.annotation.PreDestroy
    private void destroy() {
        running = false;
        EXECUTOR_ORDER_EXECUTOR.shutdown();
        try {
            if (!EXECUTOR_ORDER_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                EXECUTOR_ORDER_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR_ORDER_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private class VoucherOrderHandler implements Runnable {
        // 先在redis执行 xgroup create stream.orders g1 0 mkstream
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (running) {
                try {
                    // 1.获取消息队列中的订单信息 xreadgroup group g1 c1 count 1 block 2000 stream stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 3.获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 4.获取成功，创建订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 5.执行创建订单
                    handleVoucherOrder(voucherOrder);
                    // 6.ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    if (!running) {
                        // 应用正在关闭，忽略连接销毁异常
                        break;
                    }
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {

            while (running) {
                try {
                    // 1.获取Pending-list中的订单信息 xreadgroup group g1 c1 count 1 stream stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 3.获取失败，说明没有消息，跳出循环
                        break;
                    }
                    // 4.获取成功，创建订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 5.执行创建订单
                    handleVoucherOrder(voucherOrder);
                    // 6.ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    if (!running) {
                        break;
                    }
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    /*@PostConstruct
    private void init() {
        // 启动线程处理订单
        EXECUTOR_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order" + userId);
        // 获取锁 无参则为不等待，30秒释放
        boolean isLock = lock.tryLock();
        // 判断是否获取到锁
        if(!isLock) {
            log.error("不能重复下单");
            return;
        }

        try {
            // 获取代理对象（防止 @Transactional 自调用失效）
            IVoucherOrderService currentProxy = proxy;
            if (currentProxy == null) {
                currentProxy = (IVoucherOrderService) AopContext.currentProxy();
            }
            currentProxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        }finally {
            // 释放锁
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId));
        int r = result.intValue();
        // 判断结果是否为0
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 为0，有购买资格
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单信息
        return Result.ok(orderId);
    }

    /*@Override
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
        // 为0，有购买资格，把下单信息保存到阻塞队列
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 用户ID
        voucherOrder.setUserId(userId);
        // 代金券ID
        voucherOrder.setVoucherId(voucherId);
        // 保存到阻塞队列
        orderTasks.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单信息
        return Result.ok(orderId);
    }*/

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

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 一人一单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已经购买过一次了");
            return;
        }
        // 扣减库存 乐观锁
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1").eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // 库存大于0 乐观锁
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }
        // 保存订单
        save(voucherOrder);
    }
}
