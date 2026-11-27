-- V2 种子数据：单企业 + 6 台设备 + 2026-11 读数 + 三级因子 + 一个 draft 期间
-- 用户账号由 DataSeeder 在启动时用 BCrypt 生成，避免在 SQL 里硬编码密码哈希。

INSERT INTO org (name, code) VALUES ('华东精密制造有限公司', 'HDJM');

INSERT INTO device (org_id, name, code, energy_type, unit, status) VALUES
  (1, '空压机 A1',   'EQ-A101', '外购电力', 'kWh', 'online'),
  (1, '数控车床 L3', 'EQ-L103', '外购电力', 'kWh', 'online'),
  (1, '燃气加热炉',  'EQ-G201', '天然气',   'Nm3', 'online'),
  (1, '电表 B1',     'EQ-E301', '外购电力', 'kWh', 'online'),
  (1, '蒸汽表 S1',   'EQ-S401', '外购蒸汽', 'GJ',  'online'),
  (1, '柴油发电机',  'EQ-D501', '柴油',     'L',   'offline');

INSERT INTO device_reading (org_id, device_id, ts, qty, unit, rate, source, status, client_request_id, created_by) VALUES
  (1, 1, '2026-11-05 08:00:00', 51200.0000, 'kWh', 0.0010, '自动采集', '正常', 'seed-01', 'filler'),
  (1, 2, '2026-11-12 08:00:00', 26400.0000, 'kWh', 0.0010, '自动采集', '正常', 'seed-02', 'filler'),
  (1, 3, '2026-11-18 08:00:00', 14200.0000, 'Nm3', 0.0001, '手工补录', '正常', 'seed-03', 'filler'),
  (1, 4, '2026-11-20 08:00:00',  8800.0000, 'kWh', 0.0010, '自动采集', '正常', 'seed-04', 'filler'),
  (1, 5, '2026-11-20 08:00:00',    86.0000, 'GJ',  1.0000, '自动采集', '正常', 'seed-05', 'filler'),
  (1, 6, '2026-11-22 08:00:00',   350.0000, 'L',   0.00085,'手工补录', '正常', 'seed-06', 'filler');

INSERT INTO emission_factor (category, fuel, unit, factor_value, source_doc, scope, org_id, version, review_status, effective_from, created_by) VALUES
  ('fuel',        '天然气',   '万Nm3', 21.622000, '生态环境部 2024 年度缺省值', 'national', NULL, '2024', 'approved', '2024-01-01', 'admin'),
  ('fuel',        '柴油',     '吨',     3.095600, '生态环境部 2024 年度缺省值', 'national', NULL, '2024', 'approved', '2024-01-01', 'admin'),
  ('fuel',        '汽油',     '吨',     2.925100, '生态环境部 2024 年度缺省值', 'national', NULL, '2024', 'approved', '2024-01-01', 'admin'),
  ('electricity', '外购电力', 'MWh',    0.570300, '全国电网平均排放因子 2024',   'national', NULL, '2024', 'approved', '2024-01-01', 'admin'),
  ('steam',       '外购蒸汽', 'GJ',     0.110000, '蒸汽缺省因子 2024',           'national', NULL, '2024', 'approved', '2024-01-01', 'admin'),
  ('electricity', '外购电力', 'MWh',    0.561700, '江苏省电网排放因子 2024',     'local',    NULL, '2024-js','approved', '2024-01-01', 'admin'),
  ('fuel',        '天然气',   '万Nm3', 21.840000, '企业实测热值折算（客户自定义）','customer', 1,    'v1',    'approved', '2024-01-01', 'admin');

INSERT INTO accounting_period (org_id, period, status, version) VALUES (1, '2026-11', 'draft', 0);
