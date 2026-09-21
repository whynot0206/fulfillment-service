-- 周期 11：从共享 fulfillment 库一次性复制到 Order / Inventory 独立 schema。
-- 在停机窗口用具备 CREATE/SELECT/INSERT 权限的管理账号执行。
-- 脚本使用 INSERT IGNORE，重复执行不会覆盖目标库中已经推进的业务状态。

CREATE DATABASE IF NOT EXISTS `fulfillment_order`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `fulfillment_inventory`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `fulfillment_order`.`order` (
  `order_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `total_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `timeout_seconds` BIGINT NOT NULL DEFAULT 1800,
  `status` TINYINT NOT NULL DEFAULT 1,
  `reservation_status` TINYINT NOT NULL DEFAULT 0,
  `reservation_error` VARCHAR(500) NULL,
  `out_trade_no` VARCHAR(64) NULL,
  `pay_time` DATETIME NULL,
  `expire_time` DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`order_id`),
  UNIQUE KEY `uk_order_out_trade_no` (`out_trade_no`),
  KEY `idx_order_reservation_status` (`reservation_status`,`update_time`),
  KEY `idx_order_expiration` (`status`,`reservation_status`,`expire_time`,`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `fulfillment_order`.`order_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT NOT NULL,
  `sku_id` BIGINT NOT NULL,
  `spu_id` BIGINT NOT NULL,
  `count` INT NOT NULL,
  `price` DECIMAL(12,2) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_item_order_sku` (`order_id`,`sku_id`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `fulfillment_order`.`order_outbox_event` (
  `event_id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_type` VARCHAR(64) NOT NULL,
  `biz_key` VARCHAR(128) NOT NULL,
  `payload` VARCHAR(1000) NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 0,
  `retry_count` INT NOT NULL DEFAULT 0,
  `next_retry_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error` VARCHAR(500) NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  UNIQUE KEY `uk_event_type_biz_key` (`event_type`,`biz_key`),
  KEY `idx_outbox_status_retry` (`status`,`next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `fulfillment_order`.`microservice_order_command` (
  `command_id` BIGINT NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `total_amount` DECIMAL(12,2) NOT NULL,
  `timeout_seconds` BIGINT NOT NULL,
  `items_json` TEXT NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 0,
  `redis_reserved` TINYINT(1) NOT NULL DEFAULT 0,
  `retry_count` INT NOT NULL DEFAULT 0,
  `next_retry_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `lease_owner` VARCHAR(128) NULL,
  `lease_until` DATETIME NULL,
  `last_error` VARCHAR(500) NULL,
  `dead_letter_time` DATETIME NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`command_id`),
  UNIQUE KEY `uk_microservice_order_command_order` (`order_id`),
  KEY `idx_microservice_order_command_ready`
      (`status`,`next_retry_time`,`lease_until`,`command_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `fulfillment_inventory`.`sku_stock` (
  `sku_id` BIGINT NOT NULL,
  `spu_id` BIGINT NOT NULL,
  `stock` INT NOT NULL DEFAULT 0,
  `lock_stock` INT NOT NULL DEFAULT 0,
  `version` INT NOT NULL DEFAULT 0,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`sku_id`),
  KEY `idx_spu` (`spu_id`),
  CONSTRAINT `chk_inventory_stock_non_negative` CHECK (`stock` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `fulfillment_inventory`.`sku_stock_lock` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT NOT NULL,
  `sku_id` BIGINT NOT NULL,
  `spu_id` BIGINT NOT NULL,
  `count` INT NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_sku` (`order_id`,`sku_id`),
  KEY `idx_sku_status` (`sku_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `fulfillment_inventory`.`inventory_reservation_fence` (
  `order_id` BIGINT NOT NULL,
  `status` TINYINT NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `fulfillment_inventory`.`inventory_redis_reservation` (
  `order_id` BIGINT NOT NULL,
  `items_json` TEXT NOT NULL,
  `status` TINYINT NOT NULL COMMENT '1待MySQL落库 2已落库 3已补偿',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`order_id`),
  KEY `idx_inventory_redis_reservation_status` (`status`,`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO `fulfillment_order`.`order`
    (`order_id`,`user_id`,`total_amount`,`timeout_seconds`,`status`,`reservation_status`,
     `reservation_error`,`out_trade_no`,`pay_time`,`expire_time`,`create_time`,`update_time`)
SELECT `order_id`,`user_id`,`total_amount`,`timeout_seconds`,`status`,`reservation_status`,
       `reservation_error`,`out_trade_no`,`pay_time`,`expire_time`,`create_time`,`update_time`
  FROM `fulfillment`.`order`;
INSERT IGNORE INTO `fulfillment_order`.`order_item`
    (`id`,`order_id`,`sku_id`,`spu_id`,`count`,`price`)
SELECT `id`,`order_id`,`sku_id`,`spu_id`,`count`,`price` FROM `fulfillment`.`order_item`;
INSERT IGNORE INTO `fulfillment_order`.`order_outbox_event`
    (`event_id`,`event_type`,`biz_key`,`payload`,`status`,`retry_count`,`next_retry_time`,
     `last_error`,`create_time`,`update_time`)
SELECT `event_id`,`event_type`,`biz_key`,`payload`,`status`,`retry_count`,`next_retry_time`,
       `last_error`,`create_time`,`update_time`
  FROM `fulfillment`.`order_outbox_event`;
INSERT IGNORE INTO `fulfillment_order`.`microservice_order_command`
    (`command_id`,`order_id`,`user_id`,`total_amount`,`timeout_seconds`,`items_json`,`status`,
     `redis_reserved`,`retry_count`,`next_retry_time`,`lease_owner`,`lease_until`,`last_error`,
     `dead_letter_time`,`create_time`,`update_time`)
SELECT `command_id`,`order_id`,`user_id`,`total_amount`,`timeout_seconds`,`items_json`,`status`,
       `redis_reserved`,`retry_count`,`next_retry_time`,`lease_owner`,`lease_until`,`last_error`,
       `dead_letter_time`,`create_time`,`update_time`
  FROM `fulfillment`.`microservice_order_command`;

INSERT IGNORE INTO `fulfillment_inventory`.`sku_stock`
    (`sku_id`,`spu_id`,`stock`,`lock_stock`,`version`,`update_time`)
SELECT `sku_id`,`spu_id`,`stock`,`lock_stock`,`version`,`update_time`
  FROM `fulfillment`.`sku_stock`;
INSERT IGNORE INTO `fulfillment_inventory`.`sku_stock_lock`
    (`id`,`order_id`,`sku_id`,`spu_id`,`count`,`status`,`create_time`,`update_time`)
SELECT `id`,`order_id`,`sku_id`,`spu_id`,`count`,`status`,`create_time`,`update_time`
  FROM `fulfillment`.`sku_stock_lock`;
INSERT IGNORE INTO `fulfillment_inventory`.`inventory_reservation_fence`
    (`order_id`,`status`,`create_time`,`update_time`)
SELECT `order_id`,`status`,`create_time`,`update_time`
  FROM `fulfillment`.`inventory_reservation_fence`;

-- 已经执行共享库周期 11 的环境先完整复制账本，保留已物化和已补偿事实。
INSERT IGNORE INTO `fulfillment_inventory`.`inventory_redis_reservation`
    (`order_id`,`items_json`,`status`,`create_time`,`update_time`)
SELECT `order_id`,`items_json`,`status`,`create_time`,`update_time`
  FROM `fulfillment`.`inventory_redis_reservation`;

-- 周期 10 没有库存侧账本。只把仍在 READY/PROCESSING 的 Redis 命令投影为待落库；
-- 已完成命令对应的 MySQL 可售库存已经扣减，不应继续计入 pending。
SET SESSION group_concat_max_len = 16777216;
INSERT IGNORE INTO `fulfillment_inventory`.`inventory_redis_reservation`
    (`order_id`,`items_json`,`status`,`create_time`,`update_time`)
SELECT c.`order_id`,
       CONCAT('[', GROUP_CONCAT(
           CONCAT('{"skuId":', jt.`sku_id`,
                  ',"spuId":', jt.`spu_id`,
                  ',"count":', jt.`item_count`, '}')
           ORDER BY jt.`ordinality` SEPARATOR ','), ']'),
       1,c.`create_time`,c.`update_time`
  FROM `fulfillment`.`microservice_order_command` c
  JOIN JSON_TABLE(c.`items_json`, '$[*]' COLUMNS (
       `ordinality` FOR ORDINALITY,
       `sku_id` BIGINT PATH '$.skuId',
       `spu_id` BIGINT PATH '$.spuId',
       `item_count` INT PATH '$.count'
  )) jt
 WHERE c.`redis_reserved`=1 AND c.`status` IN (1,2)
 GROUP BY c.`order_id`,c.`create_time`,c.`update_time`;
