# 校园生活服务平台

面向校园场景的本地生活服务应用，有商铺、优惠券、秒杀、笔记和关注几个模块。主要精力花在秒杀下单链路上。

秒杀最初是把订单塞进内存阻塞队列，由后台线程消费。单机演示没问题，但进程一重启队列里的订单就没了，多实例部署也完全没法用。后来换成 RabbitMQ 做异步落库，顺带把重复投递、发送失败、消费失败这几件事一起处理掉。现在这条链路是：Redis 预扣库存 → 本地消息表 → MQ → 消费建单 → MySQL 兜底，中间每一步失败都能回退。

单体应用，Spring Boot + MySQL + Redis + RabbitMQ，没有微服务。

## 技术栈

Java 8、Spring Boot 2.3、MyBatis-Plus、MySQL 8、Redis（Lettuce + Redisson）、RabbitMQ、Caffeine、Micrometer

## 运行

需要 JDK 8、MySQL 8、Redis、RabbitMQ。

建库并导入数据：

```bash
mysql -uroot -p -e "CREATE DATABASE campus_life DEFAULT CHARACTER SET utf8mb4"
mysql -uroot -p campus_life < sql/01-schema-and-data.sql
mysql -uroot -p campus_life < src/main/resources/sql/upgrade_seckill_v2.sql
```

连接信息在 `application.yaml` 里，都支持环境变量覆盖，不填就走本机默认值。如果 Redis 或 RabbitMQ 在虚拟机上，把 `src/main/resources/application-local.yaml` 里的地址改一下，启动时加 `--spring.profiles.active=local`。这个文件在 `.gitignore` 里，不会提交。

```bash
mvn spring-boot:run
```

前端页面在 `nginx-1.18.0/html`，挂到 Nginx 上就能打开。

## 几个设计上的选择

锁放在事务外面。建单时要先按用户加 Redisson 锁再做查重，一开始把锁加在 `@Transactional` 方法里，后来发现锁是在方法返回时释放的，而事务在方法返回之后才提交，中间有窗口，两个请求能同时通过查重。现在锁包在最外层，事务用 `TransactionTemplate` 只圈住真正写库的那一段。

消息先落库再发。Redis 扣完库存、消息还没发出去的那一瞬间如果进程挂了，库存扣了但订单没了，用户还查不到。所以改成先写一条本地消息表记录（独立事务，提交后才返回），再投递。投递失败由定时任务扫表补发，Broker 的 Confirm 回调负责把记录标成已确认。

消费失败不再扔回队列。Spring AMQP 默认是 AUTO ack，监听方法抛异常消息就会 requeue，一条坏消息能把所有消费线程占满。现在捕获异常后落一条重试记录、正常返回，由定时任务按退避重试，超过次数再回滚 Redis 并把用户从"一人一单"集合里移除。

超时死信要让着重试任务。延迟队列 15 秒后检查订单有没有入库，没有就回滚库存。但入库可能正卡在重试表里排队，两边各回滚一次就变成超卖了。所以死信处理前先查有没有重试记录，有就跳过。

缓存那边是常规组合：Caffeine + Redis 两级、布隆过滤器拦穿透、逻辑过期配合独立线程池重建防击穿，更新时用 Pub/Sub 广播让集群节点清本地缓存。

## 故障注入

`diagnosis.experiment.enabled=true` 之后会多出一组接口，用来停消费者、加处理延迟、把依赖标记成不可用，方便复现问题：

```
POST /internal/experiments/seckill/consumer?action=stop
POST /internal/experiments/seckill/delay?processingMs=800
POST /internal/experiments/seckill/dependency?target=mysql
POST /internal/experiments/seckill/scenario?target=pool
POST /internal/experiments/seckill/reset
```

默认关闭，生产环境不要打开。

## 已知问题

- 本地消息表重试次数用完会停在待重发状态，不会自动回滚 Redis 库存，需要人工或补偿任务处理。
- 超时死信如果比首次消费失败先到，理论上还有一次重复回滚的竞态，彻底解决要把补偿做成幂等。
- 锁的租期固定 10 秒，没用看门狗。锁内只有一次查重和一个本地事务，超过 10 秒基本就是数据库有问题了。

## 说明

最早是跟着一个 Redis 实战课程做的 demo，只包含缓存和秒杀的基础实现。下单链路的异步化、幂等、可靠投递、重试和监控部分都是后来重写的。
