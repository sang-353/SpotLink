# CLAUDE.md

## Project Identity

**SpotLink** — a high-concurrency social-commerce platform combining shop discovery, flash-sale (seckill) coupons, user check-in, and blog-based social networking. Inspired by platforms like Xiaohongshu (Little Red Book) and Dianping.

- **Package**: `com.sang`
- **Base path**: `src/main/java/com/sang/`
- **Resources**: `src/main/resources/`
- **Database**: `hmdp_db` (10 tables, all prefixed `tb_`)

---

## Architecture Overview

```
Client Request
  │
  ▼
Interceptor Chain (order-sensitive!)
  ├─ RefreshTokenInterceptor  (order=0 — runs first, refreshes token TTL, loads user into ThreadLocal)
  └─ LoginInterceptor         (order=1 — blocks unauthenticated requests on protected paths)
  │
  ▼
Controller (9 controllers, thin — delegate to Service)
  │
  ▼
Service (10 interfaces + 10 impls — all extend MyBatis-Plus ServiceImpl<M, E>)
  │
  ▼
Mapper (10 interfaces + MyBatis-Plus BaseMapper — no hand-written SQL except one XML)
  │
  ▼
MySQL (hmdp_db)  +  Redis (cache, locks, message queue, geo, bitmap, zset)
```

### Layering Rules

1. **Controller** — parameter extraction + call Service + return `Result`. No business logic.
2. **Service** — all business logic. Always program to interfaces (`IShopService`, not `ShopServiceImpl`).
3. **Mapper** — data access only. Use MyBatis-Plus `query().eq(...)` chain in Service; rarely use XML mappers.
4. **Entity** — POJOs matching `tb_*` tables. Use `@TableField(exist = false)` for transient fields.
5. **DTO** — `Result` (unified response), `UserDTO`, `LoginFormDTO`, `ScrollResult`.

---

## Directory Map

```
com.sang/
├── SpotLinkApplication.java       ← @SpringBootApplication + @EnableAspectJAutoProxy(exposeProxy = true) + @MapperScan
├── config/
│   ├── MvcConfig.java             ← Interceptor registration (ORDER MATTERS: RefreshToken first, then Login)
│   ├── RedisConfig.java           ← RedissonClient bean (single-server config)
│   ├── MybatisConfig.java         ← MyBatis-Plus pagination plugin
│   └── WebExceptionAdvice.java    ← @RestControllerAdvice catching RuntimeException → Result.fail("服务器异常")
├── controller/   (9 files)
│   ├── UserController.java        ← /user/code, /user/login, /user/me, /user/sign, /user/sign/count
│   ├── VoucherController.java     ← /voucher/** — CRUD for coupons
│   ├── VoucherOrderController.java← /voucher-order/seckill/{id} — flash sale entry point ★
│   ├── ShopController.java        ← /shop/** — query by id/type/geo, update
│   ├── ShopTypeController.java    ← /shop-type/** — category listing
│   ├── BlogController.java        ← /blog/** — CRUD, likes, hot, feed (timeline)
│   ├── BlogCommentsController.java← /blog-comments/** — nested comments
│   ├── FollowController.java      ← /follow/** — follow/unfollow, common follows
│   └── UploadController.java      ← /upload/** — file upload to local disk
├── service/
│   ├── I*Service.java             ← 10 interfaces, all extend IService<Entity>
│   └── impl/
│       ├── UserServiceImpl.java           ← Login/sign/signCount (BitMap)
│       ├── VoucherServiceImpl.java
│       ├── VoucherOrderServiceImpl.java   ← Flash sale core: Lua atomic + Redis Stream + Redisson lock ★
│       ├── SeckillVoucherServiceImpl.java
│       ├── ShopServiceImpl.java           ← Cache strategies (pass-through, mutex, logical-expire) + Geo query ★
│       ├── ShopTypeServiceImpl.java
│       ├── BlogServiceImpl.java           ← Blog CRUD, likes (ZSet), feed push (ZSet), scroll pagination
│       ├── BlogCommentsServiceImpl.java
│       ├── FollowServiceImpl.java
│       └── UserInfoServiceImpl.java
├── entity/       (10 POJOs matching tb_* tables, annotated with MyBatis-Plus + Lombok)
├── mapper/       (10 interfaces extending BaseMapper<Entity>, 1 XML mapper)
├── dto/
│   ├── Result.java         ← {success, errorMsg, data, total} + static ok()/fail() factories
│   ├── UserDTO.java        ← {id, nickName, icon} — minimal user info for ThreadLocal + Redis storage
│   ├── LoginFormDTO.java   ← {phone, code, password}
│   └── ScrollResult.java   ← {list, minTime, offset} — for ZSet-based feed pagination
└── utils/        (13 classes — application infrastructure, NOT a dumping ground)
    ├── CacheClient.java            ← Generic cache util: pass-through, logical-expire, mutex lock ★
    ├── RedisIdWorker.java          ← Distributed 64-bit ID generator (timestamp << 32 | sequence) ★
    ├── RedisConstants.java         ← All Redis key prefixes and TTLs in one place
    ├── RedisData.java              ← Wrapper for logical-expire: {expireTime, data}
    ├── ILock.java                  ← Lock interface: tryLock(timeoutSec), unlock()
    ├── SimpleRedisLock.java        ← SETNX + Lua unlock (UUID prefix per JVM instance)
    ├── RefreshTokenInterceptor.java← Interceptor: loads user from Redis Hash, refreshes TTL, sets ThreadLocal
    ├── LoginInterceptor.java       ← Interceptor: checks ThreadLocal, returns 401 if null
    ├── UserHolder.java             ← ThreadLocal<UserDTO> holder
    ├── PasswordEncoder.java
    ├── RegexUtils.java / RegexPatterns.java
    └── SystemConstants.java        ← IMAGE_UPLOAD_DIR, USER_NICK_NAME_PREFIX, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE
```

