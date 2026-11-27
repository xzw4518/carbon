-- 树化后历史数据全部为根节点，层级统一回一级（原「一级/二级」标签不再代表真实层级）
UPDATE supplier SET level = '一级' WHERE parent_id IS NULL AND (level IS NULL OR level <> '一级');
