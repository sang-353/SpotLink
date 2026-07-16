# SpotLink 项目代码图谱 (CodeGraph 分析结果)

> **生成时间**: 2026-07-16 | **工具**: CodeGraph (npx codegraph)
> **索引规模**: 82 文件, 1,540 节点, 2,039 边

---

## 项目概要

**SpotLink** — 高并发社交电商平台，融合商铺发现、秒杀优惠券、用户签到、博客社交。定位类似小红书 + 大众点评。

- **包名**: `com.sang`
- **Java 21 + Spring Boot 3.5.14 + MyBatis-Plus 3.5.16**
- **数据库**: MySQL `hmdp_db` (10 张 `tb_` 前缀表)
- **中间件**: Redis (Lettuce) + RabbitMQ (Spring AMQP) + Redisson
- **部署**: Docker Compose (MySQL + Redis + RabbitMQ 含延迟插件)

---

## 一、架构全景图

```
HTTP Request
  │
  ▼
MvcConfig.addInterceptors()
  ├─ RefreshTokenInterceptor (order=0) — Token → Redis Hash → UserDTO → ThreadLocal, 刷新 TTL
  └─ LoginInterceptor (order=1)        — ThreadLocal 空 → 401 (公开路径除外)
  │
  ▼
Controller (9 个, @Tag + @Operation)     ← 薄层: 参数提取 → Service → Result
  │
  ▼
Service (10 接口 + 10 实现)              ← 全部业务逻辑, extends ServiceImpl<M,E>
  │
  ▼
Mapper (10 接口, BaseMapper) + 1 XML    ← 数据访问
  │
  ▼
MySQL + Redis + RabbitMQ
  │
  └── OrderDelayListener (@ConditionalOnProperty) ← 延迟队列消费者（需插件）
```

---

## 二、核心模块调用链路 (CodeGraph 验证)

### 2.1 秒杀系统 (Flash Sale + 延迟队列超时取消) ★

这是整个项目最复杂的模块，涉及 Lua 原子脚本、RabbitMQ 异步消费、Redisson 分布式锁、幂等去重、重试+死信、**延迟队列超时取消**。

```
[入口] VoucherOrderController.seckillVoucher(voucherId)         POST /voucher-order/seckill/{id}
  └─→ VoucherOrderServiceImpl.seckillVoucher(voucherId)  (::73)
        ├─ UserHolder.getUser().getId()
        ├─ stringRedisTemplate.execute(seckill.lua, voucherId, userId)
        │     ├─ GET seckill:stock:{voucherId}        → 库存检查
        │     ├─ SISMEMBER seckill:order{voucherId}    → 一人一单去重
        │     ├─ INCRBY seckill:stock:{voucherId} -1   → 原子减库存
        │     ├─ SADD seckill:order{voucherId} userId   → 标记已下单
        │     └─ return 0(成功) / 1(库存不足) / 2(重复下单)
        ├─ redisIdWorker.nextId("order")               → 分布式 64 位 ID
        ├─ rabbitTemplate.convertAndSend(SeckillOrderMessage)  // 3次重试
        └─ [异常] rollbackSeckill() → INCR stock, SREM order set
```

```        
[消费者] onSeckillOrderMessage(message, channel, deliveryTag)  (::116 @RabbitListener MANUAL_ACK)
        ├─ SETNX seckill:order:dedup:{orderId} → 幂等检查 (7天 TTL)
        │     └─ 已存在 → basicAck → return
        ├─ handleVoucherOrder(message)           (::177)
        │     ├─ redissonClient.getLock("lock:order" + userId).tryLock()
        │     ├─ AopContext.currentProxy().createVoucherOrder(voucherOrder)  (::239 @Transactional)
        │     │     ├─ COUNT(*) WHERE user_id + voucher_id → 一人一单 (DB 兜底)
        │     │     ├─ UPDATE seckill_voucher SET stock=stock-1 WHERE stock>0 → 乐观锁
        │     │     ├─ voucherOrder.setStatus(1)  // 初始状态: 未支付
        │     │     └─ INSERT voucher_order
        │     ├─ [orderDelayEnabled] 发送 OrderDelayMessage → x-delayed-message (15min延迟)
        │     └─ lock.unlock()
        ├─ 成功 → basicAck
        └─ 失败 → delete 幂等 Key → retryCount+1
              ├─ ≤3 → rabbitTemplate.convertAndSend(重发) → basicAck 原消息
              └─ >3 → basicReject(requeue=false) → DLQ
```

