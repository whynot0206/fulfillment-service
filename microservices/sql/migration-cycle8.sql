-- 周期 8：订单创建幂等与订单明细持久化。停机窗口执行，可重复运行。
CREATE TABLE IF NOT EXISTS `order_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT NOT NULL,
  `sku_id` BIGINT NOT NULL,
  `spu_id` BIGINT NOT NULL,
  `count` INT NOT NULL,
  `price` DECIMAL(12,2) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细';

SET @schema_name=DATABASE();
SET @index_exists=(SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=@schema_name AND table_name='order_item'
    AND index_name='uk_order_item_order_sku');
SET @sql=IF(@index_exists=0,
  'ALTER TABLE `order_item` ADD UNIQUE KEY `uk_order_item_order_sku` (`order_id`,`sku_id`)',
  'SELECT 1');
PREPARE cycle8_stmt FROM @sql;
EXECUTE cycle8_stmt;
DEALLOCATE PREPARE cycle8_stmt;
