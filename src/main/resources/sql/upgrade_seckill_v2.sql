-- 秒杀链路可靠性升级：防重表 / 本地消息表 / 订单重试表
-- 在 hmdp 库中执行一次即可，全部使用 IF NOT EXISTS，可重复执行。

CREATE TABLE IF NOT EXISTS `tb_order_dedup` (
  `order_id`    bigint  NOT NULL COMMENT '订单号（雪花ID）',
  `user_id`     bigint  NOT NULL COMMENT '下单用户',
  `voucher_id`  bigint  NOT NULL COMMENT '代金券ID',
  `status`      tinyint NOT NULL DEFAULT 0 COMMENT '0=处理中 1=已建单',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`order_id`),
  UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单防重表：主键挡重复消息，唯一键兜一人一单';

CREATE TABLE IF NOT EXISTS `tb_local_message` (
  `id`              bigint       NOT NULL AUTO_INCREMENT,
  `message_id`      varchar(64)  NOT NULL COMMENT '业务消息ID（订单号）',
  `exchange`        varchar(128) NOT NULL,
  `routing_key`     varchar(128) NOT NULL,
  `payload`         text         NOT NULL,
  `status`          tinyint      NOT NULL DEFAULT 0 COMMENT '0=待投递 1=已确认 2=待重发 3=放弃',
  `retry_count`     int          NOT NULL DEFAULT 0,
  `next_retry_time` datetime     DEFAULT NULL,
  `last_error`      varchar(512) DEFAULT NULL,
  `create_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_id` (`message_id`),
  KEY `idx_status_next` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表（事务性outbox）';

CREATE TABLE IF NOT EXISTS `tb_order_retry` (
  `order_id`        bigint       NOT NULL,
  `payload`         text         NOT NULL,
  `retry_count`     int          NOT NULL DEFAULT 0,
  `status`          tinyint      NOT NULL DEFAULT 0 COMMENT '0=待重试 1=成功 2=放弃',
  `next_retry_time` datetime     NOT NULL,
  `last_error`      varchar(512) DEFAULT NULL,
  `create_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`order_id`),
  KEY `idx_status_next` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单入库重试表';

