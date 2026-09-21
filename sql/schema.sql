-- 高并发库存与订单履约中台 — 初始化脚本
-- 单体演进阶段使用。Nacos 相关表在真正拆分服务时再加。
-- 设计参考 mall4cloud 的 sku_stock / sku_stock_lock 模型（AGPLv3，仅参考设计，未拷贝代码）。

SET NAMES utf8mb4;

-- ---------------------------------------------------------------
-- 库存主表：三态模型
--   stock       可售库存（下单时扣减）
--   lock_stock  锁定库存（预占中，支付后转实际扣减）
--   实际库存 = stock + lock_stock（未发货前）
-- ---------------------------------------------------------------
DROP TABLE IF EXISTS `sku_stock`;
CREATE TABLE `sku_stock` (
  `sku_id`      BIGINT      NOT NULL COMMENT 'SKU ID',
  `spu_id`      BIGINT      NOT NULL COMMENT 'SPU ID',
  `stock`       INT         NOT NULL DEFAULT 0 COMMENT '可售库存',
  `lock_stock`  INT         NOT NULL DEFAULT 0 COMMENT '锁定库存',
  `version`     INT         NOT NULL DEFAULT 0 COMMENT '周期5用于乐观锁与原子SQL对照的预留版本号',
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`sku_id`),
  KEY `idx_spu` (`spu_id`),
  -- 数据库兜底：可售库存不允许为负。先查后扣的主要错误表现为旧值覆盖，
  -- 库存通常不会变负，因此超卖必须用“成功订单数 - 实际扣减量”统计。
  CONSTRAINT `chk_stock_non_negative` CHECK (`stock` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU库存(三态)';

-- ---------------------------------------------------------------
-- 库存锁定记录：维护预占 → 释放/确认 的状态流转
-- ---------------------------------------------------------------
DROP TABLE IF EXISTS `sku_stock_lock`;
CREATE TABLE `sku_stock_lock` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `order_id`    BIGINT      NOT NULL COMMENT '订单ID',
  `sku_id`      BIGINT      NOT NULL COMMENT 'SKU ID',
  `spu_id`      BIGINT      NOT NULL COMMENT 'SPU ID',
  `count`       INT         NOT NULL COMMENT '锁定数量',
  -- 1=已锁定(预占中) 2=已释放(订单取消) 3=已确认(支付成功,实扣)
  `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '锁定状态',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 同一订单同一SKU只能有一条锁定记录 → 天然幂等，重复下单请求会撞唯一键
  UNIQUE KEY `uk_order_sku` (`order_id`, `sku_id`),
  KEY `idx_sku_status` (`sku_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存锁定记录';

-- ---------------------------------------------------------------
-- 订单
-- ---------------------------------------------------------------
DROP TABLE IF EXISTS `order`;
CREATE TABLE `order` (
  `order_id`      BIGINT       NOT NULL COMMENT '订单ID(雪花或自增)',
  `user_id`       BIGINT       NOT NULL,
  `total_amount`  DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '金额用DECIMAL,不要用double',
  `timeout_seconds` BIGINT     NOT NULL DEFAULT 1800 COMMENT '待支付超时时间，秒',
  -- 1=待支付 2=已支付 3=已取消(超时关单) 4=已关闭
  `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '订单状态',
  `reservation_status` TINYINT NOT NULL DEFAULT 0 COMMENT '0预占中 1已预占 2待补偿 3已补偿 4失败',
  `reservation_error` VARCHAR(500) DEFAULT NULL,
  `out_trade_no`  VARCHAR(64)  DEFAULT NULL COMMENT '外部支付交易号',
  `pay_time`      DATETIME     DEFAULT NULL,
  `expire_time`   DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '微服务订单支付截止时间；单体由延迟队列关单',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`order_id`),
  -- 支付回调幂等的基础：同一外部交易号只能对应一次成功支付(第 3 周用)
  UNIQUE KEY `uk_out_trade_no` (`out_trade_no`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_status_create` (`status`, `create_time`),
  KEY `idx_order_reservation_status` (`reservation_status`, `update_time`),
  KEY `idx_order_expiration` (`status`, `reservation_status`, `expire_time`, `order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单';

-- ---------------------------------------------------------------
-- 订单明细
-- ---------------------------------------------------------------
DROP TABLE IF EXISTS `order_item`;
CREATE TABLE `order_item` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `order_id`    BIGINT       NOT NULL,
  `sku_id`      BIGINT       NOT NULL,
  `spu_id`      BIGINT       NOT NULL,
  `count`       INT          NOT NULL,
  `price`       DECIMAL(12,2) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细';

-- ---------------------------------------------------------------
-- 测试数据：5 个 SKU，各 100 件
-- 压测超卖时就抢这几个
-- ---------------------------------------------------------------
INSERT INTO `sku_stock` (`sku_id`, `spu_id`, `stock`, `lock_stock`) VALUES
(1001, 1, 100, 0),
(1002, 1, 100, 0),
(1003, 2, 100, 0),
(1004, 2, 100, 0),
(1005, 3, 100, 0);

-- ---------------------------------------------------------------
-- 本地消息表：支付成功后的库存确认事件
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_outbox_event` (
  `event_id`       BIGINT       NOT NULL AUTO_INCREMENT,
  `event_type`     VARCHAR(64)  NOT NULL,
  `biz_key`        VARCHAR(128) NOT NULL,
  `payload`        VARCHAR(1000) NOT NULL,
  `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '0待投递 1投递中 2已发送',
  `retry_count`    INT          NOT NULL DEFAULT 0,
  `next_retry_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error`     VARCHAR(500) DEFAULT NULL,
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  UNIQUE KEY `uk_event_type_biz_key` (`event_type`, `biz_key`),
  KEY `idx_outbox_status_retry` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单本地消息表';

-- ---------------------------------------------------------------
-- Redis 预扣后的异步订单持久化命令
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `async_order_command` (
  `command_id`       BIGINT       NOT NULL AUTO_INCREMENT,
  `order_id`         BIGINT       NOT NULL COMMENT '订单ID，命令幂等键',
  `user_id`          BIGINT       NOT NULL,
  `total_amount`     DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `timeout_ms`       BIGINT       NOT NULL COMMENT '待支付超时时间，毫秒',
  `items_json`       TEXT         NOT NULL COMMENT 'StockReservationItem JSON',
  `status`           TINYINT      NOT NULL DEFAULT 0 COMMENT '0待处理 1处理中 2已完成 3死信',
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

-- 微服务 Redis 快速路径使用独立命令表，避免单体和 Order Service 的调度器竞争同一行。
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
  `lease_owner` VARCHAR(128) DEFAULT NULL,
  `lease_until` DATETIME DEFAULT NULL,
  `last_error` VARCHAR(500) DEFAULT NULL,
  `dead_letter_time` DATETIME DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`command_id`),
  UNIQUE KEY `uk_microservice_order_command_order` (`order_id`),
  KEY `idx_microservice_order_command_ready` (`status`,`next_retry_time`,`lease_until`,`command_id`),
  KEY `idx_microservice_order_command_reconciliation` (`redis_reserved`,`status`,`command_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微服务Redis预扣后的持久化订单命令';

-- Inventory 自有的 Redis 预扣事实投影。共享库模式保留此表用于兼容，
-- 周期 11 物理拆库后该表只存在于 fulfillment_inventory。
CREATE TABLE IF NOT EXISTS `inventory_redis_reservation` (
  `order_id` BIGINT NOT NULL,
  `items_json` TEXT NOT NULL COMMENT '规范化后的库存项目快照',
  `status` TINYINT NOT NULL COMMENT '1待MySQL落库 2已落库 3已补偿',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`order_id`),
  KEY `idx_inventory_redis_reservation_status` (`status`,`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Inventory Redis预扣事实投影';
