package com.sang.utils;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 缓存客户端门面 — 统一缓存操作入口
 * <p>
 * 提供通用的 set/setWithLogicalExpire 工具方法，并通过 {@link #query} 方法
 * 将缓存查询委派给配置指定的 {@link CacheStrategy} 实现，支持运行时切换策略。
 * <p>
 * 策略切换：修改 application.yaml 中 {@code spotlink.cache.strategy} 即可，
 * 无需改动任何业务代码。
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final Map<CacheStrategyType, CacheStrategy> strategyMap;
    private final CacheStrategyType activeStrategyType;

    public CacheClient(StringRedisTemplate stringRedisTemplate,
                       List<CacheStrategy> strategies,
                       @Value("${spotlink.cache.strategy:pass-through}") String strategyConfig) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(CacheStrategy::getType, Function.identity()));
        this.activeStrategyType = CacheStrategyType.valueOf(strategyConfig.toUpperCase().replace('-', '_'));
        log.info("CacheClient active strategy: {}", activeStrategyType);
    }

    // ==================== 工具方法 ====================

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
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // ==================== 策略委派 ====================

    /**
     * 按当前配置的策略查询缓存，缓存未命中时回调数据库查询
     * <p>
     * 具体行为取决于 {@code spotlink.cache.strategy} 配置：
     * <ul>
     *   <li>{@code pass-through} — 仅防缓存穿透</li>
     *   <li>{@code mutex} — 互斥锁防击穿 + 穿透防护</li>
     *   <li>{@code logical-expire} — 逻辑过期防击穿（需预热）</li>
     * </ul>
     *
     * @param keyPrefix  缓存 key 前缀
     * @param id         业务 ID
     * @param type       返回值类型
     * @param dbFallback 数据库查询回调
     * @param ttl        缓存过期时间
     * @param unit       时间单位
     * @param <R>        返回值泛型
     * @param <ID>       ID 泛型
     * @return 查询结果，不存在返回 null
     */
    public <R, ID> R query(String keyPrefix, ID id, Class<R> type,
                           Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {
        CacheStrategy strategy = strategyMap.get(activeStrategyType);
        return strategy.query(keyPrefix, id, type, dbFallback, ttl, unit);
    }
}
