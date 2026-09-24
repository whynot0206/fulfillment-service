-- V2 阶段 2：订单明细补商品快照字段，并为「我的订单」列表补索引。
-- 对 `fulfillment_order` 库执行，用具备 ALTER 权限的管理账号。可重复运行。
--
-- 为什么快照要落在订单侧而不是查商品服务：
--   订单是一份**已经发生的事实**。商品改价、改名、下架之后，历史订单必须还能原样显示
--   下单当时的名称、规格和成交价。如果订单页现查 Commerce，改一次价历史订单金额就全变了，
--   而且 Commerce 停机会连带订单详情打不开——把展示依赖建在另一个服务的可用性上。
--
-- 为什么可空：
--   周期 8~11 已经产生的订单行没有快照，这是事实。设成 NOT NULL DEFAULT '' 需要重写全表，
--   而且会给历史行编造一个「商品名是空串」的假事实。NULL 的含义是「这单下单时没记快照」，
--   前端据此回退显示 skuId，是准确的。

SET @schema_name = 'fulfillment_order';

-- ---------------------------------------------------------------
-- order_item.name_snapshot / spec_snapshot
-- 长度与 fulfillment_commerce.product_spu.name(128)、product_sku.spec_json(500) 一一对齐。
-- 对不齐的话，超长的商品名会在写订单时被 MySQL 截断或报错，
-- 而这两种结果都比「快照字段够长」更难排查。
-- ---------------------------------------------------------------
SET @column_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = @schema_name AND table_name = 'order_item'
    AND column_name = 'name_snapshot');
SET @sql = IF(@column_exists = 0,
  'ALTER TABLE `fulfillment_order`.`order_item`
     ADD COLUMN `name_snapshot` VARCHAR(128) NULL COMMENT ''下单时的商品名，历史订单只认这一份''',
  'SELECT 1');
PREPARE v2_snapshot_stmt FROM @sql;
EXECUTE v2_snapshot_stmt;
DEALLOCATE PREPARE v2_snapshot_stmt;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = @schema_name AND table_name = 'order_item'
    AND column_name = 'spec_snapshot');
SET @sql = IF(@column_exists = 0,
  'ALTER TABLE `fulfillment_order`.`order_item`
     ADD COLUMN `spec_snapshot` VARCHAR(500) NULL COMMENT ''下单时的规格 JSON''',
  'SELECT 1');
PREPARE v2_snapshot_stmt FROM @sql;
EXECUTE v2_snapshot_stmt;
DEALLOCATE PREPARE v2_snapshot_stmt;

-- ---------------------------------------------------------------
-- 「我的订单」列表索引
--
-- 查询形状固定是：WHERE user_id = ? ORDER BY create_time DESC, order_id DESC。
-- 索引列顺序就按这个形状排：等值列在前，排序列在后，MySQL 才能用索引序直接取前 N 行，
-- 不用 filesort。把 order_id 也放进去是为了 create_time 同秒时排序稳定——
-- 排序不稳定会让翻页出现重复或漏掉的行，而这类 bug 只在数据量上来以后才看得见。
--
-- 现有的 idx_order_reservation_status / idx_order_expiration 都是后台任务用的，
-- 前缀列不是 user_id，这个查询用不上。
-- ---------------------------------------------------------------
SET @index_exists = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = @schema_name AND table_name = 'order'
    AND index_name = 'idx_order_user_time');
SET @sql = IF(@index_exists = 0,
  'ALTER TABLE `fulfillment_order`.`order`
     ADD KEY `idx_order_user_time` (`user_id`, `create_time`, `order_id`)',
  'SELECT 1');
PREPARE v2_snapshot_stmt FROM @sql;
EXECUTE v2_snapshot_stmt;
DEALLOCATE PREPARE v2_snapshot_stmt;
