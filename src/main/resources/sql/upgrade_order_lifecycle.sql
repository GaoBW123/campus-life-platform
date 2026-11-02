-- 停止旧版本后执行，再启动新版本。取消状态保留，作为拦截迟到消息的墓碑。
CREATE TABLE IF NOT EXISTS tb_order_reservation (
  order_id bigint NOT NULL,
  user_id bigint unsigned NOT NULL,
  voucher_id bigint unsigned NOT NULL,
  status tinyint NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=CREATED 2=CANCELLED 3=COMPLETED',
  expires_at datetime NOT NULL,
  redis_released tinyint(1) NOT NULL DEFAULT 0,
  cancel_reason varchar(512) DEFAULT NULL,
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (order_id),
  KEY idx_expiry (status,expires_at),
  KEY idx_release (status,redis_released,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 历史数据按原来 15 秒超时补状态；如原部署时长不同，执行前修改此变量。
SET @order_timeout_seconds = 15;
INSERT IGNORE INTO tb_order_reservation(order_id,user_id,voucher_id,status,expires_at)
SELECT CAST(m.message_id AS UNSIGNED),JSON_UNQUOTE(JSON_EXTRACT(m.payload,'$.userId')),
       JSON_UNQUOTE(JSON_EXTRACT(m.payload,'$.voucherId')),
       CASE WHEN o.id IS NULL THEN 0 WHEN o.status=1 THEN 1 WHEN o.status=4 THEN 2 ELSE 3 END,
       TIMESTAMPADD(SECOND,@order_timeout_seconds,m.create_time)
FROM tb_local_message m
LEFT JOIN tb_voucher_order o ON o.id=CAST(m.message_id AS UNSIGNED)
WHERE m.message_id REGEXP '^[0-9]+$' AND JSON_VALID(m.payload)
  AND JSON_EXTRACT(m.payload,'$.userId') IS NOT NULL
  AND JSON_EXTRACT(m.payload,'$.voucherId') IS NOT NULL;

INSERT IGNORE INTO tb_order_reservation(order_id,user_id,voucher_id,status,expires_at)
SELECT id,user_id,voucher_id,CASE WHEN status=1 THEN 1 WHEN status=4 THEN 2 ELSE 3 END,
       TIMESTAMPADD(SECOND,@order_timeout_seconds,create_time)
FROM tb_voucher_order;
