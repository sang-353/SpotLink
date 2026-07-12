# Global AGENTS.md — Codex 全局指令

## 语言与交互
- 始终使用中文回复用户。
- 代码注释和文档可使用中文，但代码标识符（变量名、函数名等）使用英文。
- 关于修改和新增代码，需要等我明确指出进行修改后再执行修改，否则只输出计划或内容。

## 代码规范
- 优先简洁、可读性高的代码，避免过度设计。
- 变量命名做到语义清晰、通俗易懂
- 遵循现有代码库的风格，不随意引入新的范式或模式。
- 复杂逻辑务必添加代码注释。
- 修改文件时保持最小化变更，不要重构与任务无关的代码。
- 尽量精简代码改动范围

## 文件操作
- 不要主动创建或修改测试文件，除非用户明确要求。
- 不要添加版权声明或许可证头。
- 不要在代码中添加不必要的行内注释。
- 严禁改动无关文件

## Git 操作

- 不要自动提交（git commit），除非用户明确要求。
- 不要自动创建新分支或推送代码。

## 工具偏好

- 优先使用 `rg` (ripgrep) 进行文本搜索。
- 使用 PowerShell 作为默认 Shell。

## 后端工程准则 

- 资源安全：禁止硬编码敏感信息。所有连接、文件流操作必须确保闭环释放。 
-  依赖管理：不随意添加新的 Maven/Gradle 依赖，除非当前依赖无法实现核心功能。 
- 日志与调试：严禁使用标准输出语句，必须使用符合 SLF4J 规范的日志记录。 

## 交互策略

- 任务执行：涉及多个文件的重构任务，必须先提供"执行计划"供用户确认。 
- 搜索优先：在不确定类定义或方法签名时，优先调用 `rg` 搜索项目上下文，而非凭空假设。 
- 审计报警：若在处理任务时发现代码存在严重安全风险（如注入、硬编码密钥），需立即中断任务并提示。
- 进行大规模修改前，说明改动思路与缘由。
- 需求表述模糊时，及时沟通确认。
- 在请求授权执行指令时简要解释该指令目的。

## 安全准则

- 无明确指令，不得随意删除文件。
- 杜绝具有数据损毁风险的操作。

<!-- CODEGRAPH_START -->
## CodeGraph

This project has a CodeGraph MCP server (`codegraph_*` tools) configured. CodeGraph is a tree-sitter-parsed knowledge graph of every symbol, edge, and file. Reads are sub-millisecond and return structural information grep cannot.

### When to prefer codegraph over native search

Use codegraph for **structural** questions — what calls what, what would break, where is X defined, what is X's signature. Use native grep/read only for **literal text** queries (string contents, comments, log messages) or after you already have a specific file open.

| Question | Tool |
|---|---|
| "Where is X defined?" / "Find symbol named X" | `codegraph query` |
| "What calls function Y?" | `codegraph callers` |
| "What does Y call?" | `codegraph callees` |
| "What would break if I changed Z?" | `codegraph impact` |
| "Show me Y's signature / source / docstring" | `codegraph node` |
| "Give me focused context for a task/area" | `codegraph explore` |
| "See several related symbols' source at once" | `codegraph explore` |
| "What files exist under path/" | `codegraph files` |
| "Is the index healthy?" | `codegraph status` |

### Rules of thumb

- **Answer directly — don't delegate exploration.** For "how does X work" / architecture / trace questions, answer with 2-3 codegraph calls: `codegraph explore` first for the source of the symbols it surfaces. Codegraph IS the pre-built index, so spawning a separate file-reading sub-task/agent — or running a grep + read loop — repeats work codegraph already did and costs more for the same answer.
- **Trust codegraph results.** They come from a full AST parse. Do NOT re-verify them with grep — that's slower, less accurate, and wastes context.
- **Don't grep first** when looking up a symbol by name. `codegraph query` is faster and returns kind + location + signature in one call.
- **Don't loop `codegraph node` over many symbols** — one `codegraph explore` call returns several symbols' source grouped in a single capped call, while each separate node/Read call re-reads the whole context and costs far more.
- **Index lag**: the file watcher debounces ~500ms behind writes; don't re-query immediately after editing a file in the same turn.

### If `.codegraph/` doesn't exist

