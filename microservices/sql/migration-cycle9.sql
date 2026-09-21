-- 周期 9：微服务超时关单。停机窗口执行，可重复运行。
SET @schema_name=DATABASE();

SET @column_exists=(SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@schema_name AND table_name='order' AND column_name='timeout_seconds');
SET @sql=IF(@column_exists=0,
  'ALTER TABLE `order` ADD COLUMN `timeout_seconds` BIGINT NOT NULL DEFAULT 1800 AFTER `total_amount`',
  'SELECT 1');
PREPARE cycle9_stmt FROM @sql;
EXECUTE cycle9_stmt;
DEALLOCATE PREPARE cycle9_stmt;

SET @column_exists=(SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@schema_name AND table_name='order' AND column_name='expire_time');
SET @sql=IF(@column_exists=0,
  'ALTER TABLE `order` ADD COLUMN `expire_time` DATETIME NULL AFTER `pay_time`',
  'SELECT 1');
PREPARE cycle9_stmt FROM @sql;
EXECUTE cycle9_stmt;
DEALLOCATE PREPARE cycle9_stmt;

UPDATE `order`
   SET expire_time=TIMESTAMPADD(SECOND,timeout_seconds,create_time)
 WHERE expire_time IS NULL;

-- 保留根目录单体写入兼容性：单体仍由 Redisson 延迟队列关单，不写 expire_time。
-- 微服务创建订单时会显式写入真实截止时间，因此不会使用该兜底值。
ALTER TABLE `order` MODIFY COLUMN `expire_time` DATETIME NOT NULL
  DEFAULT '9999-12-31 23:59:59';

SET @index_exists=(SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=@schema_name AND table_name='order'
    AND index_name='idx_order_expiration');
SET @sql=IF(@index_exists=0,
  'ALTER TABLE `order` ADD KEY `idx_order_expiration` (`status`,`reservation_status`,`expire_time`,`order_id`)',
  'SELECT 1');
PREPARE cycle9_stmt FROM @sql;
EXECUTE cycle9_stmt;
DEALLOCATE PREPARE cycle9_stmt;
