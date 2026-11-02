# 校园生活服务平台

面向校园场景的本地生活服务应用，包含商铺、优惠券、秒杀、笔记和关注。基于 Redis 实战课程 demo 扩展，主要重构秒杀下单链路，补充可靠投递、幂等、重试和监控。

## 技术栈

Java 8 · Spring Boot 2.3 · MyBatis-Plus · MySQL 8 · Redis（Lettuce / Redisson）· RabbitMQ · Caffeine · Micrometer。采用单体架构。

## 核心设计

- **异步下单**：Redis Lua 校验库存与一人一单并预占；受理状态与本地消息表在同一事务提交，再通过 RabbitMQ 异步建单。接口返回订单号表示受理成功，最终结果以订单状态为准。
- **可靠投递与幂等**：Outbox 定时补发、Confirm 确认投递；消费异常落库后退避重试。Redisson 用户锁放在事务外，防重表、扣库存、建单和受理状态更新放在同一数据库事务内。
- **分阶段超时**：受理后默认等待建单 15 秒，原延迟队列只检查待建单状态；建单成功后另设 15 分钟支付期限，由数据库按索引定时扫描关单，服务重启后继续处理。
- **取消与补偿**：建单和取消通过 `SELECT … FOR UPDATE` 锁定同一条受理记录，取消墓碑拦截迟到消息。未支付订单关闭与数据库库存恢复同事务提交，随后 Lua 校验预占订单号、幂等释放 Redis，失败补偿持久化重试。修复了超时先回补库存、迟到消息又建单的问题。
- **缓存与观测**：Caffeine + Redis 两级缓存、布隆过滤器、逻辑过期和独立线程池重建，Pub/Sub 通知本地缓存失效；Micrometer 记录消费与数据库耗时，支持实验环境故障注入。

## 本地运行

需要 JDK 8、MySQL 8、Redis、RabbitMQ，依次导入：

```bash
mysql -uroot -p -e "CREATE DATABASE campus_life DEFAULT CHARACTER SET utf8mb4"
mysql -uroot -p campus_life < sql/01-schema-and-data.sql
mysql -uroot -p campus_life < src/main/resources/sql/upgrade_seckill_v2.sql
mysql -uroot -p campus_life < src/main/resources/sql/upgrade_order_lifecycle.sql
mysql -uroot -p campus_life < src/main/resources/sql/upgrade_payment_timeout.sql
mvn spring-boot:run
```

连接信息见 `src/main/resources/application.yaml`，支持环境变量覆盖；虚拟机配置可写入已忽略的 `application-local.yaml`，启动时加 `--spring.profiles.active=local`。

| 配置（`seckill.order` 下） | 默认值 | 用途 |
| --- | --- | --- |
| `timeout-seconds` | 15 | 从受理开始的建单等待期限 |
| `payment-timeout-seconds` | 900 | 从建单成功开始的支付期限 |
| `sweep-interval-ms` | 5000 | 扫描间隔，每类每批最多 100 条，积压时关单会延后 |

已有部署先停止旧实例，再执行迁移脚本并启动新版本。支付迁移按历史订单创建时间补齐截止时间；自定义时长时同步调整脚本变量。更改建单超时时长需迁移原 RabbitMQ 延迟队列的 TTL 参数，支付时长不依赖队列 TTL。

## 验证与实验

`mvn test`：13 个订单生命周期单元测试，覆盖迟到建单、两阶段超时隔离、已支付保护、重复关单及 Redis 补偿重试；生成真实用户 token 的辅助测试默认跳过。

本地 MySQL / Redis / RabbitMQ 回归 8 项通过，覆盖超时隔离、重复消息、重启恢复、支付保护与库存守恒；测试使用建单 2 秒、支付 6 秒缩短等待。

实验接口默认关闭，启用 `diagnosis.experiment.enabled=true` 后，可通过 `/internal/experiments/seckill/consumer`、`delay`、`dependency` 和 `reset` 注入及恢复故障。仅用于本地实验。

## 边界与后续扩展

- Redis 预占与数据库受理提交之间仍有进程崩溃窗口，尚未实现未受理预占的自动对账；受理状态与取消墓碑尚未归档。Redisson 使用固定 10 秒租期，长事务需进一步治理。
- 支付超时目前以数据库扫描为准。如需更及时的触发，可增加支付延迟队列，并复用 Outbox：建单事务同时保存超时消息，按剩余支付时间投递，消费时校验状态与截止时间，保留数据库扫描兜底。当前未实现此扩展。