### Resources

```
resources/
├── application.yaml
├── db/spotlink.sql               ← Full DDL + seed data
├── mapper/VoucherMapper.xml      ← Only manual XML mapper (seckill stock deduction)
├── seckill.lua                   ← Atomic flash-sale script ★
└── unlock.lua                    ← Safe distributed lock release (GET + DEL atomically)
```

---

## Technology Stack (Version-Agnostic)

| Concern | Choice | Notes |
|---------|--------|-------|
| Framework | Spring Boot (Web MVC) | `@RestController`, `@Service`, `@Component`, dependency injection |
| ORM | MyBatis-Plus | `ServiceImpl<M, E>` base, chain queries via `query().eq(...)`, pagination plugin |
| Cache / Lock / MQ / Geo / BitMap | Redis (via Spring Data Redis + Lettuce) | `StringRedisTemplate` everywhere; avoid `RedisTemplate` for consistency |
| Distributed Lock | Redisson (`RLock`) + custom `SimpleRedisLock` | Redisson for business locks; SimpleRedisLock for lightweight scenarios |
| JSON | Hutool `JSONUtil` | Not Jackson — match existing code |
| Utilities | Hutool (`StrUtil`, `BeanUtil`, `RandomUtil`), Lombok | |
| AOP | Spring AOP (`@EnableAspectJAutoProxy(exposeProxy = true)`) | Required for `AopContext.currentProxy()` in transactional self-calls |

### Key Dependency Philosophy

- **Redis operations**: Always use `StringRedisTemplate` (not `RedisTemplate`). Serialize objects to JSON strings manually.
- **JSON serialization**: Use `cn.hutool.json.JSONUtil.toJsonStr()` / `JSONUtil.toBean()`, not Jackson's `ObjectMapper`.
- **Bean copying**: Use `cn.hutool.core.bean.BeanUtil.copyProperties()`, not Spring's `BeanUtils`.
- **OR mapping**: All entities use MyBatis-Plus annotations (`@TableName`, `@TableId`, `@TableField`). Never write raw JDBC.

---

## Core Design Patterns

### 1. Unified Response — `Result`

