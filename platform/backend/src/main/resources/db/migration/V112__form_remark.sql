-- 表单备注统一落库：多页面的「备注」此前只有前端输入框，后端无对应列
ALTER TABLE product ADD COLUMN remark VARCHAR(500);
ALTER TABLE production_value ADD COLUMN remark VARCHAR(500);
ALTER TABLE total_value ADD COLUMN remark VARCHAR(500);
ALTER TABLE carbon_asset ADD COLUMN remark VARCHAR(500);
ALTER TABLE evidence ADD COLUMN remark VARCHAR(500);
ALTER TABLE third_party_report ADD COLUMN remark VARCHAR(500);
ALTER TABLE alert_event ADD COLUMN remark VARCHAR(500);
ALTER TABLE retrofit ADD COLUMN remark VARCHAR(500);
ALTER TABLE energy_mgmt ADD COLUMN remark VARCHAR(500);