**延迟队列超时取消**:
```
OrderDelayListener.onOrderDelayTimeout(...)  ::41
  ├─ getById(orderId) → 不存在 → ACK
  ├─ status != 1 → 已支付/已取消 → ACK 跳过
  └─ status == 1 → 超时取消:
        ├─ order.setStatus(4) + updateById        → 订单→已取消
        ├─ seckillVoucherService.update(stock+1)   → DB 库存回滚
        ├─ INCR seckill:stock:{voucherId}          → Redis 库存回滚
        └─ SREM seckill:order{voucherId} userId    → 允许重新秒杀
```

**模拟支付**: `PUT /voucher-order/pay/{orderId}` → status 1→2 + payTime

**关键依赖**: 
- `seckill.lua` (纯 Redis, 不涉及 MQ)
- `RedisIdWorker.nextId()` → 分布式 ID
- `RabbitMQConfig` → Exchange/Queue/DLX/DLQ + DelayQueueConfig (条件化)
- `OrderDelayListener` → @ConditionalOnProperty(spotlink.order.delay.enabled)
- `RedissonClient` → RedisConfig.redissonClient()

### 2.2 缓存系统 (策略模式)

```
ShopServiceImpl.queryById(id)  (::48)
  └─→ CacheClient.query(...)  (::88, 门面方法)
        └─→ strategyMap.get(activeStrategyType).query(...)  ← 按配置委派
              ├── PassThroughStrategy    ← 仅防穿透（空值缓存 + 随机 TTL）
              ├── MutexStrategy          ← 互斥锁防击穿（SETNX + 休眠重试）+ 穿透
              └── LogicalExpireStrategy  ← 逻辑过期防击穿（异步重建，返回旧数据）

application.yaml  spotlink.cache.strategy: mutex  ← 配置驱动，一行切换
```

**缓存更新**: `ShopServiceImpl.update(shop) ::201` → `updateById` + `delete(CACHE_SHOP_KEY + id)`

**策略工厂注入**: `CacheClient` 构造时自动注入 `List<CacheStrategy>`，按 `getType()` 构建策略 Map。

### 2.3 用户认证

```
sendCode (post /user/code) → 6 位随机码 → SET login:code:{phone} (2min TTL)
login    (post /user/login) → 验证码校验 → 新建/查用户 → UUID token → Redis Hash (UserDTO)
logout   (post /user/logout) → delete Redis token → UserHolder.removeUser()
```

**拦截器链**:
1. `RefreshTokenInterceptor` (order=0, 全路径): `Authorization` header → `login:token:{token}` Hash → `UserDTO` → `UserHolder.saveUser()` → 刷新 TTL (+random 0-5min)
2. `LoginInterceptor` (order=1): `UserHolder.getUser() == null` → 401

**公开路径**: `/user/code`, `/user/login`, `/shop/**`, `/voucher/**`, `/shop-type/**`, `/upload/**`, `/blog/hot`

### 2.4 博客社交

```
saveBlog      → save(blog) → 查粉丝 → ZADD feed:{follower} blogId timestamp
likeBlog      → ZSCORE blog:liked:{id} → toggle → DB liked +/-1 → ZADD/ZREM
queryHotBlog  → query().orderByDesc("liked").page() → 填充用户+点赞状态
queryBlogOfFollow → ZREVRANGEBYSCORE feed:{userId} → ScrollResult 滚动分页
```

**Feed 推送模型**: 发布时推送到所有粉丝收件箱 (Redis ZSet, 按时间戳排序)。滚动分页通过 `lastId` + `offset` 实现。

### 2.5 商铺 GEO 查询

```
queryShopByType(typeId, current, x, y)
  ├─ x/y == null → 普通分页
  └─ x/y 有值 → GEOSEARCH shop:geo:{typeId} FROMLONLAT x y BYRADIUS 5000m
       → 手动内存分页 (from/end) → query().in("id", ids) → setDistance()
```

### 2.6 关注与签到

