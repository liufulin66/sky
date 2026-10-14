-- 在当前业务数据库执行。先检查重复：有结果时停止并人工核对关联数据。
SELECT openid, COUNT(*) AS duplicate_count
FROM `user`
WHERE openid IS NOT NULL
GROUP BY openid
HAVING COUNT(*) > 1;

-- 确认没有重复，且 SHOW INDEX FROM `user` 中没有 openid 单列唯一索引后，执行以下语句。
-- 若并发写入产生重复，MySQL 会拒绝建立索引，不会删除已有用户。
ALTER TABLE `user` ADD UNIQUE INDEX idx_user_openid (openid);
