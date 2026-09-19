-- 周期 5 增量迁移：Redis 预扣成功后，将订单持久化命令异步落库。
-- 已有 fulfillment 库执行一次；命令按 order_id 幂等。
CREATE TABLE IF NOT EXISTS `async_order_command` (
  `command_id`       BIGINT       NOT NULL AUTO_INCREMENT,
  `order_id`         BIGINT       NOT NULL COMMENT '订单ID，命令幂等键',
  `user_id`          BIGINT       NOT NULL,
  `total_amount`     DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `timeout_ms`       BIGINT       NOT NULL COMMENT '待支付超时时间，毫秒',
  `items_json`       TEXT         NOT NULL COMMENT 'StockReservationItem JSON',
  -- 0=待处理 1=处理中(持有租约) 2=已完成 3=死信
  `status`           TINYINT      NOT NULL DEFAULT 0,
  `retry_count`      INT          NOT NULL DEFAULT 0,
  `next_retry_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `lease_owner`      VARCHAR(128) DEFAULT NULL,
  `lease_until`      DATETIME     DEFAULT NULL,
  `last_error`       VARCHAR(500) DEFAULT NULL,
  `dead_letter_time` DATETIME     DEFAULT NULL,
  `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`command_id`),
  UNIQUE KEY `uk_async_order_command_order` (`order_id`),
  KEY `idx_async_order_command_ready` (`status`, `next_retry_time`, `lease_until`, `command_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Redis预扣后的异步订单持久化命令';
