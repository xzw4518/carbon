-- V96 读数换算率精度：NUMERIC(12,4) 无法表示柴油 0.00085 t/L
-- 扩到 6 位后修正历史种子（只改客户端种子，不动人工补录数据）。

ALTER TABLE device_reading MODIFY COLUMN rate NUMERIC(12,6) NOT NULL DEFAULT 1;

UPDATE device_reading
SET rate = 0.000850
WHERE device_id = 6
  AND unit = 'L'
  AND rate = 0.000900
  AND client_request_id LIKE 'seed-%';
