-- =============================================================
--  增量升级脚本：为 dish / setmeal 增加库存字段
--  用途：支持并发下单场景下的库存扣减（乐观并发控制 / 原子条件更新）
--  适用：已经执行过旧版 sky.sql、需要原地升级的数据库
--  执行：mysql -uroot -p smart_campus_delivery < upgrade-20261011-add-stock.sql
-- =============================================================

USE `smart_campus_delivery`;

-- 1、菜品表增加库存字段
ALTER TABLE `dish`
    ADD COLUMN `stock` int NOT NULL DEFAULT 100 COMMENT '库存' AFTER `update_user`;

-- 2、套餐表增加库存字段
ALTER TABLE `setmeal`
    ADD COLUMN `stock` int NOT NULL DEFAULT 100 COMMENT '库存' AFTER `update_user`;

-- 说明：MySQL 在执行 ADD COLUMN 时会把已有行填充为默认值 100，
-- 因此无需额外 UPDATE。若希望统一重置库存，可执行下面两条语句：
-- UPDATE `dish`    SET `stock` = 100;
-- UPDATE `setmeal` SET `stock` = 100;
