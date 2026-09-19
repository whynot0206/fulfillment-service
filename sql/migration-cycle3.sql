-- 周期 3 增量迁移：已有 fulfillment 库执行一次。
CREATE TABLE IF NOT EXISTS `order_outbox_event` (
  `event_id`       BIGINT       NOT NULL AUTO_INCREMENT,
  `event_type`     VARCHAR(64)  NOT NULL,
  `biz_key`        VARCHAR(128) NOT NULL,
  `payload`        VARCHAR(1000) NOT NULL,
  `status`         TINYINT      NOT NULL DEFAULT 0,
  `retry_count`    INT          NOT NULL DEFAULT 0,
  `next_retry_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error`     VARCHAR(500) DEFAULT NULL,
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  UNIQUE KEY `uk_event_type_biz_key` (`event_type`, `biz_key`),
  KEY `idx_outbox_status_retry` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单本地消息表';
