-- V2 演示种子数据。可重复执行（INSERT IGNORE / ON DUPLICATE KEY UPDATE）。
-- 先执行 migration-v2-commerce.sql 建表。
--
-- SKU 编号与 fulfillment_inventory.sku_stock 的 1001-1005 一一对应，
-- SPU 编号与 sku_stock.spu_id 的 1/2/3 一致。改这里必须同步改库存种子，
-- 否则商品页能看到但下单会在预占阶段被 Inventory 拒绝。
--
-- 这里不写任何演示用户：密码摘要属于凭证，不进仓库。
-- 启动后调 POST /api/auth/register 自行注册。

INSERT INTO `fulfillment_commerce`.`product_category` (`id`, `parent_id`, `name`, `sort_order`, `status`) VALUES
  (1, 0, '数码外设', 10, 1),
  (2, 0, '家居日用', 20, 1)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `sort_order` = VALUES(`sort_order`), `status` = VALUES(`status`);

INSERT INTO `fulfillment_commerce`.`product_spu`
  (`id`, `name`, `description`, `brand`, `category_id`, `status`) VALUES
  (1, '机械键盘 K87', '87 键紧凑布局，热插拔轴座，支持有线与 2.4G 双模。', 'Nocturne', 1, 1),
  (2, '无线鼠标 M2', '轻量化对称设计，1000Hz 回报率，Type-C 快充。', 'Nocturne', 1, 1),
  (3, '保温杯 T500', '316 不锈钢内胆，24 小时保温，一体成型无焊点。', 'Kettle', 2, 1)
ON DUPLICATE KEY UPDATE
  `name` = VALUES(`name`), `description` = VALUES(`description`),
  `brand` = VALUES(`brand`), `category_id` = VALUES(`category_id`), `status` = VALUES(`status`);

INSERT INTO `fulfillment_commerce`.`product_sku`
  (`id`, `spu_id`, `sku_code`, `spec_json`, `price`, `status`) VALUES
  (1001, 1, 'K87-BLACK-BROWN', '{"配色":"夜黑","轴体":"茶轴"}',  399.00, 1),
  (1002, 1, 'K87-WHITE-RED',   '{"配色":"月白","轴体":"红轴"}',  459.00, 1),
  (1003, 2, 'M2-WIRELESS',     '{"连接":"2.4G 无线"}',            199.00, 1),
  (1004, 2, 'M2-WIRED',        '{"连接":"有线"}',                 129.00, 1),
  (1005, 3, 'T500-500ML',      '{"容量":"500ml","颜色":"雾灰"}',   89.00, 1)
ON DUPLICATE KEY UPDATE
  `spu_id` = VALUES(`spu_id`), `sku_code` = VALUES(`sku_code`),
  `spec_json` = VALUES(`spec_json`), `price` = VALUES(`price`), `status` = VALUES(`status`);

INSERT INTO `fulfillment_commerce`.`product_image` (`id`, `spu_id`, `url`, `sort_order`) VALUES
  (1, 1, '/img/spu-1.svg', 0),
  (2, 2, '/img/spu-2.svg', 0),
  (3, 3, '/img/spu-3.svg', 0)
ON DUPLICATE KEY UPDATE `url` = VALUES(`url`), `sort_order` = VALUES(`sort_order`);