The MCP server returns "not initialized." Ask the user: *"I notice this project doesn't have CodeGraph initialized. Want me to run `codegraph init -i` to build the index?"*
<!-- CODEGRAPH_END -->

---

# CodeGraph 代码图谱 (auto-generated 2026-07-12)

> **索引状态**: 80 文件, 1,477 节点, 1,916 边, 2.77 MB — Java 75 + Lua 2 + XML 2 + YAML 1

---

## 架构全景

```
HTTP Request
  │
  ▼
MvcConfig.addInterceptors() ── 拦截器链 (ORDER 敏感!)
  ├─ RefreshTokenInterceptor (order=0) — Token→UserDTO→ThreadLocal, 刷新 TTL
  └─ LoginInterceptor      (order=1) — ThreadLocal 空则 401
  │
  ▼
Controller (9 个, @Tag/@Operation) ── 薄层，参数提取 + 调用 Service → 返回 Result
  │
  ▼
Service (10 接口 + 10 实现, extends ServiceImpl<M,E>) ── 全部业务逻辑
  │
  ▼
Mapper (10 接口, BaseMapper) + VoucherMapper.xml (唯一手写 SQL: LEFT JOIN)
  │
  ▼
MySQL (hmdp_db)  +  Redis (缓存/锁/Geo/BitMap/ZSet)  +  RabbitMQ (异步订单)
```

---

## 核心调用链路 (CodeGraph 验证)

### 1. 秒杀系统 (Flash Sale) ★

```
VoucherOrderController.seckillVoucher(voucherId)          :: POST /voucher-order/seckill/{id}
  └─→ IVoucherOrderService.seckillVoucher(voucherId)
        └─→ [dynamic] VoucherOrderServiceImpl.seckillVoucher(voucherId)   ::73
              ├─ UserHolder.getUser().getId()                             // 获取用户
              ├─ stringRedisTemplate.execute(SECKILL_SCRIPT, ...)          // Lua 原子操作
              │     └─→ [Lua] seckill.lua
              │           ├─ GET seckill:stock:{voucherId} → >0 ?
              │           ├─ SISMEMBER seckill:order{voucherId} userId → 重复?
              │           ├─ INCRBY seckill:stock:{voucherId} -1
              │           ├─ SADD seckill:order{voucherId} userId
              │           └─ return 0(ok) / 1(库存不足) / 2(重复)
              ├─ redisIdWorker.nextId("order")                           // 分布式 ID
              ├─ rabbitTemplate.convertAndSend(...)                       // 发送到 MQ
              └─ [异常] rollbackSeckill() → Redis 补偿 (INCR stock, SREM)
```

**MQ 消费链路**:
```
VoucherOrderServiceImpl.onSeckillOrderMessage(message, channel, deliveryTag)   ::116 @RabbitListener
  ├─ Redis SETNX seckill:order:dedup:{orderId}  → 幂等检查 (7 天 TTL)
  │     └─ 已存在 → channel.basicAck → return
  ├─ handleVoucherOrder(message)                                            ::177
  │     ├─ redissonClient.getLock("lock:order" + userId).tryLock()           // 分布式锁
  │     └─ AopContext.currentProxy() → [dynamic] createVoucherOrder(voucherOrder)  ::239 @Transactional
  │           ├─ COUNT(*) WHERE user_id + voucher_id → 一人一单
  │           ├─ seckillVoucherService.update().setSql("stock=stock-1").gt("stock",0)
  │           └─ save(voucherOrder)
  ├─ 成功 → channel.basicAck
  └─ 失败 → delete 幂等 Key → retryCount+1
        ├─ ≤3 → 重发到 SECKILL_EXCHANGE → basicAck 原消息
        └─ >3 → basicReject(requeue=false) → DLQ: spotlink.seckill.dlq
```

### 2. 缓存系统 (Cache)

