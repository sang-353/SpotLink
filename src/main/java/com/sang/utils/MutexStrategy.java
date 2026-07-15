package com.sang.utils;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.sang.utils.RedisConstants.*;

/**
 * 互斥锁防缓存击穿策略
 * <p>
 * 通过分布式互斥锁保证同一时刻只有一个线程重建缓存，其余线程休眠重试。
 * 同时包含空值缓存以防护穿透。
 * 适用场景：高并发、对数据一致性要求高的热点数据。
 */
@Component
public class MutexStrategy implements CacheStrategy {

    private final StringRedisTemplate stringRedisTemplate;
    private final SimpleRedisLock simpleRedisLock;

    public MutexStrategy(StringRedisTemplate stringRedisTemplate, SimpleRedisLock simpleRedisLock) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.simpleRedisLock = simpleRedisLock;
    }

    @Override
    public <R, ID> R query(String keyPrefix, ID id, Class<R> type,
                           Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {
        String key = keyPrefix + id;

        // 从redis查商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值(解决缓存穿透）
        if (json != null) {
            // 返回一个错误信息
            return null;
        }
        // 实现缓存重建
        R r = null;
        try {
            // 1. 获取互斥锁
            boolean isLock = simpleRedisLock.tryLock(LOCK_SHOP_KEY + id, LOCK_SHOP_TTL);
            // 2. 判断是否获取成功
            if (!isLock) {
                // 3. 失败则休眠并重试
                Thread.sleep(50);
                return query(keyPrefix, id, type, dbFallback, ttl, unit);
            }

            // 4. 成功，查数据库
            r = dbFallback.apply(id);
            // 数据库不存在，返回错误
            if (r == null) {
                // 将空值写入redis(解决缓存穿透）
                stringRedisTemplate.opsForValue().set(key, "",
                        CACHE_NULL_TTL + RandomUtil.randomLong(0, 1), TimeUnit.MINUTES);
                return null;
            }
            // 数据库存在，存入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r),
                    ttl + RandomUtil.randomLong(0, 5), unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            simpleRedisLock.unlock(LOCK_SHOP_KEY + id);
        }

        return r;
    }

    @Override
    public CacheStrategyType getType() {
        return CacheStrategyType.MUTEX;
    }
}