- **关注**: DB insert/delete + Redis Set `follows:{userId}` (SAdd/SRem)
- **共同关注**: SINTER `follows:{user1}` `follows:{user2}` → 批量查用户
- **签到**: SETBIT `sign:{userId}:{yyyyMM}` (dayOfMonth-1) 1
- **连续签到**: BITFIELD + 循环位运算 `num & 1`

---

## 三、关键调用关系图

```
VoucherOrderServiceImpl
  ├─→ seckill.lua                (Lua 原子脚本)
  ├─→ RedisIdWorker.nextId()     (分布式 ID)
  ├─→ RabbitTemplate             (MQ 发送: 订单 + 延迟消息)
  ├─→ RedissonClient.getLock()   (分布式锁)
  ├─→ AopContext.currentProxy()  (自调用事务)
  ├─→ ISeckillVoucherService     (乐观锁扣库存)
  └─→ UserHolder.getUser()       ← RefreshTokenInterceptor 注入

OrderDelayListener (@ConditionalOnProperty)
  ├─→ IVoucherOrderService       (查/更新订单)
  ├─→ ISeckillVoucherService     (DB 库存回滚)
  ├─→ StringRedisTemplate        (Redis 库存 + Set 回滚)
  └─→ DelayQueueConfig           (x-delayed-message 交换机声明)

ShopServiceImpl
  ├─→ CacheClient.query()        ← 门面，策略委派
  ├─→ StringRedisTemplate (GEO/delete)
  └─→ ShopMapper

CacheClient (@Component, 门面)
  ├─→ Map<CacheStrategyType, CacheStrategy>  ← 工厂，构造时注入
  │     ├── PassThroughStrategy    ← 穿透防护
  │     ├── MutexStrategy          ← 互斥锁 + 穿透
  │     └── LogicalExpireStrategy  ← 逻辑过期 + 异步重建
  └─→ spotlink.cache.strategy 配置项

BlogServiceImpl
  ├─→ StringRedisTemplate (ZAdd/ZScore/ZRevRangeByScore)
  ├─→ FollowService.query()  (查粉丝)
  └─→ UserHolder.getUser()

FollowServiceImpl
  ├─→ StringRedisTemplate (SAdd/SRem/SInter)
  └─→ IUserService.listByIds()

```

---

## 四、UserHolder 消费者 (CodeGraph 验证)

`UserHolder` 被 6 个命名空间引用:
- `com.sang.controller` — BlogController, UserController
- `com.sang.service.impl` — BlogServiceImpl, FollowServiceImpl, UserServiceImpl, VoucherOrderServiceImpl

---

## 五、RabbitMQ 拓扑

```
spotlink.seckill.exchange  (DirectExchange)
  └─ Binding: "spotlink.seckill.order"
       └─→ spotlink.seckill.queue  (Durable)
              └─ DLX → spotlink.seckill.dlx (DirectExchange)
                   └─→ spotlink.seckill.dlq  (死信队列)

spotlink.order.delay.exchange  (CustomExchange: x-delayed-message, 条件化)
  └─ Binding: "spotlink.order.delay"
       └─→ spotlink.order.delay.queue  (Durable)
              └─→ OrderDelayListener.onOrderDelayTimeout()  ← 15min 超时取消
```

全部由 `RabbitMQConfig` @Bean 自动声明。延迟队列通过 `DelayQueueConfig` 内部类 + `@ConditionalOnProperty` 条件化加载。

---

## 六、Redis Key 全景

| Key 模式 | 用途 | 类型 | TTL |
|----------|------|------|-----|
| `login:code:{phone}` | 验证码 | String | 2 min |
| `login:token:{token}` | 用户信息 | Hash | ~36000 min |
| `cache:shop:{id}` | 店铺缓存 | String(JSON) | 30 min |
| `cache:shop:type:{typeId}` | 店铺类型缓存 | String(JSON) | — |
| `lock:shop:{id}` | 缓存互斥锁 | String | 10 sec |
| `seckill:stock:{voucherId}` | 秒杀库存 | String(int) | — |
| `seckill:order{voucherId}` | 用户下单标记 (Lua Set) | Set | — |
| `seckill:order:dedup:{orderId}` | 幂等去重 | String | 7 days |
| `lock:order:{userId}` | 订单用户锁 (Redisson) | — | — |
| `blog:liked:{blogId}` | 点赞记录 | ZSet | — |
| `feed:{userId}` | 关注流收件箱 | ZSet | — |
| `shop:geo:{typeId}` | 商铺坐标 | Geo | — |
| `sign:{userId}:{yyyyMM}` | 签到记录 | BitMap | — |
| `follows:{userId}` | 关注列表 | Set | — |