Every controller method returns `com.sang.dto.Result`:

```java
Result.ok()                  // success, no data
Result.ok(data)              // success with data
Result.ok(list, total)       // success with paginated list
Result.fail("error message") // business error
```

Never throw exceptions for business errors. Use `Result.fail()`. Exceptions are for truly unexpected failures, caught by `WebExceptionAdvice`.

### 2. User Context — ThreadLocal + Interceptors

- `RefreshTokenInterceptor` (order=0): Extracts token from `Authorization` header → loads user from Redis Hash → stores in `UserHolder` (ThreadLocal) → refreshes token TTL.
- `LoginInterceptor` (order=1): Checks `UserHolder.getUser() != null`. Returns 401 if null.
- `UserHolder.saveUser()` / `getUser()` / `removeUser()` — always called in preHandle/afterCompletion pairs.

**Excluded paths** (no login required): `/user/code`, `/user/login`, `/shop/**`, `/voucher/**`, `/shop-type/**`, `/upload/**`, `/blog/hot`.

When adding new public endpoints, update both `MvcConfig.addInterceptors()`.

### 3. Distributed Locking

Two tiers:
- **Redisson `RLock`**: Used in `VoucherOrderServiceImpl.handleVoucherOrder()` for per-user order deduplication (`lock:order{userId}`). Auto-renewal, 30s default lease.
- **`SimpleRedisLock`**: SETNX-based with Lua-scripted unlock (verifies UUID prefix before deleting). Used for lightweight scenarios. Each JVM instance has a unique UUID prefix to prevent cross-instance lock release.

**Pattern**:
```java
RLock lock = redissonClient.getLock("lock:key" + id);
boolean isLock = lock.tryLock();  // non-blocking
if (!isLock) { return fail/retry; }
try {
    // critical section
} finally {
    lock.unlock();
}
```

### 4. Caching — Three-Layer Strategy (CacheClient) ★

`CacheClient` is the central caching utility. It handles:

#### a) Cache Penetration (`queryWithPassThrough`)
- Query Redis first. If key exists → return.
- If key is empty string → return null (null value cached).
- If key missing → query DB → cache result (or cache empty string with short TTL if DB returns null).
- Null value TTL: `CACHE_NULL_TTL + random(0,1)` minutes (randomized to avoid avalanche).

#### b) Cache Breakdown — Mutex Lock (`queryWithMutex` in ShopServiceImpl, legacy)
- On cache miss, acquire distributed lock → one thread queries DB → others wait/retry.
- Lock key: `lock:shop:{id}`.

#### c) Cache Breakdown — Logical Expire (`queryWithLogicalExpire`)
- Data stored as `RedisData {expireTime, data}` — the Redis key itself does NOT expire.
- On read: if `expireTime.isAfter(now)` → return cached data; else → acquire lock → async rebuild via thread pool (`CACHE_REBUILD_EXECUTOR`, 10 threads) → return stale data to caller (non-blocking).
- Write: `setWithLogicalExpire()` wraps data in `RedisData` with logical expire timestamp.

#### Cache Update Strategy
- On DB update: **delete** the cache key (`stringRedisTemplate.delete(CACHE_SHOP_KEY + id)`). Let next read rebuild it.
- Never update the cache value directly on writes.

### 5. Redis Key Naming Convention

All keys defined as constants in `RedisConstants.java`:

| Constant | Pattern | Type | TTL |
|----------|---------|------|-----|
| `LOGIN_CODE_KEY` | `login:code:{phone}` | String | 2 min |
| `LOGIN_USER_KEY` | `login:token:{token}` | Hash | ~360 min |
| `CACHE_SHOP_KEY` | `cache:shop:{id}` | String (JSON) | 30 min |
| `CACHE_SHOP_TYPE_KEY` | `cache:shop:type:{typeId}` | String (JSON) | — |
| `LOCK_SHOP_KEY` | `lock:shop:{id}` | String | 10 sec |
| `SECKILL_STOCK_KEY` | `seckill:stock:{voucherId}` | String (int) | — |
| `BLOG_LIKED_KEY` | `blog:liked:{blogId}` | ZSet (userId → timestamp) | — |
| `FEED_KEY` | `feed:{userId}` | ZSet (blogId → timestamp) | — |
| `SHOP_GEO_KEY` | `shop:geo:{typeId}` | Geo | — |
| `USER_SIGN_KEY` | `sign:{userId}:{yyyyMM}` | BitMap | — |

