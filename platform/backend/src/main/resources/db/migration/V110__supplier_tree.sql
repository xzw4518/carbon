-- 供应链上下级：parent_id 为空表示根节点（一级供应商）
ALTER TABLE supplier ADD COLUMN parent_id BIGINT NULL;
CREATE INDEX idx_supplier_org_parent ON supplier (org_id, parent_id, disabled);

-- 历史数据全部保持根节点；层级文本统一为中文（原先混有 '1'/'2'）
UPDATE supplier SET level = '一级' WHERE level IS NULL OR level = '' OR level = '1';
UPDATE supplier SET level = '二级' WHERE level = '2';