```
ShopController.queryShopById(id)                     :: GET /shop/{id}
  └─→ ShopServiceImpl.queryById(id)                  ::48
        └─→ CacheClient.queryWithPassThrough(...)    ::59  (唯一调用者!)
              ├─ Redis GET key → 命中直接返回
              ├─ 命中空值("") → 返回 null (防穿透)
              ├─ DB 查询 (dbFallBack.apply(id))
              ├─ DB 不存在 → SET "" + 随机 TTL (防穿透)
              └─ DB 存在 → SET JSON + TTL → 返回

CacheClient.queryWithLogicalExpire(...)              ::100  (当前未被调用，备选方案)
  ├─ Redis GET → 空 → 返回 null
  ├─ 解析 RedisData {expireTime, data}
  ├─ 未过期 → 直接返回
  └─ 已过期 → tryLock(LOCK_SHOP_KEY) → 异步重建 (10 线程池) → 返回旧数据
```

**缓存更新策略**: `ShopServiceImpl.update(shop) ::201` → `updateById(shop)` → `stringRedisTemplate.delete(CACHE_SHOP_KEY + id)`

### 3. 用户认证链路

```
UserController.sendCode(phone)                       :: POST /user/code
  └─→ UserServiceImpl.sendCode(phone, session)       ::52
        ├─ RegexUtils.isPhoneInvalid(phone)
        ├─ RandomUtil.randomNumbers(6)
        └─ stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, 2min)

UserController.login(loginForm, session)             :: POST /user/login
  └─→ UserServiceImpl.login(loginForm, session)      ::66
        ├─ 校验手机号 → Redis 验证码
        ├─ query().eq("phone", ...) → 不存在则 creatUserWithPhone → save → 重查
        ├─ UUID.randomUUID() → token
        ├─ BeanUtil.copyProperties → UserDTO → BeanUtil.beanToMap → Redis Hash
        └─ stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap)
              expire(LOGIN_USER_TTL + random 0-5 min)

UserController.logout(request)                       :: POST /user/logout
  └─→ UserServiceImpl.logout(request)               ::111
        ├─ stringRedisTemplate.delete(LOGIN_USER_KEY + token)
        └─ UserHolder.removeUser()
```

### 4. 博客社交链路

```
BlogController.saveBlog(blog)                        :: POST /blog
  └─→ BlogServiceImpl.saveBlog(blog)                ::146
        ├─ UserHolder.getUser().getId → setUserId
        ├─ save(blog) → DB
        ├─ followService.query().eq("follow_user_id", userId).list()   // 查粉丝
        └─ forEach follower → ZADD feed:{followerUserId} blogId timestamp

BlogController.likeBlog(id)                          :: PUT /blog/like/{id}
  └─→ BlogServiceImpl.likeBlog(id)                  ::103
        ├─ ZSCORE blog:liked:{id} userId → null ? 点赞 : 取消
        ├─ DB: liked = liked +/- 1
        └─ Redis: ZADD / ZREM blog:liked:{id}

BlogController.queryHotBlog(current)                 :: GET /blog/hot  (无需登录)
  └─→ BlogServiceImpl.queryHotBlog(current)         ::86
        ├─ query().orderByDesc("liked").page(...)
        └─ forEach → queryBlogUser(blog) + isBlogLiked(blog)

BlogController.queryBlogOfFollow(max, offset)        :: GET /blog/of/follow
  └─→ BlogServiceImpl.queryBlogOfFollow(max, offset) ::168
        ├─ ZREVRANGEBYSCORE feed:{userId} 0 max LIMIT offset 3
        ├─ 解析 typedTuples → ids + minTime + offset
        └─ query().in("id", ids).last("ORDER BY FIELD(id,...)")
```

### 5. 商铺 GEO 查询

```
ShopController.queryShopByType(typeId, current, x, y) :: GET /shop/of/type
  └─→ ShopServiceImpl.queryShopByType(...)           ::212
        ├─ x/y == null → 普通分页查询
        └─ x/y 有值:
              ├─ GEOSEARCH shop:geo:{typeId} FROMLONLAT x y BYRADIUS 5000m
              ├─ 手动内存分页 (from=(current-1)*5, end=current*5)
              ├─ 提取 shopId + distance → query().in("id", ids)
              └─ forEach shop → setDistance(distanceMap.get(id))
```

### 6. 关注/共同关注

```
FollowController.follow(followUserId, isFollow)      :: PUT /follow/{id}/{isFollow}
  └─→ FollowServiceImpl.follow(followUserId, isFollow) ::38
        ├─ isFollow ? DB insert + SADD follows:{userId}
        └─ !isFollow ? DB delete + SREM follows:{userId}

FollowController.followCommons(id)                   :: GET /follow/common/{id}
  └─→ FollowServiceImpl.followCommons(id)            ::75
        └─ SINTER follows:{currentUser} follows:{targetUser}
              → userService.listByIds(ids) → BeanUtil → UserDTO list
```

