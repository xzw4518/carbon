-- V95 设施管理：厂区—车间两级（可扩展多级）设施树
-- 编码同企业内唯一由服务层按 disabled=0 校验（软删与数据库唯一索引冲突）。
-- tree_level 存层级，sort_no 存同级排序；parent_id 为空表示根节点。

CREATE TABLE facility (
  id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id      BIGINT       NOT NULL,
  name        VARCHAR(120) NOT NULL,
  code        VARCHAR(64)  NOT NULL,
  parent_id   BIGINT,
  tree_level  INT          NOT NULL DEFAULT 1,
  sort_no     INT          NOT NULL DEFAULT 1,
  remark      VARCHAR(500),
  disabled    TINYINT(1)   NOT NULL DEFAULT 0,
  version     INT          NOT NULL DEFAULT 0,
  created_by  VARCHAR(64),
  updated_by  VARCHAR(64),
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_facility_org ON facility (org_id, parent_id, disabled);
CREATE INDEX idx_facility_code ON facility (org_id, code);

INSERT INTO facility (org_id, name, code, parent_id, tree_level, sort_no, remark, created_by) VALUES
  (1, '一号厂区',   'F1',   NULL, 1, 1, '含机加工、总装两个车间', 'admin'),
  (1, '二号厂区',   'F2',   NULL, 1, 2, '含表面处理车间',         'admin');

INSERT INTO facility (org_id, name, code, parent_id, tree_level, sort_no, remark, created_by) VALUES
  (1, '机加工车间', 'F1-1', 1, 2, 1, '', 'admin'),
  (1, '总装车间',   'F1-2', 1, 2, 2, '', 'admin'),
  (1, '表面处理车间','F2-1', 2, 2, 1, '', 'admin');
