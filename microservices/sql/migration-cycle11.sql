-- 周期 11：Inventory 自有的 Redis 预扣账本。
-- 在继续使用共享 fulfillment 库时执行；可重复运行。
CREATE TABLE IF NOT EXISTS `inventory_redis_reservation` (
  `order_id` BIGINT NOT NULL,
  `items_json` TEXT NOT NULL COMMENT '规范化后的库存项目快照',
  `status` TINYINT NOT NULL COMMENT '1待MySQL落库 2已落库 3已补偿',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`order_id`),
  KEY `idx_inventory_redis_reservation_status` (`status`,`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Inventory Redis预扣事实投影';

-- 共享库升级兼容：只把仍在 READY/PROCESSING 的 Redis 命令投影为待落库。
-- 价格字段属于 Order；账本只保留 Inventory 所需的 skuId/spuId/count。
SET SESSION group_concat_max_len = 16777216;
INSERT IGNORE INTO `inventory_redis_reservation`
    (`order_id`,`items_json`,`status`,`create_time`,`update_time`)
SELECT c.`order_id`,
       CONCAT('[', GROUP_CONCAT(
           CONCAT('{"skuId":', jt.`sku_id`,
                  ',"spuId":', jt.`spu_id`,
                  ',"count":', jt.`item_count`, '}')
           ORDER BY jt.`ordinality` SEPARATOR ','), ']'),
       1,c.`create_time`,c.`update_time`
  FROM `microservice_order_command` c
  JOIN JSON_TABLE(c.`items_json`, '$[*]' COLUMNS (
       `ordinality` FOR ORDINALITY,
       `sku_id` BIGINT PATH '$.skuId',
       `spu_id` BIGINT PATH '$.spuId',
       `item_count` INT PATH '$.count'
  )) jt
 WHERE c.`redis_reserved`=1 AND c.`status` IN (1,2)
 GROUP BY c.`order_id`,c.`create_time`,c.`update_time`;