### 7. 签到系统

```
UserController.sign()                                :: POST /user/sign
  └─→ UserServiceImpl.sign()                        ::122
        └─ SETBIT sign:{userId}:{yyyyMM} (dayOfMonth-1) 1

UserController.signCount()                           :: GET /user/sign/count
  └─→ UserServiceImpl.signCount()                   ::138
        └─ BITFIELD sign:{userId}:{yyyyMM} GET u{dayOfMonth} 0
              → 循环位运算: num & 1 → 统计连续签到天数
```

### 8. 优惠券管理

```
VoucherController.addSeckillVoucher(voucher)         :: POST /voucher/seckill
  └─→ VoucherServiceImpl.addSeckillVoucher(voucher)  (预热 Redis 库存)
        ├─ save(voucher) → DB
        ├─ save(seckillVoucher) → DB
        └─ stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + id, stock)
```

---

## 关键依赖图

```
VoucherOrderServiceImpl
  ├─→ seckill.lua (Lua 原子脚本)
  ├─→ RedisIdWorker.nextId()        ── 分布式 64 位 ID 生成器
  ├─→ RabbitTemplate.convertAndSend ──→ RabbitMQConfig (Exchange/Queue/DLX/DLQ)
  ├─→ RedissonClient.getLock()      ──→ RedisConfig.redissonClient()
  ├─→ AopContext.currentProxy()     ──→ self.createVoucherOrder() @Transactional
  ├─→ ISeckillVoucherService.update() ── 乐观锁扣库存
  ├─→ StringRedisTemplate           ── 幂等/回滚/Lua 执行
  └─→ UserHolder.getUser()          ←── RefreshTokenInterceptor 注入

ShopServiceImpl
  ├─→ CacheClient.queryWithPassThrough() ── 唯一切入点
  ├─→ StringRedisTemplate (GEO/delete)
  └─→ ShopMapper (BaseMapper)

BlogServiceImpl
  ├─→ StringRedisTemplate (ZAdd/ZScore/ZRevRangeByScore)
  ├─→ FollowService.query()         ── 查粉丝推送 feed
  ├─→ BlogMapper (BaseMapper)
  └─→ UserHolder.getUser()

FollowServiceImpl
  ├─→ StringRedisTemplate (SAdd/SRem/SInter)
  └─→ IUserService.listByIds()

CacheClient (独立工具, @Component)
  ├─→ StringRedisTemplate
  └─→ ExecutorService (CACHE_REBUILD_EXECUTOR, 10 线程)

RefreshTokenInterceptor (order=0, 全路径)
  ├─→ StringRedisTemplate.opsForHash().entries()
  └─→ UserHolder.saveUser(userDTO)

LoginInterceptor (order=1, 排除公开路径)
  └─→ UserHolder.getUser() == null → 401
```

---

## UserHolder 调用者 (ThreadLocal 消费者)

`UserHolder` 被 6 个命名空间引用:
- `com.sang.controller` — BlogController, UserController
- `com.sang.service.impl` — BlogServiceImpl, FollowServiceImpl, UserServiceImpl, VoucherOrderServiceImpl

---

## RabbitMQ 拓扑 (代码自声明)

```
RabbitMQConfig.java
  ├─ DirectExchange: spotlink.seckill.exchange
  ├─ Queue: spotlink.seckill.queue  (Durable, DLX → spotlink.seckill.dlx)
  ├─ Binding: seckillQueue → seckillExchange with "spotlink.seckill.order"
  ├─ DirectExchange: spotlink.seckill.dlx  (死信交换机)
  ├─ Queue: spotlink.seckill.dlq  (死信队列)
  ├─ Binding: dlq → dlx with "spotlink.seckill.dlx"
  ├─ Jackson2JsonMessageConverter (RabbitMQ 专用 JSON 转换器)
  └─ RabbitTemplate bean: setConfirmCallback + converter
```

---

## Redis Key 命名空间速查

