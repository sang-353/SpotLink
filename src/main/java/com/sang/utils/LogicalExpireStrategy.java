package com.sang.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.sang.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.sang.utils.RedisConstants.LOCK_SHOP_TTL;

/**
 * 逻辑过期防缓存击穿策略
 * <p>
 * 缓存永不过期（物理上），通过内部逻辑过期时间判断是否需要异步重建。
 * 已过期时返回旧数据，同时异步线程重建缓存，用户无需等待。
 * 适用场景：极高并发、热点数据、可容忍短暂旧数据的场景。
 * <p>
 * <b>注意</b>：本策略不防缓存穿透，依赖数据预热（启动时加载到 Redis）。
 */
@Slf4j
@Component
public class LogicalExpireStrategy implements CacheStrategy {

    private final StringRedisTemplate stringRedisTemplate;
    private final SimpleRedisLock simpleRedisLock;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public LogicalExpireStrategy(StringRedisTemplate stringRedisTemplate, SimpleRedisLock simpleRedisLock) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.simpleRedisLock = simpleRedisLock;
    }

    @Override
    public <R, ID> R query(String keyPrefix, ID id, Class<R> type,
                           Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {
        // 不需要考虑缓存穿透

        // 构建完整的缓存key
        String key = keyPrefix + id;
        // 从redis查商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isBlank(json)) {
            // 不存在，直接返回空
            return null;
        }
        // 命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回店铺信息
            return r;
        }
        // 已过期，需要缓存重建
        // 获取互斥锁
        boolean isLock = simpleRedisLock.tryLock(LOCK_SHOP_KEY + id, LOCK_SHOP_TTL);
        // 判断是否获取锁成功
        if (isLock) {
            // 成功，开启独立线程实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    R r1 = dbFallback.apply(id);
                    // 写入新缓存
                    this.setWithLogicalExpire(key, r1, ttl, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    simpleRedisLock.unlock(LOCK_SHOP_KEY + id);
                }
            });

        }
        // 返回过期商铺信息
        return r;
    }

    @Override
    public CacheStrategyType getType() {
        return CacheStrategyType.LOGICAL_EXPIRE;
    }

    /**
     * 设置逻辑过期缓存（用于预热和异步重建）
     *
     * @param key   Redis中的键
     * @param value 需要存储的值
     * @param time  过期时间
     * @param unit  时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
}