**Rule**: Always add new key constants to `RedisConstants.java`. Never hardcode key strings.

---

## Flash Sale (Seckill) System — The Most Complex Flow ★

### Architecture

```
User Request
  │
  ▼
VoucherOrderController.seckillVoucher(voucherId)
  │
  ▼
VoucherOrderServiceImpl.seckillVoucher(voucherId)
  ├─ Generate orderId via RedisIdWorker.nextId("order")
  ├─ Execute seckill.lua (atomic on Redis)
  │   ├─ Check stock (seckill:stock:{id} > 0)
  │   ├─ Check duplicate (SISMEMBER seckill:order{id} userId)
  │   ├─ Decr stock (INCRBY -1)
  │   ├─ Record user (SADD seckill:order{id} userId)
  │   └─ Send to Stream (XADD stream.orders * userId voucherId id)
  └─ Return: 0=success, 1=no stock, 2=duplicate
  │
  ▼ (async, background thread)
VoucherOrderHandler (launched via @PostConstruct)
  ├─ XREADGROUP group=g1 consumer=c1 from stream.orders (>)
  ├─ On message → handleVoucherOrder()
  │   ├─ Redisson lock on lock:order{userId}
  │   ├─ proxy.createVoucherOrder(voucherOrder) ← @Transactional (needs proxy for self-invocation!)
  │   │   ├─ Check one-user-one-order (COUNT where user_id + voucher_id)
  │   │   ├─ Deduct stock with optimistic lock (WHERE stock > 0)
  │   │   └─ INSERT order
  │   └─ ACK message
  └─ On error → handlePendingList() (reprocess from 0, i.e., pending messages)
```

### Critical Details

1. **`AopContext.currentProxy()` is required** for `@Transactional` self-invocations. The application is annotated with `@EnableAspectJAutoProxy(exposeProxy = true)`. Always use `proxy.createVoucherOrder()` not `this.createVoucherOrder()`.

2. **Optimistic lock on stock deduction**: `setSql("stock = stock - 1").gt("stock", 0)`. If affected rows = 0, the update fails silently (no exception thrown).

3. **Redis Stream consumer group**: Must be pre-created in Redis CLI: `XGROUP CREATE stream.orders g1 0 MKSTREAM`. This is NOT done in code.

4. **Lua scripts are stored in `src/main/resources/`** and loaded via `DefaultRedisScript` with `ClassPathResource`. When refactoring, ensure the resource path remains accessible.

---

## Distributed ID Generation — RedisIdWorker

64-bit ID structure:
```
[ 32-bit timestamp offset ] [ 32-bit sequence number ]
  seconds since BEGIN_TIMESTAMP   incremented per key per day
```

- `BEGIN_TIMESTAMP` is a fixed epoch (2026-01-29 12:00:00 UTC in current implementation).
- Sequence resets daily (key includes `yyyy:MM:dd`).
- Redis key: `irc{prefix}:{yyyy:MM:dd}`. INCR is atomic.
- Generated IDs are globally unique and roughly time-ordered.

---

## Blog & Social Features

### Likes — Redis ZSet
- Key: `blog:liked:{blogId}`, members: userId, score: timestamp.
- `ZSCORE` to check if a user liked.
- `ZADD` on like, `ZREM` on unlike.
- `ZRANGE key 0 4` for top-5 likers.
- DB `liked` column updated synchronously (+1 / -1).

### Feed / Timeline — Push Model
- When a user publishes a blog, iterate all followers and `ZADD` to each follower's feed (`feed:{followerUserId}`).
- Feed query: `ZREVRANGEBYSCORE feed:{userId} max min LIMIT offset count` — scroll-based pagination using `ScrollResult`.
- Offset deduplication: if consecutive entries have the same score, offset increments; otherwise resets to 1.

