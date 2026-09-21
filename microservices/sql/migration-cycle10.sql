-- 周期 10：微服务 Redis 快速下单持久化命令。停机窗口执行，可重复运行。
CREATE TABLE IF NOT EXISTS `microservice_order_command` (
  `command_id` BIGINT NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `total_amount` DECIMAL(12,2) NOT NULL,
  `timeout_seconds` BIGINT NOT NULL,
  `items_json` TEXT NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0准备Redis 1待落库 2处理中 3完成 4死信',
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
      (`status`,`next_retry_time`,`lease_until`,`command_id`),
  KEY `idx_microservice_order_command_reconciliation`
      (`redis_reserved`,`status`,`command_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微服务Redis预扣后的持久化订单命令';
