-- 租户授权期：开始日期 + 到期时间（复用 org.expire_at，语义为“到期日 23:59:59”）
ALTER TABLE org ADD COLUMN license_start_at DATE NULL;

-- 历史租户以创建日期作为授权开始，避免出现空值
UPDATE org SET license_start_at = CAST(created_at AS DATE) WHERE license_start_at IS NULL;

-- 状态枚举统一为 pending / active / suspended / expired；历史 disabled 归入 suspended
UPDATE org SET status = 'suspended' WHERE status = 'disabled';

CREATE INDEX idx_org_status_expire ON org (status, expire_at);