### Comments — Two-Level Tree
- Level 1: `parent_id = 0`.
- Level 2: `parent_id = {level1_id}`, `answer_id = {target_comment_id}`.
- Status field: 0=normal, 1=reported, 2=banned.

---

## Shop & Geo Queries

### Cache Strategy
- Query by ID: `CacheClient.queryWithPassThrough()` — handles both penetration and breakdown.
- Query by type + geo: Redis `GEOSEARCH` with `GEORADIUSBYMEMBER`-equivalent, radius 5km, with distance.
- Distance is populated on `Shop.distance` (a `@TableField(exist = false)` transient field).

### Shop Update
- After `updateById(shop)`, delete the cache key. Do NOT update the cache value directly.

---

## User Authentication

### Login Flow
1. `POST /user/code` → generates 6-digit code → stores in Redis `login:code:{phone}` (TTL: 2 min).
2. `POST /user/login` → validates code → finds or creates User → stores UserDTO as Redis Hash at `login:token:{uuid}` → returns token.
3. Client sends token in `Authorization` header on subsequent requests.
4. `RefreshTokenInterceptor` reads the Hash, loads user into ThreadLocal, extends TTL by `LOGIN_USER_TTL + random(0,5)` minutes.

### Sign-In (Check-in) — Redis BitMap
- Key: `sign:{userId}:{yyyyMM}`.
- `SETBIT key dayOfMonth-1 1` to sign in.
- `BITFIELD key GET u{dayOfMonth} 0` + bit-shift loop to count consecutive sign-ins.

---

## Database Principles

### Table Naming
- All tables: `tb_{entity_name}` (e.g., `tb_user`, `tb_voucher_order`).
- Entity class corresponds 1:1 with table (e.g., `VoucherOrder` ↔ `tb_voucher_order`).

### ID Strategies
- Most entities: `IdType.AUTO` (database auto-increment).
- `VoucherOrder`: `IdType.INPUT` — the ID is generated by `RedisIdWorker` and set manually before insert.
- `SeckillVoucher`: `IdType.INPUT` on `voucher_id` (foreign key to `tb_voucher.id`).
- `UserInfo`: `IdType.AUTO` on `user_id` (same as `tb_user.id`).

### MyBatis-Plus Usage Pattern
- `query().eq("column", value).one()` — single entity.
- `query().eq("column", value).page(new Page<>(current, size))` — paginated.
- `query().in("id", ids).last("ORDER BY FIELD(id, ...)")` — batch query preserving order.
- `update().setSql("stock = stock - 1").gt("stock", 0).update()` — conditional update (returns boolean).

---

## Configuration & Environment

### application.yaml Structure
- Server port: 8081
- MySQL: `jdbc:mysql://127.0.0.1:3306/hmdp_db`
- Redis: `localhost:6379`
- Lettuce connection pool: max-active=10, max-idle=10, min-idle=1
- Jackson: `default-property-inclusion: non_null` (null fields omitted from JSON responses)
- MyBatis-Plus: `type-aliases-package: com.sang.entity`
- Log level: `com.sang: debug`

### Redisson
- Configured programmatically in `RedisConfig.java` (not via application.yaml).
- Single-server mode, same host/port/password as the main Redis config.

---

## Code Conventions

### Imports
- No wildcard imports (never `import com.sang.utils.*`).
- Static imports for constants from `RedisConstants` are acceptable.

### Annotations
- Controllers: `@RestController` + `@RequestMapping("/prefix")`.
- Services: `@Service` on impl class; interface extends `IService<Entity>`.
- Dependency injection: `@Resource` (not `@Autowired`) — existing convention.
- Transactions: `@Transactional` only on public methods that modify multiple tables.

### Logging
- Use `@Slf4j` (Lombok).
- `log.debug()` for normal flows, `log.error()` for exceptions.
- Always include context in log messages (e.g., userId, token, orderId).

