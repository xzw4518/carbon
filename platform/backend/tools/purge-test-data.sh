#!/usr/bin/env bash
# 清理测试数据（TST- / 历史 E2E- 标记）
# 用法：
#   bash tools/purge-test-data.sh                      # 预演，只统计不删除
#   bash tools/purge-test-data.sh --apply              # 删除全部 TST-/E2E- 测试数据
#   bash tools/purge-test-data.sh --marker 203508 --apply          # 只删本次运行（标记含 203508）
#   bash tools/purge-test-data.sh --phones "13900001234,13910005678" --apply
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
# shellcheck source=tools/lib-db.sh
. tools/lib-db.sh

APPLY=0; MARKER=""; PHONES=""
while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=1; shift ;;
    --marker) MARKER="${2:-}"; shift 2 ;;
    --phones) PHONES="${2:-}"; shift 2 ;;
    *) echo "未知参数 $1"; exit 2 ;;
  esac
done

db_ready || { echo "SKIP 数据库不可达"; exit 0; }

if [ -n "$MARKER" ]; then
  CODE_WHERE="(code like '%TST%' or code like '%E2E%') and code like '%$MARKER%'"
  NAME_WHERE="(name like '%TST%' or name like '%E2E%') and name like '%$MARKER%'"
else
  CODE_WHERE="(code like '%TST-%' or code like '%E2E-%')"
  NAME_WHERE="(name like 'TST %' or name like 'E2E %')"
fi

if [ -n "$PHONES" ]; then
  PHONE_LIST="$(printf "'%s'," $(printf '%s' "$PHONES" | tr ',' ' ') | sed 's/,$//')"
  LOG_PHONE_WHERE="phone in ($PHONE_LIST)"
elif [ -n "$MARKER" ]; then
  LOG_PHONE_WHERE="phone like '%$MARKER' and phone <> '13900000000'"
else
  # 1390000% 会误伤平台账号 13900000000，必须显式排除
  LOG_PHONE_WHERE="(phone like '1390000%' or phone like '1391000%' or phone like '1392000%')
    and phone <> '13900000000'"
fi

# app_user 额外排除平台管理员（平台账号可能使用其他测试号段）
USER_WHERE="$LOG_PHONE_WHERE and coalesce(platform_admin,0)=0"
if [ -z "$PHONES" ]; then
  USER_WHERE="($USER_WHERE) or (display_name like 'E2E %' or display_name like 'TST %')"
fi

count() { printf '%-22s %s\n' "$1" "$(sql_scalar "select count(*) from $2")"; }

echo "== 测试数据盘点 =="
count "企业 org"        "org where $CODE_WHERE or $NAME_WHERE"
count "账号 app_user"   "app_user where $USER_WHERE"
count "成员 user_tenant" "user_tenant m where m.user_id in (select id from app_user where $USER_WHERE) or m.org_id in (select id from org where $CODE_WHERE or $NAME_WHERE)"
count "产品 product"    "product where $CODE_WHERE or $NAME_WHERE"
count "凭证 evidence"   "evidence where $CODE_WHERE or $NAME_WHERE"
count "设备 device"     "device where $CODE_WHERE or $NAME_WHERE"
count "设施 facility"   "facility where $CODE_WHERE or $NAME_WHERE"
count "产值 production_value" "production_value where product_name like 'TST%' or product_name like 'E2E%'"
count "配额 quota_year" "quota_year where note in ('TST','E2E')"
count "第三方报告 third_party_report" "third_party_report where $NAME_WHERE"
count "供应商 supplier"  "supplier where $NAME_WHERE"
count "碳资产 carbon_asset" "carbon_asset where $NAME_WHERE"

if [ $APPLY -eq 0 ]; then
  echo
  echo "预演模式：未删除任何数据。确认后加 --apply 执行。"
  exit 0
fi

echo
echo "== 开始清理 =="
# 附件先删物理文件再删记录（只匹配测试命名，避免误删业务附件）
FILE_ROOT="${APP_FILE_ROOT:-./data/files}"
sql "select storage_key from file_object
     where name like 'TST%' or name like 'e2e-upload%' or name like 'ui-%' or name = 'ui.pdf'" \
  | while read -r key; do
      key=$(printf '%s' "$key" | tr -d '\r')
      # 存储路径可能带租户前缀（data/files/<org>/yyyy/MM/...），按文件名定位更稳
      [ -n "$key" ] && find "$FILE_ROOT" -type f -name "$(basename "$key")" -delete 2>/dev/null
    done