| 常量 | 模式 | 类型 | TTL |
|------|------|------|-----|
| LOGIN_CODE_KEY | `login:code:{phone}` | String | 2 min |
| LOGIN_USER_KEY | `login:token:{token}` | Hash | ~36000 min |
| CACHE_SHOP_KEY | `cache:shop:{id}` | String(JSON) | 30 min |
| CACHE_SHOP_TYPE_KEY | `cache:shop:type:{typeId}` | String(JSON) | — |
| LOCK_SHOP_KEY | `lock:shop:{id}` | String | 10 sec |
| SECKILL_STOCK_KEY | `seckill:stock:{voucherId}` | String(int) | — |
| SECKILL_ORDER_DEDUP_KEY | `seckill:order:dedup:{orderId}` | String | 7 days |
| BLOG_LIKED_KEY | `blog:liked:{blogId}` | ZSet | — |
| FEED_KEY | `feed:{userId}` | ZSet | — |
| SHOP_GEO_KEY | `shop:geo:{typeId}` | Geo | — |
| USER_SIGN_KEY | `sign:{userId}:{yyyyMM}` | BitMap | — |
| `follows:{userId}` | (硬编码于 FollowServiceImpl) | Set | — |

---

## CodeGraph 已验证的关键事实

1. **`CacheClient.queryWithPassThrough`** 仅被 `ShopServiceImpl.queryById` 调用 — 修改其签名需同步改一处。
2. **`CacheClient.queryWithLogicalExpire`** 当前无调用者 — 备选方案，可安全重构。
3. **`handleVoucherOrder`** 仅被 `onSeckillOrderMessage` 调用 — 内部方法，耦合度低。
4. **`createVoucherOrder`** 被 `handleVoucherOrder` 通过 `AopContext.currentProxy()` 调用 + 接口声明 — 修改时需保持接口一致性。
5. **`seckill.lua`** 纯 Redis 原子操作，不涉及 MQ — Lua 脚本变更不回影响 Java 层发送逻辑。
6. **`unlock.lua`** 已修复: 先 `GET` 比较再 `DEL`，防止跨实例误释放。
7. **RabbitMQ 拓扑全部由 `@Bean` 声明** — 启动即创建，无需手动建队列。
8. **`UserHolder` 被 6 个模块引用** — ThreadLocal 模式渗透 Controller 和 Service 层。
9. **`ShopServiceImpl`** 是唯一有测试覆盖的 Service — `SpotLinkApplicationTests.java`。
10. **全部 9 个 Controller** 均使用 `@Tag` + `@Operation` Swagger 注解。

---

## 项目文件树 (80 文件)