### Response Pattern
```java
// Success
return Result.ok(data);
// Failure
return Result.fail("specific reason in Chinese");
```

### Thread Safety
- `UserHolder` is ThreadLocal-based — always remove in `afterCompletion`.
- `CacheClient.CACHE_REBUILD_EXECUTOR` is a shared thread pool (10 threads).
- `VoucherOrderHandler` runs on a single-thread executor (serial order processing).

---

## Known Caveats & Gotchas

1. **`AopContext.currentProxy()`** — Required for any `@Transactional` method called from within the same class. If proxy is null, check `@EnableAspectJAutoProxy(exposeProxy = true)` on the application class.

2. **Redis Stream consumer group** — Must be created manually before the application starts: `XGROUP CREATE stream.orders g1 0 MKSTREAM`. If the group already exists, use `XGROUP SETID` or `XGROUP DESTROY` + recreate. This is not automated in code.

3. **`unlock.lua` has a bug** — The condition `redis.call('GET', KEYS[1] == ARGV[1])` incorrectly nests the equality check inside GET. It should be: `if redis.call('GET', KEYS[1]) == ARGV[1]`. The current code always evaluates KEYS[1] == ARGV[1] (which is false for integer comparison) first, then calls GET with 0/false. This should be fixed during the refactor.

4. **Entity transient fields** — `Shop.distance`, `Voucher.stock/beginTime/endTime`, `Blog.icon/name/isLike` are all `@TableField(exist = false)`. They are populated at the Service layer, not from DB.

5. **Password is stored in `application.yaml` in plaintext** — This must be externalized (env vars, config server) during the refactor.

6. **File upload path is hardcoded** in `SystemConstants.IMAGE_UPLOAD_DIR` with a Windows absolute path. This should be configurable.

7. **LoginInterceptor excludes `/blog/hot`** but uses it as an exact path match, not a pattern. Verify this matches the actual endpoint.

8. **`SeckillVoucher` has `voucher_id` as primary key** (IdType.INPUT), meaning it's a one-to-one extension of `Voucher`. The `Voucher` entity has transient `stock`, `beginTime`, `endTime` — these actually come from `SeckillVoucher`.

---

## Testing Notes

- The project currently has no test classes (only `spring-boot-starter-test` dependency).
- When adding tests, focus on:
  - `CacheClient` — unit test cache hit/miss/penetration/expire scenarios with embedded Redis.
  - `VoucherOrderServiceImpl` — integration test the full seckill flow with Redis Stream and DB.
  - Lua scripts — test edge cases (zero stock, duplicate user, concurrent access).

---

## Refactoring Guidelines

When upgrading the project:

1. **Keep the layer separation** — Controller/Service/Mapper boundaries are well-defined; don't blur them.
2. **Preserve Redis key naming** — All keys are in `RedisConstants.java`; add new ones there.
3. **Don't change the `Result` API** — Frontend likely depends on `{success, errorMsg, data, total}` shape.
4. **Maintain the interceptor order** — RefreshToken (0) before Login (1). Token refresh must happen first.
5. **Keep Lua scripts in `src/main/resources/`** — They're loaded via `ClassPathResource`.
6. **`@Resource` over `@Autowired`** — Match the existing injection style.
7. **MyBatis-Plus chain queries** — Continue using `query().eq().page()` style, not XML mappers.
8. **Thread pool for cache rebuild** — Keep the 10-thread `CACHE_REBUILD_EXECUTOR` or make it configurable.
9. **Redisson configuration** — Keep it programmatic in `RedisConfig` or move to application.yaml, but be consistent.

---

## Common Commands

```bash
# Build
mvn clean package -DskipTests

# Run
java -jar target/spotlink-0.0.1-SNAPSHOT.jar

# Redis setup (one-time)
redis-cli XGROUP CREATE stream.orders g1 0 MKSTREAM

# Test seckill
curl -X POST http://localhost:8081/voucher-order/seckill/{voucherId} \
  -H "Authorization: {token}"
```
