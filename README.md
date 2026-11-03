# 黑马点评 · RabbitMQ 版

围绕商户查询、探店分享和优惠券抢购的 Java 后端项目，提供用户登录、商户检索、内容互动、关注与签到等接口。

## 主要功能

- **用户登录**：手机号验证码登录，Redis 存储登录态，拦截器完成身份恢复、登录校验和有效期刷新。本地学习模式下，6 位数字验证码在后端控制台查看。
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

1. 在独立数据库中执行 `src/main/resources/db/hmdp.sql`，避免覆盖已有数据。登录使用手机号，与 `tb_user.phone` 的 11 位字段一致。
2. 将 `src/main/resources/application-local.example.yaml` 复制为同目录下的 `application.yaml`，填写本机数据库、Redis、RabbitMQ 连接信息。
3. 启动依赖服务。当前本地登录流程不需要配置 SMTP，也不发送真实短信。
4. 在项目根目录执行构建和启动命令。

```powershell
mvn -DskipTests package
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

现有集成测试会写入 Redis 的缓存、GEO 和 HLL 数据，运行时应使用独立测试环境。

### 配置项

| 配置 | 默认示例 | 说明 |
| --- | --- | --- |
| 后端端口 | `8081` | `server.port` |
| MySQL | `127.0.0.1:3306/hmdp` | 修改数据库地址与登录凭据 |
| Redis | `127.0.0.1:6381`，逻辑库 `0` | Redisson 与普通 Redis 客户端共用连接配置 |
| RabbitMQ | `127.0.0.1:5672` | 配置用户权限及消息空间 `/dianping-local` |
| Nginx | `8080` | `/api` 请求代理至后端 `8081` |

真实 `application.yaml` 已通过 `.gitignore` 排除；仓库内的示例使用环境变量读取密码。

启动顺序为 MySQL、Redis、RabbitMQ → Java 后端 → Nginx。浏览器页面入口为 `http://localhost:8080/`；`8081` 是后端 HTTP 接口端口，`5672` 是 RabbitMQ 消息连接端口，`15672` 是启用管理插件后的 RabbitMQ 管理页面端口。

端口修改后需要重启 Java 后端，并重新加载 Nginx 配置。以启动日志中的 `Tomcat started on port(s): 8081` 确认实际生效；在后端配置文件中查看 `server.port`，在 Nginx 配置中查看 `listen` 与 `proxy_pass`。

RabbitMQ 连接出现 `Connection refused` 时，应先确认消息服务正在运行并监听 `5672`。打开 Nginx 不会同时启动 Java 后端或 RabbitMQ。

### 登录与图片目录

| 环境变量 | 用途 |
| --- | --- |
| `HMDP_UPLOAD_DIR` | 图片保存目录，默认 `./nginx-1.18.0/html/hmdp/imgs` |

业务前端位于 `nginx-1.18.0/html/hmdp`，通过 Nginx 的 `8080` 端口访问；页面接口统一请求 `/api`，由 Nginx 转发至 `8081`。历史密码登录地址会跳转至手机号验证码登录页。使用图片上传时，请确保保存目录存在且具有写入权限。

在登录页输入手机号，点击“发送验证码”，再到 IDEA 的后端 Run 控制台查找“本地登录验证码（不发送短信）”。输入该 6 位验证码并确认用户协议后登录。验证码存入 Redis，有效期 2 分钟；参考版的发送频率限制继续生效。

登录校验只对普通业务请求判断登录态；Servlet 容器发生错误后派发的错误请求保留原始错误状态，避免把参数错误或服务器异常误报为“请先登录”。
