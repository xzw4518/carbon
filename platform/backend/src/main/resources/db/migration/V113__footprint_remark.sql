-- 碳足迹核算的「备注」：表单一直有输入框，但实体与接口未接收
ALTER TABLE product_footprint ADD COLUMN remark VARCHAR(500);
