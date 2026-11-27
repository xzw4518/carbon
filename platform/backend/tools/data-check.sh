#!/usr/bin/env bash
# M11-L3 数据一致性门禁：双字段、孤儿、重复、审计守恒
# 用法：bash tools/data-check.sh [--quiet]
# 退出码 0 = 全部通过；1 = 有违规
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
# shellcheck source=tools/lib-db.sh
. tools/lib-db.sh

QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1

if ! db_ready; then
  echo "SKIP 数据门禁：找不到 mysql 客户端或数据库不可达（$DB_HOST:$DB_PORT/$DB_NAME）"
  echo "     可用 MYSQL_BIN / DB_HOST / DB_PORT / DB_USER / DB_PASSWORD 覆盖"
  exit 0
fi

PASS=0; FAIL=0; FAILED_NAMES=""

check() { # name | sql
  local name="$1" q="$2" n
  n="$(sql_scalar "$q")"
  if [ -z "$n" ]; then
    FAIL=$((FAIL+1)); FAILED_NAMES="$FAILED_NAMES $name"
    [ $QUIET -eq 0 ] && echo "[FAIL] $name — 查询执行失败"
    return
  fi
  if [ "$n" = "0" ]; then
    PASS=$((PASS+1))
    [ $QUIET -eq 0 ] && echo "[PASS] $name"
  else
    FAIL=$((FAIL+1)); FAILED_NAMES="$FAILED_NAMES $name"
    [ $QUIET -eq 0 ] && { echo "[FAIL] $name — $n 条"; echo "       $q"; }
  fi
}

# ---------- 双字段一致 ----------
check "字段·核算期合计=当前版本明细合计" "
select count(*) from accounting_period p
where p.total is not null
  and abs(p.total - coalesce((select sum(l.emission) from accounting_line l
        where l.period_id=p.id and l.calc_version=p.calc_version),0)) > 0.05"

check "字段·核算期总量=范围一+范围二" "
select count(*) from accounting_period
where abs(coalesce(scope1,0)+coalesce(scope2,0)-coalesce(total,0)) > 0.05"

check "字段·明细排放=活动数据×因子" "
select count(*) from accounting_line
where factor_value is not null and activity is not null
  and abs(emission - activity*factor_value) > 0.0001"

check "字段·交易金额=数量×单价" "
select count(*) from carbon_trade
where amount is not null and price is not null and abs(amount-qty*price) > 0.01"

check "字段·报告文件大小=内容字节数" "
select count(*) from report
where file_size is not null and content is not null and file_size <> length(content)"

check "字段·资产数量非负" "select count(*) from carbon_asset where qty < 0"

check "字段·读数数量非负" "select count(*) from device_reading where qty < 0"

# ---------- 孤儿数据 ----------
check "孤儿·核算明细→核算期" "
select count(*) from accounting_line l
where not exists(select 1 from accounting_period p where p.id=l.period_id)"

check "孤儿·核算明细→设备" "
select count(*) from accounting_line l
where l.device_id is not null and not exists(select 1 from device d where d.id=l.device_id)"

check "孤儿·核算明细→因子" "
select count(*) from accounting_line l
where l.factor_id is not null and not exists(select 1 from emission_factor f where f.id=l.factor_id)"

check "孤儿·读数→设备" "
select count(*) from device_reading r
where not exists(select 1 from device d where d.id=r.device_id)"

check "孤儿·交易→资产" "
select count(*) from carbon_trade t
where not exists(select 1 from carbon_asset a where a.id=t.asset_id)"

check "孤儿·报告→核算期" "
select count(*) from report r
where r.period_id is not null and not exists(select 1 from accounting_period p where p.id=r.period_id)"

check "孤儿·第三方报告→附件" "
select count(*) from third_party_report r
where r.file_id is not null and not exists(select 1 from file_object f where f.id=r.file_id)"

check "孤儿·成员→账号" "
select count(*) from user_tenant m
where not exists(select 1 from app_user u where u.id=m.user_id)"

check "孤儿·成员→企业" "
select count(*) from user_tenant m
where not exists(select 1 from org o where o.id=m.org_id)"

check "孤儿·设施→上级设施" "
select count(*) from facility f
where f.parent_id is not null and f.parent_id <> 0
  and not exists(select 1 from facility p where p.id=f.parent_id)"

check "孤儿·产值→产品" "
select count(*) from production_value v
where v.product_id is not null and not exists(select 1 from product p where p.id=v.product_id)"

check "孤儿·附件→凭证" "
select count(*) from file_object f
where f.biz_type='evidence' and f.biz_id is not null
  and not exists(select 1 from evidence e where e.id=f.biz_id)"

check "孤儿·角色→企业" "
select count(*) from tenant_role r
where not exists(select 1 from org o where o.id=r.org_id)"

check "孤儿·角色页面→角色" "
select count(*) from tenant_role_page p
where not exists(select 1 from tenant_role r where r.id=p.role_id)"

check "孤儿·成员角色→角色" "
select count(*) from user_tenant t
where t.status <> 'removed' and t.role_id is not null
  and not exists(select 1 from tenant_role r where r.id=t.role_id)"

check "唯一·每租户一个默认角色" "
select count(*) from (select org_id from tenant_role where is_default=1 and disabled=0
  group by org_id having count(*)>1) x"

# ---------- 唯一性 ----------
check "重复·手机号（未注销账号）" "
select count(*) from (select phone from app_user where deleted_at is null
  group by phone having count(*)>1) x"

check "重复·企业成员（同一企业同一人）" "
select count(*) from (select org_id,user_id from user_tenant where status='active'
  group by org_id,user_id having count(*)>1) x"

check "重复·企业编码" "
select count(*) from (select code from org where code is not null and code<>''
  group by code having count(*)>1) x"

check "重复·读数幂等键" "
select count(*) from (select org_id,client_request_id from device_reading
  where client_request_id is not null and client_request_id<>''
  group by org_id,client_request_id having count(*)>1) x"

check "重复·核算明细（期间+版本+读数）" "
select count(*) from (select period_id,calc_version,reading_id from accounting_line
  group by period_id,calc_version,reading_id having count(*)>1) x"

# ---------- 审计守恒 ----------
check "审计·记录缺操作人" "select count(*) from audit_log where actor is null or actor=''"

check "审计·记录缺企业" "select count(*) from audit_log where org_id is null"

check "审计·已提交期间缺 submit 记录" "
select count(*) from accounting_period p where p.submitted_at is not null
  and not exists(select 1 from audit_log a
    where a.entity='accounting_period' and a.entity_id=p.id and a.action='submit')"

check "审计·已复核期间缺 review 记录" "
select count(*) from accounting_period p where p.reviewed_at is not null
  and not exists(select 1 from audit_log a
    where a.entity='accounting_period' and a.entity_id=p.id and a.action='review')"

check "审计·已批准期间缺 approve 记录" "
select count(*) from accounting_period p where p.approved_at is not null
  and not exists(select 1 from audit_log a
    where a.entity='accounting_period' and a.entity_id=p.id and a.action='approve')"

echo
if [ $FAIL -eq 0 ]; then
  echo "RESULT: PASS ($PASS checks)"
else
  echo "RESULT: FAIL ($PASS passed, $FAIL failed) —$FAILED_NAMES"
fi
[ $FAIL -eq 0 ]
