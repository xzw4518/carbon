-- 设备台账扩展：设施归属、类型/级别、备注、乐观锁
ALTER TABLE device ADD COLUMN facility_id BIGINT NULL;
ALTER TABLE device ADD COLUMN device_type VARCHAR(32) NULL;
ALTER TABLE device ADD COLUMN level VARCHAR(32) NULL;
ALTER TABLE device ADD COLUMN remark VARCHAR(255) NULL;
ALTER TABLE device ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE device ADD COLUMN updated_at TIMESTAMP NULL;

UPDATE device SET device_type = '电表', level = '次级用能单位' WHERE device_type IS NULL;
UPDATE device SET facility_id = (SELECT MIN(f.id) FROM facility f WHERE f.org_id = device.org_id) WHERE facility_id IS NULL;
