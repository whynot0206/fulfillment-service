-- 周期 7 微服务拆分增量迁移。请在停机窗口执行，可重复运行。
CREATE TABLE IF NOT EXISTS `order` (
  `order_id` BIGINT NOT NULL, `user_id` BIGINT NOT NULL,
  `total_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `status` TINYINT NOT NULL DEFAULT 1,
  `reservation_status` TINYINT NOT NULL DEFAULT 0,
  `reservation_error` VARCHAR(500) NULL,
  `out_trade_no` VARCHAR(64) NULL, `pay_time` DATETIME NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`order_id`), UNIQUE KEY `uk_order_out_trade_no` (`out_trade_no`),
  KEY `idx_order_reservation_status` (`reservation_status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @schema_name=DATABASE();
SET @column_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='order' AND column_name='reservation_status');
SET @sql=IF(@column_exists=0,'ALTER TABLE `order` ADD COLUMN `reservation_status` TINYINT NOT NULL DEFAULT 0 AFTER `status`','SELECT 1');
PREPARE cycle7_stmt FROM @sql; EXECUTE cycle7_stmt; DEALLOCATE PREPARE cycle7_stmt;
SET @column_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='order' AND column_name='reservation_error');
SET @sql=IF(@column_exists=0,'ALTER TABLE `order` ADD COLUMN `reservation_error` VARCHAR(500) NULL AFTER `reservation_status`','SELECT 1');
PREPARE cycle7_stmt FROM @sql; EXECUTE cycle7_stmt; DEALLOCATE PREPARE cycle7_stmt;
SET @index_exists=(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='order' AND index_name='idx_order_reservation_status');
SET @sql=IF(@index_exists=0,'ALTER TABLE `order` ADD KEY `idx_order_reservation_status` (`reservation_status`,`update_time`)','SELECT 1');
PREPARE cycle7_stmt FROM @sql; EXECUTE cycle7_stmt; DEALLOCATE PREPARE cycle7_stmt;

CREATE TABLE IF NOT EXISTS `order_outbox_event` (
  `event_id` BIGINT NOT NULL AUTO_INCREMENT, `event_type` VARCHAR(64) NOT NULL,
  `biz_key` VARCHAR(128) NOT NULL, `payload` VARCHAR(1000) NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待投递 1投递中 2已发送 3死信',
  `retry_count` INT NOT NULL DEFAULT 0,
  `next_retry_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error` VARCHAR(500) NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`), UNIQUE KEY `uk_event_type_biz_key` (`event_type`,`biz_key`),
  KEY `idx_outbox_status_retry` (`status`,`next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `inventory_reservation_fence` (
  `order_id` BIGINT NOT NULL, `status` TINYINT NOT NULL COMMENT '1活动 2已取消 3已确认',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 回填单体阶段历史数据，只处理新增列仍为默认值的记录。
UPDATE `order` o SET
  o.reservation_status=CASE WHEN o.status=2 THEN 1 WHEN o.status IN (3,4) THEN 3
    WHEN EXISTS(SELECT 1 FROM sku_stock_lock l WHERE l.order_id=o.order_id AND l.status=1) THEN 1 ELSE 4 END,
  o.reservation_error=CASE WHEN o.status=1 AND NOT EXISTS
    (SELECT 1 FROM sku_stock_lock l WHERE l.order_id=o.order_id AND l.status=1)
    THEN 'cycle7 migration: no active reservation found' ELSE o.reservation_error END
WHERE o.reservation_status=0;

INSERT INTO inventory_reservation_fence(order_id,status)
SELECT order_id,CASE WHEN MAX(status=3)=1 THEN 3 WHEN MAX(status=2)=1 THEN 2 ELSE 1 END
FROM sku_stock_lock GROUP BY order_id
ON DUPLICATE KEY UPDATE status=VALUES(status);
