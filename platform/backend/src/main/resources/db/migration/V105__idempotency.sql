-- 幂等键：POST 创建类接口携带 Idempotency-Key 时，24h 内重复提交返回首次结果
CREATE TABLE idempotency_record (
  id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id        BIGINT       NOT NULL,
  actor_id      BIGINT       NULL,
  idem_key      VARCHAR(128) NOT NULL,
  method        VARCHAR(10)  NOT NULL,
  path          VARCHAR(255) NOT NULL,
  request_hash  VARCHAR(64)  NOT NULL,
  status_code   INT          NOT NULL DEFAULT 0,
  response_body MEDIUMTEXT   NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at    DATETIME     NOT NULL,
  CONSTRAINT uk_idem_org_key UNIQUE (org_id, idem_key)
);

CREATE INDEX idx_idem_expire ON idempotency_record (expires_at);
