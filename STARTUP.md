# SpotLink 项目启动指南

## 前置条件

| 依赖 | 要求 | 说明 |
|------|------|------|
| Java | 21+ | `java --version` |
| Maven | 3.9+ | `mvn --version` |
| MySQL | 8.0+ | 端口 3306，用户 root / xie060106 |
| Redis | 7.x | 端口 6379，密码 dev123456（Docker 容器 `dev-redis`） |

## 一、初始化数据库（仅首次）

```bash
# 创建数据库并导入表结构和种子数据
E:\mysql\bin\mysql.exe -uroot -pxie060106 -e "CREATE DATABASE IF NOT EXISTS hmdp_db DEFAULT CHARACTER SET utf8mb4;"
E:\mysql\bin\mysql.exe -uroot -pxie060106 hmdp_db < src/main/resources/db/spotlink.sql
```

## 二、预热 Redis 数据（每次 Redis 重启后必须执行）

### 一键预热（推荐）

在项目根目录依次执行以下三条命令：

```bash
# 1. 预热秒杀库存（7 张秒杀券，id=10~16）
for id in 10 11 12 13 14 15 16; do
  docker exec dev-redis redis-cli -a dev123456 --no-auth-warning SET "seckill:stock:$id" 100
done

# 2. 创建 Redis Stream 消费者组
docker exec dev-redis redis-cli -a dev123456 --no-auth-warning XGROUP CREATE stream.orders g1 0 MKSTREAM

# 3. 预热商铺 GEO 坐标数据
mvn test -Dtest=SpotLinkApplicationTests#loadShopData -DfailIfNoTests=false
```

> **说明**：
> - 命令 2 若提示 `BUSYGROUP Consumer Group name already exists` 可忽略（已创建过）。
> - 命令 3 将 14 个商铺按类型写入 Redis GEO（`shop:geo:1`、`shop:geo:2`），支撑附近商铺搜索功能。

## 三、启动应用

```bash
cd E:\code\Maven\hmdp\spotlink
mvn spring-boot:run -Dmaven.test.skip=true
```

应用启动后访问：
- **API Base URL**: http://localhost:8081
- **Swagger UI**: http://localhost:8081/swagger-ui.html
- **Knife4j 文档**: http://localhost:8081/doc.html

## 四、验证服务是否正常

```bash
# 1. 查询单个商铺
curl -s http://localhost:8081/shop/1

# 2. 查询商铺类型列表
curl -s http://localhost:8081/shop-type/list

# 3. GEO 周边搜索（应返回带 distance 的结果）
curl -s "http://localhost:8081/shop/of/type?typeId=1&current=1&x=120.149192&y=30.316078"

# 4. 查询店铺优惠券
curl -s http://localhost:8081/voucher/list/1

# 5. 用户登录
curl -s -X POST http://localhost:8081/user/code -d "phone=13686869696"
# 查看验证码（从 Redis 获取）：
docker exec dev-redis redis-cli -a dev123456 --no-auth-warning GET "login:code:13686869696"
# 登录（替换 <code> 为实际验证码）：
curl -s -X POST http://localhost:8081/user/login \
  -H "Content-Type: application/json" \
  -d '{"phone":"13686869696","code":"<code>"}'

# 6. 秒杀（替换 <token> 为上一步返回的 token）
curl -s -X POST http://localhost:8081/voucher-order/seckill/10 \
  -H "Authorization: <token>"
```

## 五、Redis 预热数据说明

| Key | 类型 | 说明 | 预热方式 |
|-----|------|------|----------|
| `seckill:stock:{id}` | String | 秒杀券库存（id=10~16） | 手动 SET |
| `shop:geo:1` `shop:geo:2` | GEO | 商铺坐标（按类型分组） | 运行 loadShopData 测试 |
| `stream.orders` | Stream | 秒杀订单消息队列 | XGROUP CREATE |

> ⚠️ **重要**：如果 Redis 被 FLUSHALL 或重启且未持久化，必须重新执行第二步的所有预热命令，否则 GEO 查询返回空、秒杀接口报 500 错误。

## 六、常见问题

### Q: 启动报 `Communications link failure`
→ MySQL 未启动或 `application.yaml` 中密码/端口不正确。

### Q: 秒杀返回 `{"success":false,"errorMsg":"服务器异常"}`
→ 秒杀库存未预热（`seckill:stock:{id}` 不存在），执行步骤 2.1。

### Q: GEO 搜索返回空数组
→ 商铺 GEO 数据未预热，执行步骤 2.3。

### Q: Broker: `NOGROUP No such key 'stream.orders' or consumer group 'g1'`
→ Stream 消费者组未创建，执行步骤 2.2。

### Q: 秒杀券已过期
→ `tb_seckill_voucher` 表中的 `begin_time` / `end_time` 需要是当前或未来时间。SQL 种子数据已包含有效期到 2027-01-01 的券。

### Q: 启动报 `Port 8081 was already in use`
→ 上次未正常关闭，端口仍被占用。执行以下命令杀掉旧进程后重新启动：
```bash
# 查找占用 8081 端口的进程 PID
netstat -ano | findstr ":8081"
# 杀掉该进程（替换 <PID> 为实际值）
taskkill //PID <PID> //F
```
