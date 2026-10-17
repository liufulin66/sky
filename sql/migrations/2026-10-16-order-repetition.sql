-- 再来一单的持久化幂等记录。先审阅，在已完成2026-10-15订单迁移的业务库手动执行一次。
-- 应用不会自动执行。没有修改历史订单或购物车的语句。
CREATE TABLE order_repetition (
    user_id BIGINT NOT NULL COMMENT '操作用户',
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '再来一单的独立幂等键',
    order_id BIGINT NOT NULL COMMENT '来源订单',
    create_time DATETIME NOT NULL COMMENT '本次成功追加的时间',
    PRIMARY KEY (user_id, request_id),
    INDEX idx_order_repetition_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='再来一单的成功操作记录';