---

## 七、技术栈

| 关注点 | 选型 | 约束 |
|--------|------|------|
| 框架 | Spring Boot 3.5.14 | Jakarta 命名空间 |
| Java | 21 | — |
| ORM | MyBatis-Plus 3.5.16 | chain query, 避免 XML |
| Redis 客户端 | Lettuce 6.8.1 | 仅用 `StringRedisTemplate` |
| MQ | RabbitMQ + Spring AMQP | 手动 ACK, app 层重试 |
| 分布式锁 | Redisson 3.22.0 + SimpleRedisLock | Redisson=业务, Simple=轻量 |
| API 文档 | springdoc 2.7.0 + Knife4j 4.5.0 | Swagger UI `/swagger-ui.html` |
| JSON | Hutool `JSONUtil` | RabbitMQ 除外 (Jackson) |
| Bean 复制 | Hutool `BeanUtil.copyProperties` | — |
| DI | `@Resource` | 不用 `@Autowired` |
| 密码加密 | BCrypt (PasswordEncoder) | 已从 MD5 升级 |
| 部署 | Docker Compose | MySQL + Redis + RabbitMQ(含延迟插件) |

---

## 八、CodeGraph 验证的关键事实

1. `CacheClient.query()` 门面方法按 `spotlink.cache.strategy` 配置委派 — 切换策略只需改 yaml
2. `CacheStrategy` 接口有 3 个实现: `PassThroughStrategy`, `MutexStrategy`, `LogicalExpireStrategy`
3. `handleVoucherOrder` 仅被 `onSeckillOrderMessage` 调用 — 内部方法, 耦合度低
4. `createVoucherOrder` 通过 AopContext 调用 — 内部设 `status=1`(未支付)
5. `OrderDelayListener` 通过 `@ConditionalOnProperty` 条件化 — 无插件环境不加载
6. `seckill.lua` 纯 Redis 操作 — 不涉及 MQ
7. `unlock.lua` 已修复: `GET == ARGV[1]` 再 `DEL`, 防跨实例误释放
8. RabbitMQ 拓扑全部 Bean 声明 — 延迟队列条件化加载
9. `UserHolder` 6 个消费者 — ThreadLocal 渗透 Controller + Service
10. `ShopServiceImpl` 唯一有测试覆盖 (`SpotLinkApplicationTests.java`)
11. 全部 9 个 Controller 均有 `@Tag` + `@Operation`
12. `payOrder` 模拟支付: status 1→2 + payTime
13. Docker Compose + `rabbitmq/Dockerfile` 内置延迟插件

---

## 九、已知注意事项

1. `AopContext.currentProxy()` 用于 `@Transactional` 自调用 — 需 `@EnableAspectJAutoProxy(exposeProxy=true)`
2. RabbitMQ vhost `/spotlink` 需提前创建 — 拓扑由 Bean 自动声明
3. Entity 瞬态字段: Shop.distance, Voucher.stock/beginTime/endTime, Blog.icon/name/isLike — `@TableField(exist=false)`
4. `application.yaml` 含明文密码 — 生产需外置
5. `SystemConstants.IMAGE_UPLOAD_DIR` 为 Windows 硬编码路径
6. SeckillVoucher PK: `voucher_id` IdType.INPUT (1:1 扩展 Voucher)
7. Lua 脚本纯 Redis 操作 — MQ 发送在 Java 层
8. `createVoucherOrder(VoucherOrder)` 接收已设 orderId 的实体, 内部设 status=1
9. 延迟队列需 `rabbitmq_delayed_message_exchange` 插件 — Dockerfile 已内置
10. `OrderDelayListener` + `DelayQueueConfig` 通过 `spotlink.order.delay.enabled` 条件化
11. 超时取消同时回滚 DB 库存、Redis 库存、Redis Set — 三重保障
12. `BlogCommentsController` 空壳实现
13. 除 `ShopServiceImpl` 外，其他 Service 无测试覆盖
