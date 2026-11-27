-- 供应商「备注」此前只有前端输入框与列表列，后端无对应列，保存后静默丢失
ALTER TABLE supplier ADD COLUMN remark VARCHAR(500);
