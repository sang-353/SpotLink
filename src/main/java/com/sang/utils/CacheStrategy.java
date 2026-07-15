package com.sang.utils;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 缓存策略接口 — 定义统一的缓存查询契约
 * <p>
 * 每个实现类封装一种完整的缓存策略（含穿透防护和/或击穿防护），
 * 通过 {@link CacheStrategyType} 标识自身类型，由 {@link CacheClient} 按配置委派。
 *
 * @see PassThroughStrategy
 * @see MutexStrategy
 * @see LogicalExpireStrategy
 */
public interface CacheStrategy {

    /**
     * 按策略查询缓存，缓存未命中时回调数据库查询
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
    <R, ID> R query(String keyPrefix, ID id, Class<R> type,
                    Function<ID, R> dbFallback, Long ttl, TimeUnit unit);

    /**
     * 返回当前策略的类型标识
     */
    CacheStrategyType getType();
}
