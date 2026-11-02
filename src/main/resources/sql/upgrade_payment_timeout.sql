-- 停止旧实例，先执行 upgrade_order_lifecycle.sql，再执行本脚本。
-- 历史待支付订单从原建单时间计算；按部署配置修改此值。
SET @payment_timeout_seconds = 900;
SET @payment_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_order_reservation' AND COLUMN_NAME='payment_expires_at') = 0,
  'ALTER TABLE tb_order_reservation ADD COLUMN payment_expires_at datetime DEFAULT NULL COMMENT ''支付截止时间，从建单成功开始''',
  'SELECT 1');
PREPARE payment_stmt FROM @payment_ddl;
EXECUTE payment_stmt;
DEALLOCATE PREPARE payment_stmt;

SET @payment_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_order_reservation' AND INDEX_NAME='idx_payment_expiry') = 0,
  'ALTER TABLE tb_order_reservation ADD INDEX idx_payment_expiry (status,payment_expires_at)',
  'SELECT 1');
PREPARE payment_stmt FROM @payment_ddl;
EXECUTE payment_stmt;
DEALLOCATE PREPARE payment_stmt;

UPDATE tb_order_reservation r JOIN tb_voucher_order o ON o.id=r.order_id
SET r.payment_expires_at=TIMESTAMPADD(SECOND,@payment_timeout_seconds,o.create_time)
WHERE r.status=1 AND r.payment_expires_at IS NULL;
