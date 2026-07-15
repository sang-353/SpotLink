package com.sang.utils;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.sang.utils.RedisConstants.CACHE_NULL_TTL;

/**
 * 缓存穿透防护策略
 * <p>
 * 仅处理缓存穿透（空值缓存），不做击穿防护。
 * 适用场景：低并发查询、数据变更不频繁的普通业务。
 */
@Component
public class PassThroughStrategy implements CacheStrategy {

    private final StringRedisTemplate stringRedisTemplate;

    public PassThroughStrategy(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public <R, ID> R query(String keyPrefix, ID id, Class<R> type,
                           Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {
        String key = keyPrefix + id;

        // 1. 查 Redis
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 命中有效数据 → 直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        // 3. 命中空值 → 防穿透，直接返回 null
        if (json != null) {
            return null;
        }

        // 4. 未命中 → 查数据库
        R r = dbFallback.apply(id);

        // 5. 数据库不存在 → 写空值防穿透
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "",
                    CACHE_NULL_TTL + RandomUtil.randomLong(0, 1), TimeUnit.MINUTES);
            return null;
        }

        // 6. 数据库存在 → 写缓存 + 返回
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r),
                ttl + RandomUtil.randomLong(0, 5), unit);
        return r;
    }

    @Override
    public CacheStrategyType getType() {
        return CacheStrategyType.PASS_THROUGH;
    }
}
