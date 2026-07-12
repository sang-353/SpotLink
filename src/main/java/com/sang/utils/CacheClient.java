package com.sang.utils;

import cn.hutool.core.util.RandomUtil;
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

import static com.sang.utils.RedisConstants.*;


@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private final SimpleRedisLock simpleRedisLock;

    public CacheClient(StringRedisTemplate stringRedisTemplate, SimpleRedisLock simpleRedisLock) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.simpleRedisLock = simpleRedisLock;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 设置逻辑过期缓存
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
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), time, unit);
    }

    /**
     * 使用缓存查询数据的方法，实现了缓存穿透的处理
     *
     * @param keyPrefix  缓存键的前缀
     * @param id         查询数据的ID
     * @param type       返回数据的类型
     * @param dbFallBack 数据库查询的函数式接口
     * @param time       缓存过期时间
     * @param unit       时间单位
     * @return 查询结果，可能是数据或null
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit) {
        // 合成key
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
        // 不存在，查数据库
        R r = dbFallBack.apply(id);
        // 数据库不存在，返回错误
        if (r == null) {
            // 将空值写入redis(解决缓存穿透）
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL + RandomUtil.randomLong(0, 1), TimeUnit.MINUTES);
            return null;
        }
        // 数据库存在，存入redis，返回
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 使用逻辑过期方式查询缓存数据，并在缓存过期时异步重建缓存
     *
     * @param <R>        返回数据类型
     * @param <ID>       查询条件ID类型
     * @param keyPrefix  缓存key前缀
     * @param id         查询条件ID
     * @param type       返回数据类型Class对象
     * @param dbFallBack 数据库查询回调函数
     * @param time       缓存过期时间
     * @param unit       时间单位
     * @return 查询结果，如果不存在则返回null
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit) {
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
        boolean isLock = simpleRedisLock.tryLock(LOCK_SHOP_KEY, LOCK_SHOP_TTL);
        // 判断是否获取锁成功
        if (isLock) {
            // 成功，开启独立线程实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    R r1 = dbFallBack.apply(id);
                    // 写入新缓存
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    simpleRedisLock.unlock(LOCK_SHOP_KEY);
                }
            });

        }
        // 返回过期商铺信息
        return r;
    }
}
