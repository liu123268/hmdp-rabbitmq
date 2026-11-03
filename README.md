# 黑马点评 · RabbitMQ 版

围绕商户查询、探店分享和优惠券抢购的 Java 后端项目，提供用户登录、商户检索、内容互动、关注与签到等接口。

## 主要功能

- **用户登录**：邮箱验证码登录，Redis 存储登录态，拦截器完成身份恢复、登录校验和有效期刷新。
- **商户查询**：商户分类、名称搜索、详情查询及基于 Redis GEO 的附近商户检索。
- **探店内容**：笔记发布、热门内容查询、点赞与个人内容查询。
- **关注与动态**：用户关注、共同关注及关注动态查询。
- **优惠券秒杀**：Lua 库存与重复购买校验、RabbitMQ 异步订单处理。
- **用户签到**：基于 Redis Bitmap 记录签到并统计连续签到天数。

## 技术栈

Spring Boot、MyBatis-Plus、MySQL、Redis、Redisson、RabbitMQ、Guava、JavaMail、Nginx。

## 秒杀处理流程

```text
抢购请求
  → Guava 令牌桶限制入口请求
  → Redis Lua 校验库存和重复购买，并预扣库存
  → RabbitMQ 交换机路由至订单队列
  → 消费者在 MySQL 事务内条件扣库存、保存订单
```

Guava 限流作用于单个后端实例。Lua 中的 Redis 操作原子执行；数据库事务覆盖数据库扣库存与订单保存。接口返回订单 ID 表示请求已受理，最终订单结果以数据库写入为准。

验证码发送频率通过 Redis ZSet 记录与时间窗口检查进行限制。

## 目录结构

```text
src/main/java/com/hmdp
  config/          框架与中间件配置
  controller/      HTTP 接口
  service/         业务处理
  mapper/          数据访问
  rebbitmq/        订单消息发送与消费
  utils/           缓存、登录态和 ID 等工具
src/main/resources
  db/hmdp.sql      建表脚本与演示数据
  seckill.lua      秒杀资格校验与 Redis 库存预扣
nginx-1.18.0       Nginx 配置及运行文件
```

## 本地运行

需要 MySQL、Redis 和 RabbitMQ。附近商户查询使用的 GEOSEARCH 命令要求 Redis 6.2 或以上版本。本地使用 JDK 17 编译，源码目标版本为 Java 8。

1. 在独立数据库中执行 `src/main/resources/db/hmdp.sql`，避免覆盖已有数据。登录使用邮箱，历史字段 `tb_user.phone` 用于存储登录标识，需确保字段长度可以容纳邮箱。
2. 将 `src/main/resources/application-local.example.yaml` 复制为同目录下的 `application.yaml`，填写本机数据库、Redis、RabbitMQ 连接信息。
3. 配置邮箱 SMTP 环境变量，并启动依赖服务。
4. 在项目根目录执行构建和启动命令。

```powershell
mvn -DskipTests package
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

现有集成测试会写入 Redis 的缓存、GEO 和 HLL 数据，运行时应使用独立测试环境。

### 配置项

| 配置 | 默认示例 | 说明 |
| --- | --- | --- |
| 后端端口 | `18081` | `server.port` |
| MySQL | `127.0.0.1:3306/hmdp` | 修改数据库地址与登录凭据 |
| Redis | `127.0.0.1:6381`，逻辑库 `6` | Redisson 与普通 Redis 客户端共用连接配置 |
| RabbitMQ | `127.0.0.1:5672` | 配置用户权限及消息空间 `/dianping-local` |
| Nginx | `18080` | 代理至后端 `18081` |

真实 `application.yaml` 已通过 `.gitignore` 排除；仓库内的示例使用环境变量读取密码。

### 邮件与图片目录

| 环境变量 | 用途 |
| --- | --- |
| `HMDP_SMTP_USERNAME` | 发件邮箱 |
| `HMDP_SMTP_PASSWORD` | 邮箱 SMTP 授权码 |
| `HMDP_SMTP_HOST` | SMTP 地址，默认 `smtp.qq.com` |
| `HMDP_SMTP_PORT` | SMTP 端口，默认 `587` |
| `HMDP_UPLOAD_DIR` | 图片保存目录，默认 `./nginx-1.18.0/html/hmdp/imgs` |

当前仓库包含后端与 Nginx 配置，业务前端页面需单独准备。使用图片上传时，请确保保存目录存在且具有写入权限。
