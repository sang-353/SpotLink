package com.sang.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sang.dto.Result;
import com.sang.entity.Shop;
import com.sang.mapper.ShopMapper;
import com.sang.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sang.utils.CacheClient;
import com.sang.utils.SystemConstants;
import jakarta.annotation.Resource;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.sang.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;


    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
        // 逻辑过期解决缓存击穿
//        Shop shop = cacheClient
//                .queryWithLogicalExpire(CACHE_SHOP_KEY + id, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

 /*   public Shop queryWithLogicalExpire(Long id) {
        // 不需要考虑缓存穿透

        // 从redis查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 不存在，直接返回空
            return null;
        }
        // 命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回店铺信息
            return shop;
        }
        // 已过期，需要缓存重建
        // 获取互斥锁
        boolean isLock = tryLock(LOCK_SHOP_KEY + id);
        // 判断是否获取锁成功
        if(isLock) {
            // 成功，开启独立线程实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    this.saveShop2Redis(id, 30*60L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(LOCK_SHOP_KEY + id);
                }
            });

        }
        // 返回过期商铺信息
        return shop;
    }*/
    public Shop queryWithMutex(Long id) {
        // 从redis查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值(解决缓存穿透）
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }
        // 实现缓存重建
        Shop shop = null;
        try {
            // 1. 获取互斥锁
            boolean isLock = tryLock(LOCK_SHOP_KEY + id);
            // 2. 判断是否获取成功
            if (!isLock) {
                // 3. 失败则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 4. 成功，查数据库
            shop = getById(id);
            // 数据库不存在，返回错误
            if (shop == null) {
                // 将空值写入redis(解决缓存穿透）
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",
                        CACHE_NULL_TTL + RandomUtil.randomLong(0,1), TimeUnit.MINUTES);
                return null;
            }
            // 数据库存在，存入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),
                    CACHE_SHOP_TTL + RandomUtil.randomLong(0,5), TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unLock(LOCK_SHOP_KEY + id);
        }

        return shop;
    }

/*    public Shop queryWithPassThrough(Long id) {
        // 从redis查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值(解决缓存穿透）
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }
        // 不存在，查数据库
        Shop shop = getById(id);
        // 数据库不存在，返回错误
        if (shop == null) {
            // 将空值写入redis(解决缓存穿透）
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",
                    CACHE_NULL_TTL + RandomUtil.randomLong(0,1), TimeUnit.MINUTES);
            return null;
        }
        // 数据库存在，存入redis，返回
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),
                CACHE_SHOP_TTL + RandomUtil.randomLong(0,5), TimeUnit.MINUTES);
        return shop;
    }*/
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

/*    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 查询店铺数据
        Shop shop = getById(id);
        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }*/
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 查询redis，按照距离排序，分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        // 解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if (content.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        // 截取 from ~ end 的部分
        List<Long> ids = new ArrayList<>(content.size());
        Map<String, Distance> distanceMap = new HashMap<>(content.size());
        content.stream().skip(from).forEach(result-> {
            // 获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 查询店铺
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        // 返回结果
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
