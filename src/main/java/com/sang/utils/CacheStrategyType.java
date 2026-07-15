package com.sang.utils;

/**
 * 缓存策略类型枚举
 */
public enum CacheStrategyType {

    /**
     * 仅防缓存穿透（空值缓存），无击穿防护
     */
    PASS_THROUGH,

    /**
     * 互斥锁防缓存击穿 + 穿透防护
     */
    MUTEX,

    /**
     * 逻辑过期防缓存击穿（需预热，不防穿透）
     */
    LOGICAL_EXPIRE
}
