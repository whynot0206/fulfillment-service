-- V2 阶段 1：Commerce 数据所有权建库脚本（用户、商品、购物车、结算幂等）。
-- 用具备 CREATE 权限的管理账号执行，可重复运行。
--
-- 数据所有权（DESIGN_V2 §6.3）：
--   commerce 拥有用户、商品 SPU/SKU 展示事实、购物车；
--   可售库存由 fulfillment_inventory 拥有，本库不保存任何库存数量字段。
--
-- 跨库标识约定：product_sku.id 必须等于 fulfillment_inventory.sku_stock.sku_id。
--   SKU 编号是跨服务的业务主键，不是 Commerce 私有自增值，因此本表不用 AUTO_INCREMENT。
--   新增 SKU 时必须同时在 Inventory 建库存行，否则下单会在预占阶段被拒。

CREATE DATABASE IF NOT EXISTS `fulfillment_commerce`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ---------------------------------------------------------------
-- 用户
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fulfillment_commerce`.`user_account` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(64) NOT NULL,
  `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt 摘要，禁止保存明文',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1正常 2禁用',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_account_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户账号';

CREATE TABLE IF NOT EXISTS `fulfillment_commerce`.`user_address` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `receiver` VARCHAR(64) NOT NULL,
  `phone` VARCHAR(32) NOT NULL,
  `address_snapshot` VARCHAR(500) NOT NULL COMMENT '整段收货地址，下单时整体复制进订单',
  `is_default` TINYINT(1) NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_address_user` (`user_id`, `is_default`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收货地址';

-- ---------------------------------------------------------------
-- 商品
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fulfillment_commerce`.`product_category` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT '0 表示一级分类',
  `name` VARCHAR(64) NOT NULL,
  `sort_order` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1可见 2隐藏',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_category_parent_sort` (`parent_id`, `sort_order`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类，仅用于展示与筛选';

CREATE TABLE IF NOT EXISTS `fulfillment_commerce`.`product_spu` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(128) NOT NULL,
  `description` VARCHAR(1000) NOT NULL DEFAULT '',
  `brand` VARCHAR(64) NOT NULL DEFAULT '',
  `category_id` BIGINT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1上架 2下架',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_spu_status_id` (`status`, `id`) COMMENT '列表页按上架状态翻页',
  KEY `idx_spu_category_status` (`category_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品 SPU';

CREATE TABLE IF NOT EXISTS `fulfillment_commerce`.`product_sku` (
  `id` BIGINT NOT NULL COMMENT '必须等于 fulfillment_inventory.sku_stock.sku_id，不自增',
  `spu_id` BIGINT NOT NULL,
  `sku_code` VARCHAR(64) NOT NULL,
  `spec_json` VARCHAR(500) NOT NULL DEFAULT '{}',
  `price` DECIMAL(12,2) NOT NULL COMMENT '销售价，与订单 DECIMAL(12,2) 口径一致',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1上架 2下架',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_sku_code` (`sku_code`),
  KEY `idx_sku_spu_status` (`spu_id`, `status`, `id`),
  CONSTRAINT `chk_product_sku_price_non_negative` CHECK (`price` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品 SKU，不含库存数量';

CREATE TABLE IF NOT EXISTS `fulfillment_commerce`.`product_image` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `spu_id` BIGINT NOT NULL,
  `url` VARCHAR(500) NOT NULL,
  `sort_order` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_image_spu_sort` (`spu_id`, `sort_order`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品图片';

-- ---------------------------------------------------------------
-- 购物车
-- MySQL 是购物车事实，Redis 只做读缓存。唯一键让「同一用户重复加购同一 SKU」
-- 落到一行 upsert，而不是靠先查后插。
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fulfillment_commerce`.`cart_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `sku_id` BIGINT NOT NULL,
  `quantity` INT NOT NULL,
  `selected` TINYINT(1) NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cart_user_sku` (`user_id`, `sku_id`),
  KEY `idx_cart_user_update` (`user_id`, `update_time`),
  CONSTRAINT `chk_cart_quantity_positive` CHECK (`quantity` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车条目';

-- ---------------------------------------------------------------
-- 结算幂等
--
-- 口径说明：DESIGN_V2 §17 把「Idempotency-Key + 用户」记在 Order DB。
-- 本脚本放在 Commerce DB，原因是 HTTP Idempotency-Key 在 Commerce 入口收到，
-- 而 Order 现有的幂等边界是 orderId 唯一键 + 载荷摘要（周期 8 已实测）。
-- 两处同时记会形成两个互相竞争的幂等事实。这一处偏离需要本人确认后写回 DESIGN_V2。
--
-- request_digest 存「规范化后的购物车快照摘要」，用于区分
-- 同键同载荷（返回既有订单）与同键异载荷（返回 409）。
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fulfillment_commerce`.`checkout_request` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `idempotency_key` VARCHAR(128) NOT NULL,
  `request_digest` CHAR(64) NOT NULL COMMENT 'SHA-256 十六进制',
  `total_amount` DECIMAL(12,2) NULL COMMENT '提交时服务端计算的金额，用于清车后安全重放',
  `order_id` BIGINT NULL COMMENT '下游订单号，尚未分配时为 NULL',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0处理中 1已提交 2已拒绝',
  `last_error` VARCHAR(500) NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_checkout_user_key` (`user_id`, `idempotency_key`),
  UNIQUE KEY `uk_checkout_order` (`order_id`),
  KEY `idx_checkout_status_create` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算请求幂等记录';

-- ---------------------------------------------------------------
-- 兼容性补丁：已建过表的环境补索引，避免直接改历史 CREATE 语句
-- ---------------------------------------------------------------
SET @commerce_schema = 'fulfillment_commerce';

SET @column_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = @commerce_schema AND table_name = 'checkout_request'
    AND column_name = 'total_amount');
SET @sql = IF(@column_exists = 0,
  'ALTER TABLE `fulfillment_commerce`.`checkout_request` ADD COLUMN `total_amount` DECIMAL(12,2) NULL AFTER `request_digest`',
  'SELECT 1');
PREPARE commerce_stmt FROM @sql; EXECUTE commerce_stmt; DEALLOCATE PREPARE commerce_stmt;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = @commerce_schema AND table_name = 'cart_item'
    AND index_name = 'uk_cart_user_sku');
SET @sql = IF(@index_exists = 0,
  'ALTER TABLE `fulfillment_commerce`.`cart_item` ADD UNIQUE KEY `uk_cart_user_sku` (`user_id`,`sku_id`)',
  'SELECT 1');
PREPARE commerce_stmt FROM @sql; EXECUTE commerce_stmt; DEALLOCATE PREPARE commerce_stmt;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = @commerce_schema AND table_name = 'user_account'
    AND index_name = 'uk_user_account_username');
SET @sql = IF(@index_exists = 0,
  'ALTER TABLE `fulfillment_commerce`.`user_account` ADD UNIQUE KEY `uk_user_account_username` (`username`)',
  'SELECT 1');
PREPARE commerce_stmt FROM @sql; EXECUTE commerce_stmt; DEALLOCATE PREPARE commerce_stmt;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = @commerce_schema AND table_name = 'checkout_request'
    AND index_name = 'uk_checkout_user_key');
SET @sql = IF(@index_exists = 0,
  'ALTER TABLE `fulfillment_commerce`.`checkout_request` ADD UNIQUE KEY `uk_checkout_user_key` (`user_id`,`idempotency_key`)',
  'SELECT 1');
PREPARE commerce_stmt FROM @sql; EXECUTE commerce_stmt; DEALLOCATE PREPARE commerce_stmt;
