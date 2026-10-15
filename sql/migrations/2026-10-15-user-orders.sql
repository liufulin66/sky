-- 用户订单接口迁移。先备份并审阅，在目标业务库手动执行一次；应用不会自动执行此文件。
-- 不要重新导入包含DROP TABLE的sky-take-out.sql。
-- 先运行以下检查。若存在重复非空订单号，先人工处理，再执行ALTER；不自动修改历史订单。
SELECT number, COUNT(*) AS duplicate_count FROM orders
WHERE number IS NOT NULL GROUP BY number HAVING COUNT(*) > 1;

-- 历史订单的request_id/request_hash保留NULL；MySQL唯一索引允许多个NULL。
-- 新订单由应用强制填写request_id/request_hash。
ALTER TABLE orders
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    MODIFY COLUMN consignee VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '收货人快照',
    MODIFY COLUMN address VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '收货地址快照',
    ADD COLUMN request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '用户下单幂等键',
    ADD COLUMN request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '规范化请求SHA-256',
    ADD COLUMN delivery_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '配送费快照（元）',
    ADD UNIQUE INDEX idx_order_number (number),
    ADD UNIQUE INDEX idx_order_user_request (user_id, request_id),
    ADD INDEX idx_order_user_time (user_id, order_time, id);

ALTER TABLE order_detail ADD INDEX idx_order_detail_order (order_id);