sql_exec "
delete from accounting_line where device_id in (select id from device where $CODE_WHERE or $NAME_WHERE);
delete from device_reading where device_id in (select id from device where $CODE_WHERE or $NAME_WHERE);
delete from device where $CODE_WHERE or $NAME_WHERE;
delete from product_footprint where product_id in (select id from product where $CODE_WHERE or $NAME_WHERE);
delete from production_value where product_name like 'TST%' or product_name like 'E2E%';
delete from product where $CODE_WHERE or $NAME_WHERE;
-- 演示租户里被功能用例创建的 TST 供应商/碳资产（含其交易）
delete from carbon_trade where asset_id in (select id from carbon_asset where $NAME_WHERE);
delete from carbon_asset where $NAME_WHERE;
delete from supplier where $NAME_WHERE;
delete from file_object where biz_type='evidence' and biz_id in (select id from evidence where $CODE_WHERE or $NAME_WHERE);
-- 功能用例建的足迹与附件（软删除后会一直留在库里）
delete from product_footprint where name like 'TST %' or name like 'E2E %';
delete from file_object where name like 'TST%' or name like 'E2E%'
  or name like 'e2e-upload%' or name like 'ui-%' or name = 'ui.pdf';
delete from evidence where $CODE_WHERE or $NAME_WHERE;
delete from third_party_report where $NAME_WHERE;
delete from facility where $CODE_WHERE or $NAME_WHERE;
delete from quota_year where note in ('TST','E2E');
delete from user_tenant where user_id in (select id from app_user where $USER_WHERE);
delete from password_history where user_id in (select id from app_user where $USER_WHERE);
delete from phone_history where user_id in (select id from app_user where $USER_WHERE);
delete from login_log where $LOG_PHONE_WHERE;
delete from sms_code where $LOG_PHONE_WHERE;
delete from audit_log where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from tenant_role_page where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from tenant_role where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from user_tenant where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from facility where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
-- 按租户清理业务数据：测试租户删除后不能留下孤儿产品/设备/报告等
delete from device_reading where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from accounting_line where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from accounting_period where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from report where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from product_footprint where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from production_value where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from total_value where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from carbon_trade where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from carbon_asset where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from evidence where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from third_party_report where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from file_object where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from supplier where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from product where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from device where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from quota_year where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from energy_mgmt where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from retrofit where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from alert_record where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from alert_event where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from alert_rule where org_id in (select id from org where $CODE_WHERE or $NAME_WHERE);
delete from org where $CODE_WHERE or $NAME_WHERE;
delete from app_user where $USER_WHERE;
-- 功能测试脚本创建的 135 段账号：只剩 removed 成员关系（登录也进不来），一并清理
delete from user_tenant where user_id in (select id from app_user where phone like '135%');
delete from app_user where phone like '135%'
  and not exists (select 1 from user_tenant t where t.user_id = app_user.id and t.status <> 'removed');
-- 平台审计（org_id=0）里指向已删除测试租户的行，以及残留的孤儿审计，随测试数据一起清理
delete from audit_log where org_id = 0 and entity = 'tenant'
  and entity_id not in (select cast(id as char) from org);
delete from audit_log where org_id <> 0 and org_id not in (select id from org);
" >/dev/null

LEFT=0
for t in "org where $CODE_WHERE or $NAME_WHERE" \
         "app_user where $USER_WHERE" \
         "product where $CODE_WHERE or $NAME_WHERE" \
         "evidence where $CODE_WHERE or $NAME_WHERE" \
         "device where $CODE_WHERE or $NAME_WHERE" \
         "facility where $CODE_WHERE or $NAME_WHERE" \
         "supplier where $NAME_WHERE" \
         "carbon_asset where $NAME_WHERE"; do
  n=$(sql_scalar "select count(*) from $t")
  LEFT=$((LEFT + ${n:-0}))
done

if [ "$LEFT" -eq 0 ]; then
  echo "RESULT: CLEAN（残留 0）"
else
  echo "RESULT: LEFT $LEFT（仍有残留，检查外键或匹配规则）"
  exit 1
fi