```
src/main/java/com/sang/
├── SpotLinkApplication.java         ← @SpringBootApplication + @EnableAspectJAutoProxy + @MapperScan
├── config/
│   ├── MvcConfig.java               ← 拦截器注册 (RefreshToken order=0, Login order=1)
│   ├── RedisConfig.java             ← RedissonClient Bean
│   ├── RabbitMQConfig.java          ← Exchange/Queue/DLX/DLQ + Jackson2JsonMessageConverter + RabbitTemplate
│   ├── MybatisConfig.java           ← 分页插件 (MySQL)
│   ├── SpringDocConfig.java         ← Knife4j 6 组 API
│   └── WebExceptionAdvice.java      ← @RestControllerAdvice
├── controller/   (9 文件)
│   ├── UserController.java          ← /user/**
│   ├── VoucherController.java       ← /voucher/**
│   ├── VoucherOrderController.java  ← /voucher-order/seckill/{id} ★
│   ├── ShopController.java          ← /shop/**
│   ├── ShopTypeController.java      ← /shop-type/**
│   ├── BlogController.java          ← /blog/**
│   ├── BlogCommentsController.java  ← /blog-comments/** (空壳)
│   ├── FollowController.java        ← /follow/**
│   └── UploadController.java        ← /upload/**
├── service/
│   ├── I*Service.java               ← 10 接口, extends IService<Entity>
│   └── impl/
│       ├── UserServiceImpl.java           ← 登录/登出/签到
│       ├── VoucherServiceImpl.java        ← 优惠券 CRUD + 秒杀预热
│       ├── VoucherOrderServiceImpl.java   ← 秒杀核心 ★
│       ├── SeckillVoucherServiceImpl.java ← 纯继承
│       ├── ShopServiceImpl.java           ← 缓存穿透/Geo 查询
│       ├── ShopTypeServiceImpl.java       ← Redis 缓存
│       ├── BlogServiceImpl.java           ← 博文 CRUD/点赞/Feed
│       ├── BlogCommentsServiceImpl.java   ← 纯继承
│       ├── FollowServiceImpl.java         ← 关注/取关/共同关注
│       └── UserInfoServiceImpl.java       ← 纯继承
├── entity/       (10 POJO, @TableName + Lombok)
├── mapper/       (10 接口, BaseMapper<Entity>)
├── dto/
│   ├── Result.java              ← {success, errorMsg, data, total}
│   ├── UserDTO.java             ← {id, nickName, icon}
│   ├── LoginFormDTO.java        ← {phone, code, password}
│   ├── ScrollResult.java        ← {list, minTime, offset}
│   └── SeckillOrderMessage.java ← {orderId, userId, voucherId, retryCount}
└── utils/
    ├── CacheClient.java            ← 通用缓存工具 (穿透/逻辑过期/互斥锁)
    ├── RedisIdWorker.java          ← 分布式 ID 生成器 (64 位)
    ├── RedisConstants.java         ← 所有 Redis Key 和 TTL
    ├── RedisData.java              ← {expireTime, data} 封装
    ├── ILock.java                  ← 锁接口
    ├── SimpleRedisLock.java        ← SETNX + Lua 解锁
    ├── RefreshTokenInterceptor.java← Token 刷新拦截器
    ├── LoginInterceptor.java       ← 登录拦截器 (401)
    ├── UserHolder.java             ← ThreadLocal<UserDTO>
    ├── PasswordEncoder.java        ← MD5+salt
    ├── RegexUtils.java / RegexPatterns.java
    └── SystemConstants.java        ← 上传路径/RabbitMQ 常量/分页大小

src/main/resources/
├── application.yaml
├── db/spotlink.sql
├── mapper/VoucherMapper.xml       ← 唯一手写 SQL
├── seckill.lua                     ← 秒杀原子脚本
└── unlock.lua                      ← 分布式锁安全释放
```

---

## 技术栈速查

| 关注点 | 选型 | 备注 |
|--------|------|------|
| 框架 | Spring Boot 3.5.14 | Jakarta 命名空间 |
| Java | 21 | — |
| ORM | MyBatis-Plus 3.5.16 | ServiceImpl 基类, 链式 query() |
| 缓存/锁/Geo/BitMap | Redis + Lettuce 6.8.1 | 仅用 StringRedisTemplate |
| 消息队列 | RabbitMQ + Spring AMQP | 手动 ACK, DLX/DLQ |
| 分布式锁 | Redisson 3.22.0 + SimpleRedisLock | Redisson 用于业务锁 |
| API 文档 | springdoc-openapi 2.7.0 + Knife4j 4.5.0 | /swagger-ui.html |
| JSON | Hutool JSONUtil | RabbitMQ 除外 (Jackson) |
| 注入 | @Resource | 不用 @Autowired |
| Bean 复制 | Hutool BeanUtil.copyProperties | 不用 Spring BeanUtils |

---

## 已知注意事项

1. `AopContext.currentProxy()` 用于 `@Transactional` 自调用 — 需 `@EnableAspectJAutoProxy(exposeProxy=true)`
2. RabbitMQ vhost `/spotlink` 需提前创建 — 拓扑由 Bean 自动声明
3. `unlock.lua` 已修复: `GET == ARGV[1]` 再 `DEL`
4. Entity 瞬态字段: Shop.distance, Voucher.stock/beginTime/endTime, Blog.icon/name/isLike — `@TableField(exist=false)`
5. `application.yaml` 含明文密码 — 生产需外置
6. `SystemConstants.IMAGE_UPLOAD_DIR` 为 Windows 硬编码路径
7. SeckillVoucher PK: `voucher_id` IdType.INPUT (1:1 扩展 Voucher)
8. Spring RabbitMQ retry 已禁用 — 应用层自管理重试+幂等 Key
9. Lua 脚本纯 Redis 操作 — MQ 发送在 Java 层
10. `createVoucherOrder(VoucherOrder)` 接收已设 orderId 的实体
