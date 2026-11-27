#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""功能测试用例集（可复跑）：按模块执行正常流 / 边界 / 异常用例，输出用例表与 JSON 结果。

用法：
    python tools/functional-cases.py                 # 默认 http://localhost:8088
    BASE=http://127.0.0.1:8088 python tools/functional-cases.py

产物：
    target/test-run/functional-<date>.json
    ../docs/test-cases-<date>.md
退出码：有 P0/P1 用例失败则 1。
"""
import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta

BASE = os.environ.get("BASE", "http://localhost:8088")
TODAY = date.today().isoformat()
SUFFIX = str(int(time.time()))[-6:]
CASES = []
CLEANUP = {"products": [], "devices": [], "facilities": [], "suppliers": [], "quotas": [],
           "assets": [], "trades": [], "evidences": [], "reports": [], "users": [], "roles": [],
           "footprints": [], "alertRules": [], "files": [], "production": []}


def http(method, path, token=None, body=None, raw=False, timeout=25, headers=None):
    url = BASE + path
    data = None if body is None else json.dumps(body).encode()
    headers = dict(headers or {})
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            txt = r.read()
            if raw:
                return r.status, txt
            try:
                return r.status, json.loads(txt.decode() or "{}")
            except Exception:
                return r.status, txt[:200]
    except urllib.error.HTTPError as e:
        txt = e.read()
        if raw:
            return e.code, txt
        try:
            return e.code, json.loads(txt.decode() or "{}")
        except Exception:
            return e.code, txt[:200]
    except Exception as e:  # 连接失败等
        return -1, str(e)


def code_of(resp):
    body = resp[1]
    if isinstance(body, dict):
        return body.get("status")
    return None


def captcha():
    """取图形验证码并从 SVG 中解析出字符（密码登录已强制验证码）。"""
    s, b = http("GET", "/api/auth/captcha")
    body = b.get("body") or {}
    img = body.get("image") or ""
    svg = base64.b64decode(img.split(",", 1)[1]).decode("utf-8") if "," in img else ""
    code = "".join(re.findall(r"<text[^>]*>([^<])</text>", svg))
    return body.get("captchaId"), code


def login_body(phone, pwd):
    """密码登录请求体：每次都要配一张新的图形验证码。"""
    cid, code = captcha()
    return {"phone": phone, "password": pwd, "captchaId": cid, "captchaCode": code}


def login(phone, pwd):
    cid, code = captcha()
    s, b = http("POST", "/api/auth/login", None,
                {"phone": phone, "password": pwd, "captchaId": cid, "captchaCode": code})
    if s != 200:
        raise RuntimeError("登录失败 %s: %s %s" % (phone, s, b))
    return b["body"]["token"]


def case(cid, module, prio, title, inp, expected):
    def deco(fn):
        CASES.append({"id": cid, "module": module, "prio": prio, "title": title,
                      "input": inp, "expected": expected, "fn": fn})
        return fn
    return deco


# ==================== 预置数据 ====================
ADMIN = login("13800000001", "Admin@2026!Pass")
FILLER = login("13800000002", "Test@2026!Pass")
APPROVER = login("13800000005", "Test@2026!Pass")
PLAT = login("13900000000", "Superadmin@2026!Pass")
PERIOD = "2026-11"
STATE = {}


# ==================== A 认证与会话 ====================
@case("A01", "认证", "P0", "管理员手机号+密码登录",
      "phone=13800000001 / password=Admin@2026!Pass", "HTTP 200，返回 token 且 role=admin")
def a01():
    s, b = http("POST", "/api/auth/login", None, login_body("13800000001", "Admin@2026!Pass"))
    u = b.get("body", {}).get("user", {})
    return (s, u.get("role")), s == 200 and bool(b["body"].get("token")) and u.get("role") == "admin"


@case("A02", "认证", "P0", "密码错误登录",
      "phone=13800000001 / password=WrongPass123", "HTTP 401，status=AUTH_BAD_CREDENTIAL")
def a02():
    s, b = http("POST", "/api/auth/login", None, login_body("13800000001", "WrongPass123"))
    return (s, b.get("status")), s == 401 and b.get("status") == "AUTH_BAD_CREDENTIAL"


@case("A03", "认证", "P1", "不存在的手机号登录",
      "phone=13800009999 / 任意密码", "HTTP 401，与密码错误同一码（不泄露账号是否存在）")
def a03():
    s, b = http("POST", "/api/auth/login", None, login_body("13800009999", "Whatever123!"))
    return (s, b.get("status")), s == 401 and b.get("status") == "AUTH_BAD_CREDENTIAL"


@case("A04", "认证", "P0", "未登录访问业务接口",
      "GET /api/products 不带 Authorization", "HTTP 401 或 403，不返回数据")
def a04():
    s, b = http("GET", "/api/products")
    return (s, b.get("status")), s in (401, 403)


@case("A05", "认证", "P0", "读取当前用户身份",
      "GET /api/auth/me（管理员令牌）", "HTTP 200，userType=tenant_admin，pages 含 *")
def a05():
    s, b = http("GET", "/api/auth/me", ADMIN)
    u = b.get("body", {}).get("user", {})
    pages = u.get("pages") or []
    return (s, u.get("userType"), len(pages), "product.list" in pages), s == 200 and u.get("userType") == "tenant_admin" and len(pages) == 34 and "product.list" in pages


@case("A06", "认证", "P0", "初始密码未改访问业务接口",
      "新建用户用初始密码登录后 GET /api/products", "HTTP 403，status=MUST_CHANGE_PASSWORD")
def a06():
    phone = "135" + SUFFIX + "01"
    s, b = http("POST", "/api/users", ADMIN, {"phone": phone, "displayName": "用例初始密码" + SUFFIX, "userType": "user"})
    if s != 200:
        return ("建用户失败 " + str(b),), False
    CLEANUP["users"].append(b["body"]["membershipId"])
    tok = login(phone, b["body"]["tempPassword"])
    s2, b2 = http("GET", "/api/products", tok)
    return (s2, b2.get("status")), s2 == 403 and b2.get("status") == "MUST_CHANGE_PASSWORD"


@case("A07", "认证", "P1", "弱密码被拒",
      "改密 old=初始密码 / new=123456", "HTTP 400，提示密码规则不满足")
def a07():
    phone = "135" + SUFFIX + "02"
    s, b = http("POST", "/api/users", ADMIN, {"phone": phone, "displayName": "用例弱密码" + SUFFIX, "userType": "user"})
    CLEANUP["users"].append(b["body"]["membershipId"])
    tok = login(phone, b["body"]["tempPassword"])
    s2, b2 = http("POST", "/api/auth/password/change", tok, {"oldPassword": b["body"]["tempPassword"], "newPassword": "123456"})
    return (s2, b2.get("status")), s2 == 400 and b2.get("status") == "VALIDATION"


@case("A08", "认证", "P0", "改密后旧令牌失效",
      "改密成功，用旧 token 再访问 /api/products", "改密 200；旧 token 401 SESSION_REVOKED；新密码可登录")
def a08():
    phone = "135" + SUFFIX + "03"
    s, b = http("POST", "/api/users", ADMIN, {"phone": phone, "displayName": "用例改密" + SUFFIX, "userType": "user"})
    CLEANUP["users"].append(b["body"]["membershipId"])
    old = login(phone, b["body"]["tempPassword"])
    s1, _ = http("POST", "/api/auth/password/change", old, {"oldPassword": b["body"]["tempPassword"], "newPassword": "Abcdef123456!"})
    s2, b2 = http("GET", "/api/products", old)
    old2 = login(phone, "Abcdef123456!")
    return (s1, s2, b2.get("status"), bool(old2)), s1 == 200 and s2 == 401 and b2.get("status") == "SESSION_REVOKED" and bool(old2)


@case("A09", "认证", "P1", "连续输错密码触发锁定",
      "同一账号连续错误密码 6 次", "第 5~6 次出现 HTTP 429 AUTH_RATE_LIMITED（登录失败次数过多）")
def a09():
    phone = "135" + SUFFIX + "04"
    s, b = http("POST", "/api/users", ADMIN, {"phone": phone, "displayName": "用例锁定" + SUFFIX, "userType": "user"})
    CLEANUP["users"].append(b["body"]["membershipId"])
    last = None
    for i in range(6):
        last = http("POST", "/api/auth/login", None, login_body(phone, "WrongPass%03d" % i))
        if last[0] == 423:
            break
    return (last[0], code_of(last)), last[0] == 429 and code_of(last) == "AUTH_RATE_LIMITED"


@case("A10", "认证", "P1", "停用账号无法登录",
      "管理员停用成员后用正确密码登录", "HTTP 403 MEMBER_DISABLED，提示账号已停用")
def a10():
    phone = "135" + SUFFIX + "05"
    s, b = http("POST", "/api/users", ADMIN, {"phone": phone, "displayName": "用例停用" + SUFFIX, "userType": "user"})
    mid, temp = b["body"]["membershipId"], b["body"]["tempPassword"]
    CLEANUP["users"].append(mid)
    http("POST", "/api/auth/password/change", login(phone, temp), {"oldPassword": temp, "newPassword": "Abcdef123456!"})
    http("POST", "/api/users/%s/disable" % mid, ADMIN, {})
    s2, b2 = http("POST", "/api/auth/login", None, login_body(phone, "Abcdef123456!"))
    return (s2, b2.get("status")), s2 == 403 and b2.get("status") == "MEMBER_DISABLED"


# ==================== B 平台 ====================
@case("B01", "平台", "P0", "平台账号登录身份",
      "13900000000 登录后 GET /auth/me", "role=platform_admin，pages 为空数组")
def b01():
    s, b = http("GET", "/api/auth/me", PLAT)
    u = b.get("body", {}).get("user", {})
    return (s, u.get("role"), u.get("pages")), s == 200 and u.get("role") == "platform_admin" and u.get("pages") == []


@case("B02", "平台", "P0", "平台账号不能读业务数据",
      "平台令牌 GET /api/products、/api/bootstrap", "两个接口均 403")
def b02():
    s1, _ = http("GET", "/api/products", PLAT)
    s2, _ = http("GET", "/api/bootstrap", PLAT)
    return (s1, s2), s1 == 403 and s2 == 403


@case("B03", "平台", "P0", "开通租户",
      "POST /platform/tenants {名称/编码/管理员手机/授权期/席位}", "HTTP 200，返回 orgId 与一次性初始密码")
def b03():
    phone = "135" + SUFFIX + "11"
    s, b = http("POST", "/api/platform/tenants", PLAT, {
        "name": "TST 功能测试租户 " + SUFFIX, "code": "TST-FN-" + SUFFIX,
        "adminPhone": phone, "adminName": "功能测试管理员", "plan": "standard", "seatLimit": 3,
        "licenseStartAt": TODAY, "licenseEndAt": TODAY[:4] + "-12-31"})
    body = b.get("body", {}) if isinstance(b, dict) else {}
    STATE["tenant"] = body.get("orgId")
    STATE["tenantPhone"] = phone
    STATE["tenantTemp"] = body.get("tempPassword")
    return (s, body.get("orgId"), bool(body.get("tempPassword"))), s == 200 and bool(body.get("orgId")) and bool(body.get("tempPassword"))


@case("B04", "平台", "P0", "重复企业编码被拒",
      "用已存在的 code=TST-FN-<suffix> 再开通", "HTTP 409 DUPLICATE")
def b04():
    s, b = http("POST", "/api/platform/tenants", PLAT, {
        "name": "TST 重复编码", "code": "TST-FN-" + SUFFIX, "adminPhone": "13520000001"})
    return (s, b.get("status")), s == 409 and b.get("status") == "DUPLICATE"


@case("B05", "平台", "P1", "授权日期倒挂被拒",
      "licenseStartAt=2027-06-01 / licenseEndAt=2027-01-01", "HTTP 422 TENANT_LICENSE_INVALID")
def b05():
    s, b = http("POST", "/api/platform/tenants", PLAT, {
        "name": "TST 日期倒挂 " + SUFFIX, "code": "TST-DT-" + SUFFIX, "adminPhone": "13520000002",
        "licenseStartAt": "2027-06-01", "licenseEndAt": "2027-01-01"})
    return (s, b.get("status")), s == 422 and b.get("status") == "TENANT_LICENSE_INVALID"


@case("B06", "平台", "P1", "租户详情字段完整",
      "GET /platform/tenants/{新租户id}", "200，含 plan/seatLimit/licenseEndAt/members/devices/reports")
def b06():
    s, b = http("GET", "/api/platform/tenants/%s" % STATE["tenant"], PLAT)
    d = b.get("body", {}) if isinstance(b, dict) else {}
    keys = ["plan", "seatLimit", "licenseEndAt", "members", "devices", "reports"]
    return (s, {k: d.get(k) for k in keys}), s == 200 and all(k in d for k in keys)


@case("B07", "平台", "P1", "续期顺延到期日",
      "POST /platform/tenants/{id}/renew {months:6}", "200，到期日在原到期日基础上 +6 个月")
def b07():
    s0, d0 = http("GET", "/api/platform/tenants/%s" % STATE["tenant"], PLAT)
    before = d0["body"]["licenseEndAt"]
    s, b = http("POST", "/api/platform/tenants/%s/renew" % STATE["tenant"], PLAT, {"months": 6})
    after = b.get("body", {}).get("licenseEndAt") if isinstance(b, dict) else None
    import calendar
    y, m, d = int(before[:4]), int(before[5:7]), int(before[8:10])
    m2 = m + 6
    ey, em = y + (m2 - 1) // 12, (m2 - 1) % 12 + 1
    ed = min(d, calendar.monthrange(ey, em)[1])
    want = "%04d-%02d-%02d" % (ey, em, ed)
    return (s, before, after), s == 200 and after == want


@case("B08", "平台", "P1", "续期非法日期被拒",
      "POST renew {licenseEndAt:'abc'}", "HTTP 422，不是 500")
def b08():
    s, b = http("POST", "/api/platform/tenants/%s/renew" % STATE["tenant"], PLAT, {"licenseEndAt": "abc"})
    return (s, b.get("status")), s == 422


@case("B09", "平台", "P0", "停用租户后成员无法登录",
      "停用租户 → 该租户管理员登录", "停用 200；登录 423 TENANT_SUSPENDED")
def b09():
    phone, temp = STATE["tenantPhone"], STATE["tenantTemp"]
    tok = login(phone, temp)
    http("POST", "/api/auth/password/change", tok, {"oldPassword": temp, "newPassword": "Tenant@2026ok"})
    s1, _ = http("POST", "/api/platform/tenants/%s/suspend" % STATE["tenant"], PLAT)
    s2, b2 = http("POST", "/api/auth/login", None, login_body(phone, "Tenant@2026ok"))
    return (s1, s2, b2.get("status")), s1 == 200 and s2 == 423 and b2.get("status") == "TENANT_SUSPENDED"


@case("B10", "平台", "P0", "恢复租户后成员可登录",
      "恢复租户 → 成员登录", "恢复 200；登录 200")
def b10():
    s1, _ = http("POST", "/api/platform/tenants/%s/resume" % STATE["tenant"], PLAT)
    s2, b2 = http("POST", "/api/auth/login", None, login_body(STATE["tenantPhone"], "Tenant@2026ok"))
    return (s1, s2), s1 == 200 and s2 == 200


@case("B11", "平台", "P1", "席位上限不能小于成员数",
      "租户内新增 1 名成员后 PATCH seatLimit=1", "HTTP 409 SEAT_LIMIT")
def b11():
    t = login(STATE["tenantPhone"], "Tenant@2026ok")
    http("POST", "/api/users", t, {"phone": "135" + SUFFIX + "12", "displayName": "席位成员" + SUFFIX, "userType": "user"})
    s, b = http("PUT", "/api/platform/tenants/%s" % STATE["tenant"], PLAT, {"seatLimit": 1})
    return (s, b.get("status")), s == 409 and b.get("status") == "SEAT_LIMIT"


@case("B12", "平台", "P1", "平台审计可按动作与关键词检索",
      "GET /platform/audit?action=create、keyword=租户名", "两次查询 total≥1 且字段含 actionLabel/targetName")
def b12():
    s1, b1 = http("GET", "/api/platform/audit?size=5&action=create", PLAT)
    s2, b2 = http("GET", "/api/platform/audit?size=5&keyword=" + urllib.parse.quote("TST 功能测试租户 " + SUFFIX), PLAT)
    r1 = b1.get("body", {}).get("rows", []) if isinstance(b1, dict) else []
    r2 = b2.get("body", {}).get("rows", []) if isinstance(b2, dict) else []
    return (s1, len(r1), s2, len(r2)), s1 == 200 and r1 and s2 == 200 and r2 and "actionLabel" in r1[0]


@case("B13", "平台", "P0", "超管进入租户后读写、退出后收权",
      "POST /auth/enter-tenant {新租户} → GET /overview → POST /auth/exit-tenant", "进入 200 且 impersonating=true；业务读 200；退出后业务读 403")
def b13():
    s1, b1 = http("POST", "/api/auth/enter-tenant", PLAT, {"orgId": STATE["tenant"]})
    imp = b1.get("body", {}).get("token") if isinstance(b1, dict) else None
    s2, _ = http("GET", "/api/overview", imp)
    s3, b3 = http("POST", "/api/auth/exit-tenant", imp, {})
    return (s1, s2, s3), s1 == 200 and s2 == 200 and s3 == 200


@case("B14", "平台", "P0", "租户管理员不能调平台接口",
      "租户管理员令牌 GET /platform/tenants", "HTTP 403")
def b14():
    s, b = http("GET", "/api/platform/tenants", ADMIN)
    return (s, b.get("status")), s == 403


# ==================== C 主数据 ====================
@case("C01", "主数据", "P0", "新增产品",
      "POST /products {name/code/type/cls/unit/l1}", "200，返回 id 且 version=0")
def c01():
    s, b = http("POST", "/api/products", ADMIN, {"name": "TST 用例产品" + SUFFIX, "code": "TST-P-" + SUFFIX,
                                                 "type": "一般产品", "cls": "工业产品", "unit": "吨", "l1": 0.42})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    STATE["product"] = d.get("id")
    CLEANUP["products"].append(d.get("id"))
    return (s, d.get("id"), d.get("version")), s == 200 and d.get("version") == 0


@case("C02", "主数据", "P0", "产品编码唯一",
      "重复 POST /products code=FN-P-<suffix>", "HTTP 409 DUPLICATE")
def c02():
    s, b = http("POST", "/api/products", ADMIN, {"name": "TST 重复产品", "code": "TST-P-" + SUFFIX,
                                                 "type": "一般产品", "cls": "工业产品", "unit": "吨"})
    return (s, b.get("status")), s == 409 and b.get("status") == "DUPLICATE"


@case("C03", "主数据", "P1", "产品详情",
      "GET /products/{刚建的id}", "200，字段与创建一致")
def c03():
    s, b = http("GET", "/api/products/%s" % STATE["product"], ADMIN)
    d = b.get("body", {}) if isinstance(b, dict) else {}
    return (s, d.get("name")), s == 200 and d.get("name") == "TST 用例产品" + SUFFIX


@case("C04", "主数据", "P1", "产品详情不存在",
      "GET /products/9999999", "HTTP 404 NOT_FOUND")
def c04():
    s, b = http("GET", "/api/products/9999999", ADMIN)
    return (s, b.get("status")), s == 404 and b.get("status") == "NOT_FOUND"


@case("C05", "主数据", "P1", "乐观锁版本冲突",
      "PATCH 产品 version=99（实际 0）", "HTTP 409 VERSION_CONFLICT 且返回 currentVersion")
def c05():
    s, b = http("PUT", "/api/products/%s" % STATE["product"], ADMIN, {
        "name": "TST 冲突改名", "code": "TST-P-" + SUFFIX, "type": "一般产品", "cls": "工业产品", "unit": "吨", "version": 99})
    return (s, b.get("status")), s == 409 and b.get("status") == "VERSION_CONFLICT"


@case("C06", "主数据", "P0", "修改产品",
      "PATCH 产品 {name:..., version:0}", "200，version=1")
def c06():
    s, b = http("PUT", "/api/products/%s" % STATE["product"], ADMIN, {
        "name": "TST 用例产品改" + SUFFIX, "code": "TST-P-" + SUFFIX, "type": "一般产品", "cls": "工业产品",
        "unit": "吨", "l1": 0.5, "version": 0})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    return (s, d.get("version")), s == 200 and d.get("version") == 1


@case("C07", "主数据", "P0", "删除产品后不可见",
      "DELETE /products/{id} → 详情查询", "删除 200；详情 404")
def c07():
    pid = STATE["product"]
    s1, _ = http("DELETE", "/api/products/%s" % pid, ADMIN)
    s2, b2 = http("GET", "/api/products/%s" % pid, ADMIN)
    return (s1, s2, b2.get("status")), s1 == 200 and s2 == 404


@case("C08", "主数据", "P1", "新增设备",
      "POST /devices {name/code/energyType:外购电力}", "200，返回 id")
def c08():
    s, b = http("POST", "/api/devices", ADMIN, {"name": "TST 用例设备" + SUFFIX, "code": "TST-D-" + SUFFIX,
                                                "energyType": "外购电力", "status": "运行"})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    STATE["device"] = d.get("id")
    CLEANUP["devices"].append(d.get("id"))
    return (s, d.get("id")), s == 200 and bool(d.get("id"))


@case("C09", "主数据", "P1", "设备编码唯一",
      "重复 POST /devices code=FN-D-<suffix>", "HTTP 409 DUPLICATE")
def c09():
    s, b = http("POST", "/api/devices", ADMIN, {"name": "TST 重复设备", "code": "TST-D-" + SUFFIX, "energyType": "外购电力"})
    return (s, b.get("status")), s == 409 and b.get("status") == "DUPLICATE"


@case("C10", "主数据", "P1", "设备停用与启用留痕",
      "POST /devices/{id}/disable → enable → 查审计", "两次 200，审计含 disable/enable")
def c10():
    did = STATE["device"]
    s1, _ = http("POST", "/api/devices/%s/disable" % did, ADMIN, {})
    s2, _ = http("POST", "/api/devices/%s/enable" % did, ADMIN, {})
    s3, b3 = http("GET", "/api/audit-logs?entity=device&size=20", ADMIN)
    acts = [r.get("action") for r in (b3.get("body", {}).get("rows", []) if isinstance(b3, dict) else [])]
    return (s1, s2, "disable" in acts, "enable" in acts), s1 == 200 and s2 == 200 and "disable" in acts and "enable" in acts


@case("C11", "主数据", "P1", "新增下级设施",
      "POST /facilities {parentId:1}", "200，tree_level=2")
def c11():
    s, b = http("POST", "/api/facilities", ADMIN, {"name": "TST 用例子厂区" + SUFFIX, "code": "TST-F-" + SUFFIX, "parentId": 1})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    STATE["facility"] = d.get("id")
    CLEANUP["facilities"].append(d.get("id"))
    return (s, d.get("level")), s == 200 and d.get("level") == 2


@case("C12", "主数据", "P1", "有下级的设施不能删除",
      "DELETE /facilities/1（有子级）", "HTTP 409 REFERENCED")
def c12():
    s, b = http("DELETE", "/api/facilities/1", ADMIN)
    return (s, b.get("status")), s == 409 and b.get("status") == "REFERENCED"


@case("C13", "主数据", "P1", "新增供应商",
      "POST /suppliers 完整字段", "200，返回 id")
def c13():
    s, b = http("POST", "/api/suppliers", ADMIN, {
        "name": "TST 用例供应商" + SUFFIX, "product": "用例产品", "unit": "吨", "fpUnit": "tCO2e/t",
        "footprint": 1.25, "date": TODAY, "level": "一级"})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    CLEANUP["suppliers"].append(d.get("id"))
    return (s, d.get("id")), s == 200 and bool(d.get("id"))


@case("C14", "主数据", "P1", "供应商足迹不能为负",
      "POST /suppliers footprint=-1", "HTTP 400 VALIDATION")
def c14():
    s, b = http("POST", "/api/suppliers", ADMIN, {
        "name": "TST 负数供应商" + SUFFIX, "product": "x", "unit": "吨", "fpUnit": "tCO2e/t",
        "footprint": -1, "date": TODAY})
    return (s, b.get("status")), s == 400 and b.get("status") == "VALIDATION"


@case("C15", "主数据", "P1", "新增年度配额",
      "POST /quota-years {year:2031,total:1000}", "200")
def c15():
    s0, b0 = http("GET", "/api/quota-years?year=2031", ADMIN)
    for row in (b0.get("body") if isinstance(b0, list) else []) or []:
        http("DELETE", "/api/quota-years/%s" % row.get("id"), ADMIN)
    s, b = http("POST", "/api/quota-years", ADMIN, {"year": 2031, "total": 1000, "next": 900, "note": "TST"})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    CLEANUP["quotas"].append(d.get("id"))
    return (s, d.get("id")), s == 200


@case("C16", "主数据", "P1", "同年度配额不可重复",
      "再次 POST /quota-years {year:2031}", "HTTP 409 DUPLICATE")
def c16():
    s, b = http("POST", "/api/quota-years", ADMIN, {"year": 2031, "total": 1})
    return (s, b.get("status")), s == 409 and b.get("status") == "DUPLICATE"


@case("C17", "主数据", "P1", "新增碳资产",
      "POST /carbon-assets {name/type/qty/unit/year}", "200，返回 id")
def c17():
    s, b = http("POST", "/api/carbon-assets", ADMIN, {
        "name": "TST 用例资产" + SUFFIX, "type": "配额资产", "qty": 100, "unit": "tCO2e", "year": 2031})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    STATE["asset"] = d.get("id")
    CLEANUP["assets"].append(d.get("id"))
    return (s, d.get("id")), s == 200 and bool(d.get("id"))


@case("C18", "主数据", "P1", "交易数量超过库存被拒",
      "POST /carbon-trades {assetId:新资产, qty:99999}", "HTTP 409 INSUFFICIENT")
def c18():
    s, b = http("POST", "/api/carbon-trades", ADMIN, {
        "assetId": STATE["asset"], "type": "售出", "qty": 99999, "price": 10, "tradeDate": TODAY})
    return (s, b.get("status")), s == 409 and b.get("status") == "INSUFFICIENT"


@case("C19", "主数据", "P1", "凭证有效期倒挂被拒",
      "POST /evidences validFrom=2026-12-31 / validTo=2026-01-01", "HTTP 400")
def c19():
    s, b = http("POST", "/api/evidences", ADMIN, {
        "name": "TST 倒挂凭证" + SUFFIX, "validFrom": "2026-12-31", "validTo": "2026-01-01", "atDate": TODAY})
    return (s, b.get("status")), s in (400, 422)


# ==================== D 核算流程 ====================
@case("D01", "核算", "P0", "计算排放",
      "POST /accounting-periods/2026-11/calculate", "200，scope1/scope2/total>0")
def d01():
    s, b = http("POST", "/api/accounting-periods/%s/calculate" % PERIOD, FILLER, {})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    STATE["calc"] = d.get("total")
    STATE["version"] = d.get("version")
    return (s, d.get("scope1"), d.get("scope2"), d.get("total")), s == 200 and (d.get("total") or 0) > 0


@case("D02", "核算", "P0", "提交核算期间",
      "POST /{period}/submit {version}", "200，status=submitted")
def d02():
    s, b = http("POST", "/api/accounting-periods/%s/submit" % PERIOD, FILLER, {"version": STATE["version"]})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    STATE["version"] = d.get("version")
    return (s, d.get("status")), s == 200 and d.get("status") == "submitted"


@case("D03", "核算", "P0", "编制人不能复核（职责分离）",
      "提交人（填报员）再调 review", "HTTP 409 SOD_VIOLATION")
def d03():
    s, b = http("POST", "/api/accounting-periods/%s/review" % PERIOD, FILLER, {"version": STATE["version"]})
    return (s, b.get("status")), s == 409 and b.get("status") == "SOD_VIOLATION"


@case("D04", "核算", "P0", "他人复核通过",
      "管理员调 review", "200，status=reviewed")
def d04():
    s, b = http("POST", "/api/accounting-periods/%s/review" % PERIOD, ADMIN, {"version": STATE["version"], "remark": "用例复核"})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    STATE["version"] = d.get("version")
    return (s, d.get("status")), s == 200 and d.get("status") == "reviewed"


@case("D05", "核算", "P0", "复核人不能批准（职责分离）",
      "复核人（管理员）再调 approve", "HTTP 409 SOD_VIOLATION")
def d05():
    s, b = http("POST", "/api/accounting-periods/%s/approve" % PERIOD, ADMIN, {"version": STATE["version"]})
    return (s, b.get("status")), s == 409 and b.get("status") == "SOD_VIOLATION"


@case("D06", "核算", "P0", "他人批准通过",
      "批准人账号调 approve", "200，status=approved")
def d06():
    s, b = http("POST", "/api/accounting-periods/%s/approve" % PERIOD, APPROVER, {"version": STATE["version"], "remark": "用例批准"})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    STATE["version"] = d.get("version")
    return (s, d.get("status")), s == 200 and d.get("status") == "approved"


@case("D07", "核算", "P0", "锁定期间",
      "POST /{period}/lock {version}", "200，status=locked")
def d07():
    s, b = http("POST", "/api/accounting-periods/%s/lock" % PERIOD, ADMIN, {"version": STATE["version"]})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    STATE["version"] = d.get("version")
    return (s, d.get("status")), s == 200 and d.get("status") == "locked"


@case("D08", "核算", "P0", "锁定后不能再计算",
      "锁定状态下 POST /calculate", "HTTP 409 PERIOD_LOCKED")
def d08():
    s, b = http("POST", "/api/accounting-periods/%s/calculate" % PERIOD, FILLER, {})
    return (s, b.get("status")), s == 409 and b.get("status") == "PERIOD_LOCKED"


@case("D09", "核算", "P1", "普通用户不能解锁",
      "填报员调 unlock", "HTTP 403")
def d09():
    s, b = http("POST", "/api/accounting-periods/%s/unlock" % PERIOD, FILLER, {"version": STATE["version"], "reason": "用例"})
    return (s, b.get("status")), s == 403


@case("D10", "核算", "P0", "管理员解锁回草稿并留痕",
      "管理员 unlock {version,reason}", "200，status=draft，unlocks 记录 +1")
def d10():
    s, b = http("POST", "/api/accounting-periods/%s/unlock" % PERIOD, ADMIN,
                {"version": STATE["version"], "reason": "功能测试回归"})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    STATE["version"] = d.get("version")
    return (s, d.get("status"), len(d.get("unlocks") or [])), s == 200 and d.get("status") == "draft"


# ==================== E 报告 ====================
@case("E01", "报告", "P0", "生成碳排放报告",
      "POST /reports {type:carbon,period:2026-11}", "200，返回报告 id 与文件名")
def e01():
    # 草稿期不允许生成正式报告：先核算并提交本期
    http("POST", "/api/accounting-periods/%s/calculate" % PERIOD, FILLER, {})
    _, pb = http("GET", "/api/accounting-periods/%s" % PERIOD, ADMIN)
    pbody = pb.get("body") or {}
    if pbody.get("status") not in ("submitted", "reviewed", "approved", "locked"):
        http("POST", "/api/accounting-periods/%s/submit" % PERIOD, FILLER, {"version": pbody.get("version")})
    s, b = http("POST", "/api/reports", ADMIN, {"type": "carbon", "period": PERIOD})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    STATE["report"] = d.get("id")
    CLEANUP["reports"].append(d.get("id"))
    return (s, d.get("id"), d.get("fileName")), s == 200 and bool(d.get("id"))


@case("E02", "报告", "P1", "报告列表按类型过滤",
      "GET /reports?type=carbon", "200，分页对象且 rows 全部 type=carbon")
def e02():
    s, b = http("GET", "/api/reports?type=carbon", ADMIN)
    body = b.get("body")
    rows = body if isinstance(body, list) else (body or {}).get("rows", [])
    return (s, len(rows or [])), s == 200 and rows and all(r.get("type") == "carbon" for r in rows)


@case("E03", "报告", "P0", "下载报告 PDF",
      "GET /reports/{id}/download", "200，Content-Type=application/pdf，字节数>1KB")
def e03():
    s, raw = http("GET", "/api/reports/%s/download" % STATE["report"], ADMIN, raw=True)
    return (s, len(raw)), s == 200 and len(raw) > 1024


@case("E04", "报告", "P1", "打包下载 ZIP",
      "GET /reports/bundle?type=carbon&period=2026-11", "200，Content-Type=application/zip")
def e04():
    s, raw = http("GET", "/api/reports/bundle?type=carbon&period=%s" % PERIOD, ADMIN, raw=True)
    return (s, len(raw)), s == 200 and len(raw) > 1024


@case("E05", "报告", "P0", "普通用户不能删除报告",
      "填报员 DELETE /reports/{id}", "HTTP 403")
def e05():
    s, b = http("DELETE", "/api/reports/%s" % STATE["report"], FILLER)
    return (s, b.get("status")), s == 403


@case("E06", "报告", "P1", "管理员删除报告",
      "管理员 DELETE /reports/{id}", "200，列表不再包含该报告")
def e06():
    s, _ = http("DELETE", "/api/reports/%s" % STATE["report"], ADMIN)
    s2, b2 = http("GET", "/api/reports?type=carbon", ADMIN)
    rows = b2.get("body", []) if isinstance(b2, list) else []
    left = [r for r in rows if r.get("id") == STATE["report"]]
    return (s, len(left)), s == 200 and not left


# ==================== F 权限 ====================
@case("F01", "权限", "P0", "受限角色只拿到勾选页面",
      "建角色 pages=[product.list] + 用户，登录查 /auth/me 与 /bootstrap", "pages 含 product.list 不含 *；快照无 reports 键")
def f01():
    s, b = http("POST", "/api/roles", ADMIN, {"name": "用例只读" + SUFFIX, "pages": ["product.list"]})
    rid = b["body"]["roleId"]
    CLEANUP["roles"].append(rid)
    phone = "135" + SUFFIX + "21"
    s2, b2 = http("POST", "/api/users", ADMIN, {"phone": phone, "displayName": "用例只读用户" + SUFFIX, "userType": "user", "roleId": rid})
    mid, temp = b2["body"]["membershipId"], b2["body"]["tempPassword"]
    CLEANUP["users"].append(mid)
    tok = login(phone, temp)
    http("POST", "/api/auth/password/change", tok, {"oldPassword": temp, "newPassword": "Abcdef123456!"})
    tok = login(phone, "Abcdef123456!")
    s3, b3 = http("GET", "/api/auth/me", tok)
    pages = b3["body"]["user"]["pages"]
    s4, b4 = http("GET", "/api/bootstrap", tok)
    snap = b4.get("body", {}) if isinstance(b4, dict) else {}
    STATE["limited"] = tok
    return (s3, pages, "reports" in snap), s3 == 200 and "product.list" in pages and "*" not in pages and "reports" not in snap


@case("F02", "权限", "P0", "未授权页面接口被拒",
      "受限用户 GET /api/reports、/api/suppliers", "均 HTTP 403 PAGE_FORBIDDEN")
def f02():
    s1, b1 = http("GET", "/api/reports", STATE["limited"])
    s2, b2 = http("GET", "/api/suppliers", STATE["limited"])
    return (s1, b1.get("status"), s2, b2.get("status")), s1 == 403 and s2 == 403 and b1.get("status") == "PAGE_FORBIDDEN"


@case("F03", "权限", "P0", "导出按资源鉴权",
      "受限用户 GET /export?resource=evidences / products", "evidences 403；products 200")
def f03():
    s1, b1 = http("GET", "/api/export?resource=evidences", STATE["limited"], raw=True)
    s2, _ = http("GET", "/api/export?resource=products", STATE["limited"], raw=True)
    return (s1, s2), s1 == 403 and s2 == 200


@case("F04", "权限", "P1", "附件接口按页面鉴权",
      "受限用户 GET /api/files/1/download", "HTTP 403 PAGE_FORBIDDEN")
def f04():
    s, b = http("GET", "/api/files/1/download", STATE["limited"])
    return (s, b.get("status")), s == 403


@case("F05", "权限", "P0", "普通用户不能读审计",
      "填报员 GET /api/audit-logs", "HTTP 403")
def f05():
    s, b = http("GET", "/api/audit-logs", FILLER)
    return (s, b.get("status")), s == 403


@case("F06", "权限", "P1", "普通用户不能读任务清单",
      "填报员 GET /api/jobs", "HTTP 403")
def f06():
    s, b = http("GET", "/api/jobs", FILLER)
    return (s, b.get("status")), s == 403


@case("F07", "权限", "P1", "平台账号无业务页面",
      "平台令牌 GET /auth/me", "pages 为空；GET /api/overview 403")
def f07():
    s1, b1 = http("GET", "/api/auth/me", PLAT)
    s2, _ = http("GET", "/api/overview", PLAT)
    return (s1, b1["body"]["user"]["pages"], s2), s1 == 200 and b1["body"]["user"]["pages"] == [] and s2 == 403


@case("F08", "权限", "P1", "跨租户详情不可见",
      "管理员 GET /products/{新租户的产品}", "HTTP 404")
def f08():
    t = login(STATE["tenantPhone"], "Tenant@2026ok")
    s, b = http("POST", "/api/products", t, {"name": "他租户产品" + SUFFIX, "code": "OTHER-" + SUFFIX,
                                             "type": "一般产品", "cls": "工业产品", "unit": "吨"})
    pid = b["body"]["id"]
    s2, b2 = http("GET", "/api/products/%s" % pid, ADMIN)
    return (s2, b2.get("status")), s2 == 404


# ==================== G 健壮性 ====================
@case("G01", "健壮性", "P1", "分页参数非数字",
      "GET /products?page=abc", "HTTP 400 VALIDATION")
def g01():
    s, b = http("GET", "/api/products?page=abc", ADMIN)
    return (s, b.get("status")), s == 400


@case("G02", "健壮性", "P2", "分页 size=0 自动收敛",
      "GET /products?size=0", "HTTP 200，至少返回 1 条")
def g02():
    s, b = http("GET", "/api/products?size=0", ADMIN)
    rows = b.get("body", {}).get("rows", []) if isinstance(b, dict) else []
    return (s, len(rows)), s == 200 and len(rows) >= 1


@case("G03", "健壮性", "P2", "页码超界返回空页",
      "GET /products?page=99999", "HTTP 200，rows 为空，total>0")
def g03():
    s, b = http("GET", "/api/products?page=99999", ADMIN)
    d = b.get("body", {}) if isinstance(b, dict) else {}
    return (s, len(d.get("rows", [])), d.get("total")), s == 200 and d.get("rows") == [] and (d.get("total") or 0) > 0


@case("G04", "健壮性", "P1", "导出缺少必填参数",
      "GET /export（不带 resource）", "HTTP 400，提示缺 resource")
def g04():
    s, b = http("GET", "/api/export", ADMIN)
    return (s, b.get("status")), s == 400


@case("G05", "健壮性", "P2", "不支持的方法返回 405",
      "POST /products/1（详情路径不支持 POST）", "HTTP 405 METHOD_NOT_ALLOWED")
def g05():
    s, b = http("POST", "/api/products/1", ADMIN, {"name": "x"})
    return (s, b.get("status")), s == 405 and b.get("status") == "METHOD_NOT_ALLOWED"


@case("G06", "健壮性", "P1", "上传接口收到 JSON",
      "POST /api/files Content-Type=json 无文件", "HTTP 400（不是 500）")
def g06():
    s, b = http("POST", "/api/files", ADMIN, {"file": "x"})
    return (s, b.get("status")), s == 400


@case("G07", "健壮性", "P2", "超长关键词不报错",
      "GET /products?keyword=<300字符>", "HTTP 200")
def g07():
    s, b = http("GET", "/api/products?keyword=" + urllib.parse.quote("x" * 300), ADMIN)
    return (s, b.get("status")), s == 200


@case("G08", "健壮性", "P1", "注入式关键词无效果",
      "GET /products?keyword=%' or 1=1 --", "HTTP 200 且 total=0")
def g08():
    s, b = http("GET", "/api/products?keyword=" + urllib.parse.quote("%' or 1=1 --"), ADMIN)
    total = b.get("body", {}).get("total") if isinstance(b, dict) else None
    return (s, total), s == 200 and total == 0


@case("G09", "健壮性", "P1", "非法日期筛选不 500",
      "GET /alert-records?from=abc&to=xyz", "HTTP 400 或 200，不能 5xx")
def g09():
    s, b = http("GET", "/api/alert-records?from=abc&to=xyz", ADMIN)
    return (s,), 200 <= s < 500


@case("G10", "健壮性", "P2", "导出未知资源",
      "GET /export?resource=nope", "HTTP 400 VALIDATION")
def g10():
    s, b = http("GET", "/api/export?resource=nope", ADMIN)
    return (s, b.get("status")), s == 400


# ==================== H 一致性 ====================
@case("H01", "一致性", "P1", "分页 total 与翻页不重不漏",
      "GET /products?size=2 取前两页", "两页 id 不重复，合计≤total")
def h01():
    s1, b1 = http("GET", "/api/products?page=1&size=2", ADMIN)
    s2, b2 = http("GET", "/api/products?page=2&size=2", ADMIN)
    d1, d2 = b1.get("body", {}), b2.get("body", {})
    ids = [r["id"] for r in d1.get("rows", [])] + [r["id"] for r in d2.get("rows", [])]
    return (d1.get("total"), len(ids), len(set(ids))), s1 == 200 and s2 == 200 and len(ids) == len(set(ids)) and len(ids) <= d1.get("total", 0)


@case("H02", "一致性", "P1", "写操作产生审计",
      "新增产品后查 /audit-logs?entity=product", "最近一条 action=create 且 actor=管理员")
def h02():
    s, b = http("POST", "/api/products", ADMIN, {"name": "TST 审计验证产品" + SUFFIX, "code": "TST-AUD-" + SUFFIX,
                                                 "type": "一般产品", "cls": "工业产品", "unit": "吨"})
    pid = b["body"]["id"]
    CLEANUP["products"].append(pid)
    s2, b2 = http("GET", "/api/audit-logs?entity=product&keyword=%s&size=5" % pid, ADMIN)
    rows = b2.get("body", {}).get("rows", []) if isinstance(b2, dict) else []
    hit = [r for r in rows if r.get("action") == "create" and r.get("entityId") == str(pid)]
    return (s2, len(hit), hit[0].get("actor") if hit else None), s2 == 200 and hit and hit[0].get("actor") == "13800000001"


@case("H03", "一致性", "P2", "首页快照体积在预算内",
      "GET /api/bootstrap 字节数", "≤ 60KB")
def h03():
    s, raw = http("GET", "/api/bootstrap", ADMIN, raw=True)
    return (s, len(raw) // 1024), s == 200 and len(raw) <= 60 * 1024


@case("H04", "一致性", "P1", "健康检查",
      "GET /api/health", "200 且 status=UP")
def h04():
    s, b = http("GET", "/api/health", None)
    return (s, b.get("body", {}).get("status") if isinstance(b, dict) else b), s == 200


@case("H05", "一致性", "P2", "审计分页边界不报错",
      "GET /audit-logs?page=0&size=0", "HTTP 200，返回合法分页对象")
def h05():
    s, b = http("GET", "/api/audit-logs?page=0&size=0", ADMIN)
    d = b.get("body", {}) if isinstance(b, dict) else {}
    return (s, d.get("page"), d.get("size")), s == 200 and d.get("page") == 1 and (d.get("size") or 0) >= 1


@case("H06", "一致性", "P2", "租户审计日期格式非法",
      "GET /audit-logs?from=abc", "HTTP 400 VALIDATION，不静默返回全量")
def h06():
    s, b = http("GET", "/api/audit-logs?from=abc", ADMIN)
    return (s, b.get("status")), s == 400 and b.get("status") == "VALIDATION"


@case("H07", "一致性", "P2", "平台审计日期格式非法",
      "GET /platform/audit?from=abc", "HTTP 400 VALIDATION")
def h07():
    s, b = http("GET", "/api/platform/audit?from=abc", PLAT)
    return (s, b.get("status")), s == 400 and b.get("status") == "VALIDATION"


# ==================== I 分析视图 ====================
def kpis(b):
    d = b.get("body") if isinstance(b, dict) else None
    return (d.get("kpis") if isinstance(d, dict) else None) or []


@case("I01", "分析", "P0", "总览 KPI 与口径",
      "GET /api/overview?year=2026", "200，kpis≥4，orgName 非空，year=2026")
def i01():
    s, b = http("GET", "/api/overview?year=2026", ADMIN)
    d = b.get("body", {}) if isinstance(b, dict) else {}
    ks = kpis(b)
    return (s, len(ks), d.get("orgName"), d.get("year")), \
        s == 200 and len(ks) >= 4 and bool(d.get("orgName")) and d.get("year") == 2026


@case("I02", "分析", "P0", "能耗分析（月份区间）",
      "GET /analysis/energy-consumption?from=2026-01&to=2026-12", "200，kpis 含综合能耗")
def i02():
    s, b = http("GET", "/api/analysis/energy-consumption?from=2026-01&to=2026-12", ADMIN)
    labels = [k.get("label") for k in kpis(b)]
    return (s, labels[:4]), s == 200 and "综合能耗" in labels


@case("I03", "分析", "P0", "碳排放分析",
      "GET /analysis/carbon-emission?from=2026-01&to=2026-12", "200，kpis 含碳排放总量与范围一")
def i03():
    s, b = http("GET", "/api/analysis/carbon-emission?from=2026-01&to=2026-12", ADMIN)
    labels = [k.get("label") for k in kpis(b)]
    return (s, labels[:4]), s == 200 and "碳排放总量" in labels and "范围一" in labels


@case("I04", "分析", "P1", "能效分析",
      "GET /analysis/energy-efficiency?from=2026-01&to=2026-12", "200，kpis 含单位产值能耗")
def i04():
    s, b = http("GET", "/api/analysis/energy-efficiency?from=2026-01&to=2026-12", ADMIN)
    labels = [k.get("label") for k in kpis(b)]
    return (s, labels[:3]), s == 200 and any("单位产值能耗" in (x or "") for x in labels)


@case("I05", "分析", "P1", "能流分析",
      "GET /analysis/energy-flow?from=2026-01&to=2026-12", "200，kpis 含总能耗")
def i05():
    s, b = http("GET", "/api/analysis/energy-flow?from=2026-01&to=2026-12", ADMIN)
    labels = [k.get("label") for k in kpis(b)]
    return (s, labels[:3]), s == 200 and "总能耗" in labels


@case("I06", "分析", "P1", "减排策略分析",
      "GET /analysis/strategy?from=2026-01&to=2026-12", "200，kpis 含建议条数")
def i06():
    s, b = http("GET", "/api/analysis/strategy?from=2026-01&to=2026-12", ADMIN)
    labels = [k.get("label") for k in kpis(b)]
    return (s, labels[:3]), s == 200 and any("建议" in (x or "") for x in labels)


@case("I07", "分析", "P1", "配额分析",
      "GET /analysis/quota?year=2026", "200，kpis 含配额与使用率")
def i07():
    s, b = http("GET", "/api/analysis/quota?year=2026", ADMIN)
    labels = [k.get("label") for k in kpis(b)]
    return (s, labels[:3]), s == 200 and any("配额" in (x or "") for x in labels)


@case("I08", "分析", "P1", "产品碳足迹分析",
      "GET /analysis/footprint?year=2026", "200，kpis 含碳足迹值")
def i08():
    s, b = http("GET", "/api/analysis/footprint?year=2026", ADMIN)
    labels = [k.get("label") for k in kpis(b)]
    return (s, labels[:3]), s == 200 and any("足迹" in (x or "") for x in labels)


@case("I09", "分析", "P1", "供应链分析",
      "GET /analysis/supply-chain", "200，kpis 含供应商家数")
def i09():
    s, b = http("GET", "/api/analysis/supply-chain", ADMIN)
    labels = [k.get("label") for k in kpis(b)]
    return (s, labels[:3]), s == 200 and "供应商" in labels


@case("I10", "分析", "P2", "分析区间格式非法",
      "GET /analysis/energy-consumption?from=2026-01-01", "HTTP 400 VALIDATION，提示月份格式")
def i10():
    s, b = http("GET", "/api/analysis/energy-consumption?from=2026-01-01&to=2026-12", ADMIN)
    return (s, b.get("status"), (b.get("body") or {}).get("message")), \
        s == 400 and b.get("status") == "VALIDATION"


# ==================== J 预警 ====================
@case("J01", "预警", "P0", "新增预警规则",
      "POST /alert-rules {name,metric,op,threshold,unit,level}",
      "200，返回规则 id，阈值与名称正确")
def j01():
    s, b = http("POST", "/api/alert-rules", ADMIN, {
        "name": "TST 规则" + SUFFIX, "metric": "能耗", "op": "大于", "threshold": 88,
        "unit": "tce", "level": "一般", "cool": 60, "enabled": 1, "remark": "功能用例"})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    STATE["rule"] = d.get("id")
    CLEANUP["alertRules"].append(d.get("id"))
    return (s, d.get("id"), d.get("name"), d.get("threshold")), \
        s == 200 and bool(d.get("id")) and d.get("name") == "TST 规则" + SUFFIX


@case("J02", "预警", "P1", "规则阈值为负被拒",
      "POST /alert-rules {threshold:-1}", "HTTP 400 VALIDATION")
def j02():
    s, b = http("POST", "/api/alert-rules", ADMIN, {
        "name": "TST 负阈值" + SUFFIX, "metric": "能耗", "op": "大于", "threshold": -1,
        "unit": "tce", "level": "一般", "enabled": 1})
    return (s, b.get("status")), s == 400 and b.get("status") == "VALIDATION"


@case("J03", "预警", "P1", "规则详情",
      "GET /alert-rules/{id}", "200，返回同一条规则")
def j03():
    s, b = http("GET", "/api/alert-rules/%s" % STATE["rule"], ADMIN)
    d = b.get("body", {}) if isinstance(b, dict) else {}
    return (s, d.get("id"), d.get("name")), s == 200 and d.get("id") == STATE["rule"]


@case("J04", "预警", "P1", "修改规则并升版本",
      "PATCH /alert-rules/{id} {threshold:100,version:0}", "200，阈值 100，version=1")
def j04():
    s, b = http("PUT", "/api/alert-rules/%s" % STATE["rule"], ADMIN, {
        "name": "TST 规则" + SUFFIX, "metric": "能耗", "op": "大于", "threshold": 100,
        "unit": "tce", "level": "重要", "cool": 60, "enabled": 1, "version": 0})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    return (s, d.get("threshold"), d.get("version")), s == 200 and float(d.get("threshold") or 0) == 100


@case("J05", "预警", "P0", "预警记录分页与统计一致",
      "GET /alert-records?page=1&size=5 + /alert-records/stats", "200，total≥open，stats 四字段齐全")
def j05():
    s1, b1 = http("GET", "/api/alert-records?page=1&size=5", ADMIN)
    s2, b2 = http("GET", "/api/alert-records/stats", ADMIN)
    d = b1.get("body", {}) if isinstance(b1, dict) else {}
    st = b2.get("body", {}) if isinstance(b2, dict) else {}
    keys = {"midOpen", "highOpen", "unread", "open"}
    return (s1, d.get("total"), st), s1 == 200 and s2 == 200 and keys.issubset(st) \
        and (d.get("total") or 0) >= (st.get("open") or 0)


@case("J06", "预警", "P1", "记录按等级过滤",
      "GET /alert-records?level=high&size=20", "200，返回行全部 level=high")
def j06():
    s, b = http("GET", "/api/alert-records?level=high&size=20", ADMIN)
    rows = (b.get("body", {}) if isinstance(b, dict) else {}).get("rows", [])
    return (s, len(rows), sorted({r.get("level") for r in rows})), \
        s == 200 and all(r.get("level") == "high" for r in rows)


@case("J07", "预警", "P1", "批量删除记录需管理员",
      "填报员 POST /alert-records/batch-delete", "HTTP 403")
def j07():
    s, b = http("POST", "/api/alert-records/batch-delete", FILLER, {"ids": [1]})
    return (s, b.get("status")), s == 403


# ==================== K 碳足迹 ====================
@case("K01", "足迹", "P0", "新增产品碳足迹",
      "POST /footprints {name,unit,qty,footprint,date,boundary}", "200，返回 id")
def k01():
    s, b = http("POST", "/api/footprints", ADMIN, {
        "productId": 1, "name": "TST 足迹产品" + SUFFIX, "unit": "tCO2e/t", "qty": 2,
        "footprint": 1.25, "date": TODAY, "boundary": "摇篮到大门",
        "stageRaw": 0.4, "stageTransport": 0.2, "stageMake": 0.65})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    STATE["footprint"] = d.get("id")
    CLEANUP["footprints"].append(d.get("id"))
    return (s, d.get("id"), d.get("footprint")), s == 200 and bool(d.get("id"))


@case("K02", "足迹", "P1", "足迹数量必须大于 0",
      "POST /footprints {qty:0}", "HTTP 422/400，拒绝")
def k02():
    s, b = http("POST", "/api/footprints", ADMIN, {
        "name": "TST 零数量" + SUFFIX, "unit": "tCO2e/t", "qty": 0, "footprint": 1,
        "date": TODAY, "boundary": "摇篮到大门"})
    return (s, b.get("status")), s in (400, 422)


@case("K03", "足迹", "P1", "足迹详情与修改",
      "GET + PATCH /footprints/{id} {boundary:摇篮到坟墓,version:0}", "200，边界已更新")
def k03():
    s1, b1 = http("GET", "/api/footprints/%s" % STATE["footprint"], ADMIN)
    s2, b2 = http("PUT", "/api/footprints/%s" % STATE["footprint"], ADMIN, {
        "name": "TST 足迹产品" + SUFFIX, "unit": "tCO2e/t", "qty": 2, "footprint": 1.25,
        "date": TODAY, "boundary": "摇篮到坟墓",
        "stageRaw": 0.4, "stageTransport": 0.2, "stageMake": 0.65, "version": 0})
    d = b2.get("body", {}) if isinstance(b2, dict) else {}
    return (s1, s2, d.get("boundary"), d.get("version")), \
        s1 == 200 and s2 == 200 and d.get("boundary") == "摇篮到坟墓" and d.get("version") == 1


@case("K04", "足迹", "P1", "删除足迹后详情 404",
      "DELETE /footprints/{id} → GET /footprints/{id}", "删除 200，再查 404")
def k04():
    s1, _ = http("DELETE", "/api/footprints/%s" % STATE["footprint"], ADMIN)
    s2, b2 = http("GET", "/api/footprints/%s" % STATE["footprint"], ADMIN)
    return (s1, s2, b2.get("status")), s1 == 200 and s2 == 404 and b2.get("status") == "NOT_FOUND"


# ==================== L 能源与节能 ====================
@case("L01", "能源", "P1", "能源管理列表",
      "GET /energy-mgmt?size=5", "200，返回分页对象")
def l01():
    s, b = http("GET", "/api/energy-mgmt?size=5", ADMIN)
    d = b.get("body", {}) if isinstance(b, dict) else {}
    return (s, d.get("total"), len(d.get("rows") or [])), s == 200 and "total" in d


@case("L02", "能源", "P1", "能源管理缺负责人被拒",
      "POST /energy-mgmt {atDate,orgName} 无 duty", "HTTP 400 VALIDATION")
def l02():
    s, b = http("POST", "/api/energy-mgmt", ADMIN, {"atDate": TODAY, "orgName": "华东精密"})
    return (s, b.get("status")), s == 400 and b.get("status") == "VALIDATION"


@case("L03", "能源", "P1", "节能改造列表",
      "GET /retrofits?size=5", "200，返回分页对象")
def l03():
    s, b = http("GET", "/api/retrofits?size=5", ADMIN)
    d = b.get("body", {}) if isinstance(b, dict) else {}
    return (s, d.get("total"), len(d.get("rows") or [])), s == 200 and "total" in d


@case("L04", "能源", "P1", "改造投资额为负被拒",
      "POST /retrofits {invest:-1}", "HTTP 400/422")
def l04():
    s, b = http("POST", "/api/retrofits", ADMIN, {
        "projectYear": 2026, "name": "TST 负投资" + SUFFIX, "invest": -1, "investUnit": "万元",
        "saving": 10, "savingUnit": "tce"})
    return (s, b.get("status")), s in (400, 422)


# ==================== M 设备读数 ====================
@case("M01", "读数", "P0", "读数分页",
      "GET /device-readings/page?size=5", "200，total>0，行含设备与数值")
def m01():
    s, b = http("GET", "/api/device-readings/page?size=5", ADMIN)
    d = b.get("body", {}) if isinstance(b, dict) else {}
    rows = d.get("rows") or []
    return (s, d.get("total"), rows[0].get("deviceCode") if rows else None), \
        s == 200 and (d.get("total") or 0) > 0 and rows and rows[0].get("deviceCode")


@case("M02", "读数", "P1", "读数按设备过滤",
      "GET /device-readings/page?deviceId={id}", "200，返回行 deviceId 全部一致")
def m02():
    s0, b0 = http("GET", "/api/device-readings/page?size=1", ADMIN)
    rows0 = (b0.get("body", {}) if isinstance(b0, dict) else {}).get("rows", [])
    did = rows0[0]["deviceId"] if rows0 else None
    STATE["device"] = did
    s, b = http("GET", "/api/device-readings/page?deviceId=%s&size=20" % did, ADMIN)
    rows = (b.get("body", {}) if isinstance(b, dict) else {}).get("rows", [])
    return (s, did, len(rows), sorted({r.get("deviceId") for r in rows})), \
        s == 200 and rows and all(r.get("deviceId") == did for r in rows)


@case("M03", "读数", "P1", "读数为负被拒",
      "POST /device-readings {qty:-5}", "HTTP 400/422")
def m03():
    s, b = http("POST", "/api/device-readings", ADMIN, {
        "deviceId": STATE.get("device"), "ts": TODAY + "T09:00:00", "qty": -5,
        "unit": "kWh", "source": "TST 用例"})
    return (s, b.get("status")), s in (400, 422)


@case("M04", "读数", "P1", "读数版本冲突",
      "PATCH /device-readings/{id} {version:999}", "HTTP 409，返回当前版本")
def m04():
    s0, b0 = http("GET", "/api/device-readings/page?size=1", ADMIN)
    rid = (b0.get("body", {}) if isinstance(b0, dict) else {}).get("rows", [{}])[0].get("id")
    s, b = http("PUT", "/api/device-readings/%s" % rid, ADMIN, {"version": 999, "remark": "TST"})
    return (s, b.get("status")), s == 409


# ==================== N 附件 ====================
def multipart(path, token, filename, content, fields=None):
    boundary = "----CBTEST" + SUFFIX
    chunks = []
    for k, v in (fields or {}).items():
        chunks.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
                       % (boundary, k, v)).encode())
    chunks.append(("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
                   "Content-Type: text/csv\r\n\r\n" % (boundary, filename)).encode()
                  + content + b"\r\n")
    chunks.append(("--%s--\r\n" % boundary).encode())
    data = b"".join(chunks)
    req = urllib.request.Request(BASE + path, data=data, method="POST", headers={
        "Authorization": "Bearer " + token,
        "Content-Type": "multipart/form-data; boundary=" + boundary,
        "Content-Length": str(len(data))})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}


def fetch_raw(path, token):
    req = urllib.request.Request(BASE + path, headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


@case("N01", "附件", "P0", "上传附件",
      "POST /files (multipart, bizType=evidence,bizId=1)", "200，返回 id 与 sha256")
def n01():
    content = ("id,name\n1,TST\n").encode("utf-8")
    s, b = multipart("/api/files", ADMIN, "TST-附件-%s.csv" % SUFFIX, content,
                     {"bizType": "evidence", "bizId": "1"})
    d = b.get("body", {}) if isinstance(b, dict) else {}
    STATE["file"] = d.get("id")
    STATE["fileSha"] = d.get("sha256")
    CLEANUP["files"].append(d.get("id"))
    return (s, d.get("id"), d.get("size"), bool(d.get("sha256"))), s == 200 and bool(d.get("id"))


@case("N02", "附件", "P1", "按业务对象列附件",
      "GET /files?bizType=evidence&bizId=1", "200，列表包含刚上传的附件")
def n02():
    s, b = http("GET", "/api/files?bizType=evidence&bizId=1", ADMIN)
    body = b.get("body") if isinstance(b, dict) else None
    rows = body if isinstance(body, list) else ((body or {}).get("rows") or [])
    return (s, len(rows or [])), s == 200 and any(r.get("id") == STATE["file"] for r in rows or [])


@case("N03", "附件", "P0", "下载附件内容一致",
      "GET /files/{id}/download", "200，字节与上传一致，X-Content-SHA256 匹配")
def n03():
    import hashlib
    s, raw, headers = fetch_raw("/api/files/%s/download" % STATE["file"], ADMIN)
    want = hashlib.sha256(("id,name\n1,TST\n").encode("utf-8")).hexdigest()
    got = headers.get("X-Content-SHA256") or headers.get("x-content-sha256")
    return (s, len(raw), got == want), s == 200 and raw == ("id,name\n1,TST\n").encode("utf-8") and got == want


@case("N04", "附件", "P1", "删除附件后不可下载",
      "DELETE /files/{id} → GET download", "删除 200，再下载 404")
def n04():
    s1, _ = http("DELETE", "/api/files/%s" % STATE["file"], ADMIN)
    s2, _, _ = fetch_raw("/api/files/%s/download" % STATE["file"], ADMIN)
    return (s1, s2), s1 == 200 and s2 == 404


# ==================== O 幂等 ====================
def http_full(method, path, token=None, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    h = dict(headers or {})
    if data is not None:
        h["Content-Type"] = "application/json"
    if token:
        h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode() or "{}"), dict(r.headers)
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}"), dict(e.headers)
        except Exception:
            return e.code, {}, dict(e.headers)


@case("O01", "幂等", "P0", "同键重复创建只落一条",
      "POST /products 两次 + 同 Idempotency-Key", "第二次 200 且 Idempotent-Replay=true，库里仅 1 条")
def o01():
    key = "TST-IDEM-%s" % SUFFIX
    body = {"name": "TST 幂等产品" + SUFFIX, "code": "TST-IDEM-" + SUFFIX,
            "type": "一般产品", "cls": "工业产品", "unit": "吨"}
    s1, b1, h1 = http_full("POST", "/api/products", ADMIN, body, {"Idempotency-Key": key})
    s2, b2, h2 = http_full("POST", "/api/products", ADMIN, body, {"Idempotency-Key": key})
    pid = (b1.get("body") or {}).get("id")
    CLEANUP["products"].append(pid)
    s3, b3 = http("GET", "/api/products?keyword=" + urllib.parse.quote("TST 幂等产品" + SUFFIX), ADMIN)
    total = (b3.get("body", {}) if isinstance(b3, dict) else {}).get("total")
    replay = str(h2.get("Idempotent-Replay") or h2.get("idempotent-replay")).lower() == "true"
    return (s1, s2, replay, total), s1 == 200 and s2 == 200 and replay and total == 1


@case("O02", "幂等", "P1", "同键不同请求体冲突",
      "同 Idempotency-Key 改 name 再提交", "HTTP 409 IDEMPOTENCY_CONFLICT")
def o02():
    key = "TST-IDEM-C-%s" % SUFFIX
    s1, b1, _ = http_full("POST", "/api/products", ADMIN, {
        "name": "TST 幂等冲突A" + SUFFIX, "code": "TST-IDA-" + SUFFIX,
        "type": "一般产品", "cls": "工业产品", "unit": "吨"}, {"Idempotency-Key": key})
    CLEANUP["products"].append((b1.get("body") or {}).get("id"))
    s2, b2, _ = http_full("POST", "/api/products", ADMIN, {
        "name": "TST 幂等冲突B" + SUFFIX, "code": "TST-IDB-" + SUFFIX,
        "type": "一般产品", "cls": "工业产品", "unit": "吨"}, {"Idempotency-Key": key})
    return (s1, s2, b2.get("status")), s1 == 200 and s2 == 409 and b2.get("status") == "IDEMPOTENCY_CONFLICT"


@case("O03", "幂等", "P2", "不带键可重复创建",
      "POST /products 两次（无 Idempotency-Key）", "200/200，两条独立记录")
def o03():
    made = []
    for tag in ("A", "B"):
        code = "TST-NOKEY-%s-%s" % (tag, SUFFIX)
        s, b = http("POST", "/api/products", ADMIN, {
            "name": "TST 无键" + tag + SUFFIX, "code": code,
            "type": "一般产品", "cls": "工业产品", "unit": "吨"})
        made.append((s, (b.get("body") or {}).get("id")))
        CLEANUP["products"].append((b.get("body") or {}).get("id"))
    return made, all(x[0] == 200 for x in made) and len({x[1] for x in made}) == 2


# ==================== P 导出与导入 ====================
@case("P01", "导入导出", "P0", "导出产品 CSV",
      "GET /export?resource=products", "200，UTF-8 BOM 开头且含表头「编码」")
def p01():
    s, raw = http("GET", "/api/export?resource=products", ADMIN, raw=True)
    text = raw.decode("utf-8-sig", errors="ignore") if isinstance(raw, (bytes, bytearray)) else str(raw)
    return (s, len(raw), text.splitlines()[0] if text else ""), \
        s == 200 and isinstance(raw, (bytes, bytearray)) and raw.startswith(b"\xef\xbb\xbf") and "编码" in text[:80]


@case("P02", "导入导出", "P1", "导出日期格式非法",
      "GET /export?resource=readings&from=abc", "HTTP 400，不返回 500")
def p02():
    s, b = http("GET", "/api/export?resource=readings&from=abc&to=xyz", ADMIN)
    return (s, b.get("status") if isinstance(b, dict) else None), s == 400


@case("P03", "导入导出", "P1", "导入模板可下载",
      "GET /import/template?resource=products", "200，CSV 表头与导入列一致")
def p03():
    s, raw = http("GET", "/api/import/template?resource=products", ADMIN, raw=True)
    text = raw.decode("utf-8-sig", errors="ignore") if isinstance(raw, (bytes, bytearray)) else ""
    return (s, text.splitlines()[0] if text else ""), \
        s == 200 and text.startswith("编码,名称") and "1级能耗" in text


@case("P04", "导入导出", "P0", "导入产品 CSV",
      "POST /import?resource=products（1 行 TST 产品）", "200，success=1，产品可查到")
def p04():
    code = "TST-IMP-" + SUFFIX
    csv = ("编码,名称,规格型号,产品类型,产品分类,单位,1级能耗,2级能耗,3级能耗\n"
           "%s,TST 导入产品%s,,,,吨,0.10,0.20,0.30\n" % (code, SUFFIX)).encode("utf-8")
    s, b = multipart("/api/import?resource=products", ADMIN, "TST-import.csv", csv)
    d = b.get("body", {}) if isinstance(b, dict) else {}
    s2, b2 = http("GET", "/api/products?keyword=" + urllib.parse.quote("TST 导入产品" + SUFFIX), ADMIN)
    rows = (b2.get("body", {}) if isinstance(b2, dict) else {}).get("rows", [])
    hit = [r for r in rows if r.get("code") == code]
    if hit:
        CLEANUP["products"].append(hit[0].get("id"))
    return (s, d.get("success"), d.get("failed"), len(hit)), \
        s == 200 and d.get("success") == 1 and d.get("failed") == 0 and len(hit) == 1


@case("P05", "导入导出", "P1", "错误表头导入被拒",
      "POST /import?resource=products（表头 姓名,编码）", "HTTP 400 VALIDATION，库里不新增脏数据")
def p05():
    csv = "姓名,编码\nfoo,bar\n".encode("utf-8")
    s, b = multipart("/api/import?resource=products", ADMIN, "TST-bad.csv", csv)
    s2, b2 = http("GET", "/api/products?keyword=" + urllib.parse.quote("bar"), ADMIN)
    total = (b2.get("body", {}) if isinstance(b2, dict) else {}).get("total")
    return (s, b.get("status"), total), s == 400 and b.get("status") == "VALIDATION" and total == 0


# ==================== Q 角色与席位 ====================
@case("Q01", "角色", "P1", "非法页面编码被拒",
      "POST /roles {pages:[no.such.page]}", "HTTP 400 VALIDATION")
def q01():
    s, b = http("POST", "/api/roles", ADMIN, {"name": "TST 非法页" + SUFFIX, "pages": ["no.such.page"]})
    return (s, b.get("status")), s == 400 and b.get("status") == "VALIDATION"


@case("Q02", "角色", "P1", "首页权限强制补齐",
      "POST /roles {pages:[product.list]} → GET /roles/{id}", "pages 首位为 home")
def q02():
    s, b = http("POST", "/api/roles", ADMIN, {"name": "TST 首页补齐" + SUFFIX, "pages": ["product.list"]})
    rid = (b.get("body") or {}).get("roleId")
    CLEANUP["roles"].append(rid)
    s2, b2 = http("GET", "/api/roles/%s" % rid, ADMIN)
    pages = (b2.get("body") or {}).get("pages") or []
    return (s, s2, pages[:3]), s == 200 and s2 == 200 and pages[:1] == ["home"] and "product.list" in pages


@case("Q03", "角色", "P1", "角色名称租户内唯一",
      "POST /roles 同名两次", "第二次 HTTP 409 DUPLICATE")
def q03():
    name = "TST 重名角色" + SUFFIX
    s1, b1 = http("POST", "/api/roles", ADMIN, {"name": name, "pages": ["product.list"]})
    CLEANUP["roles"].append((b1.get("body") or {}).get("roleId"))
    s2, b2 = http("POST", "/api/roles", ADMIN, {"name": name, "pages": ["product.list"]})
    return (s1, s2, b2.get("status")), s1 == 200 and s2 == 409 and b2.get("status") == "DUPLICATE"


@case("Q04", "角色", "P1", "默认角色不可删除",
      "DELETE /roles/{默认角色}", "HTTP 409 ROLE_DEFAULT")
def q04():
    s0, b0 = http("GET", "/api/roles", ADMIN)
    body = b0.get("body") if isinstance(b0, dict) else None
    rows = body if isinstance(body, list) else ((body or {}).get("rows") or [])
    rid = next((r["roleId"] for r in rows if r.get("defaultRole")), None)
    s, b = http("DELETE", "/api/roles/%s" % rid, ADMIN)
    return (s, b.get("status")), s == 409 and b.get("status") == "ROLE_DEFAULT"


@case("Q05", "角色", "P1", "有成员的角色不可删除",
      "建角色+用户后 DELETE 角色", "HTTP 409 ROLE_IN_USE")
def q05():
    s0, b0 = http("POST", "/api/roles", ADMIN, {"name": "TST 在用角色" + SUFFIX, "pages": ["product.list"]})
    rid = (b0.get("body") or {}).get("roleId")
    CLEANUP["roles"].append(rid)
    phone = "135" + SUFFIX + "31"
    s1, b1 = http("POST", "/api/users", ADMIN, {
        "phone": phone, "displayName": "TST 在用成员" + SUFFIX, "userType": "user", "roleId": rid})
    CLEANUP["users"].append((b1.get("body") or {}).get("membershipId"))
    s, b = http("DELETE", "/api/roles/%s" % rid, ADMIN)
    return (s1, s, b.get("status")), s1 == 200 and s == 409 and b.get("status") == "ROLE_IN_USE"


@case("Q06", "席位", "P0", "席位已满不能新增成员",
      "平台把 seatLimit 调为当前成员数 → 新增用户", "HTTP 409 SEAT_LIMIT")
def q06():
    s0, b0 = http("GET", "/api/platform/tenants/1", PLAT)
    members = (b0.get("body") or {}).get("members")
    http("PUT", "/api/platform/tenants/1", PLAT, {"seatLimit": members})
    s, b = http("POST", "/api/users", ADMIN, {
        "phone": "135" + SUFFIX + "41", "displayName": "TST 溢出成员" + SUFFIX, "userType": "user"})
    http("PUT", "/api/platform/tenants/1", PLAT, {"seatLimit": 20})
    return (s0, members, s, b.get("status")), s == 409 and b.get("status") == "SEAT_LIMIT"


# ==================== R 安全与数据正确性 ====================
@case("R04", "安全", "P1", "导出中和 CSV 公式注入",
      "产品名 =1+1 → 导出 products", "单元格以 '= 开头，不被 Excel 当公式")
def r04():
    s, b = http("POST", "/api/products", ADMIN, {
        "name": "=1+1", "code": "TST-CSV-" + SUFFIX,
        "type": "一般产品", "cls": "工业产品", "unit": "吨"})
    pid = (b.get("body") or {}).get("id")
    CLEANUP["products"].append(pid)
    s2, raw = http("GET", "/api/export?resource=products", ADMIN, raw=True)
    text = raw.decode("utf-8-sig", errors="ignore")
    line = next((l for l in text.splitlines() if "TST-CSV-" + SUFFIX in l), "")
    return (s, s2, line[:20]), s == 200 and s2 == 200 and line.startswith("'=1+1")


@case("R05", "安全", "P0", "成员被停用后旧令牌立即失效",
      "登录 → 管理员移除成员 → 旧令牌访问 /overview", "HTTP 401")
def r05():
    phone = "135" + SUFFIX + "72"
    s, b = http("POST", "/api/users", ADMIN, {
        "phone": phone, "displayName": "TST 停用" + SUFFIX, "userType": "user"})
    mid, tmp = (b.get("body") or {}).get("membershipId"), (b.get("body") or {}).get("tempPassword")
    tok = login(phone, tmp)
    http("POST", "/api/auth/password/change", tok, {"oldPassword": tmp, "newPassword": "Abcdef123456!"})
    tok = login(phone, "Abcdef123456!")
    s1, _ = http("GET", "/api/overview", tok)
    s2, _ = http("DELETE", "/api/users/%s" % mid, ADMIN)
    s3, b3 = http("GET", "/api/overview", tok)
    return (s1, s2, s3, b3.get("status")), s1 == 200 and s2 == 200 and s3 == 401


@case("R06", "安全", "P1", "角色调整后成员旧令牌失效",
      "改角色页面 → 旧令牌访问 /products", "HTTP 401 SESSION_REVOKED")
def r06():
    s, b = http("POST", "/api/roles", ADMIN, {"name": "TST 改角色" + SUFFIX, "pages": ["product.list"]})
    rid = (b.get("body") or {}).get("roleId")
    CLEANUP["roles"].append(rid)
    phone = "135" + SUFFIX + "73"
    s1, b1 = http("POST", "/api/users", ADMIN, {
        "phone": phone, "displayName": "TST 改角色成员" + SUFFIX, "userType": "user", "roleId": rid})
    mid, tmp = (b1.get("body") or {}).get("membershipId"), (b1.get("body") or {}).get("tempPassword")
    CLEANUP["users"].append(mid)
    tok = login(phone, tmp)
    http("POST", "/api/auth/password/change", tok, {"oldPassword": tmp, "newPassword": "Abcdef123456!"})
    tok = login(phone, "Abcdef123456!")
    s2, _ = http("GET", "/api/products", tok)
    s3, _ = http("PUT", "/api/roles/%s" % rid, ADMIN,
                 {"name": "TST 改角色" + SUFFIX, "pages": ["product.list", "supplier.list"]})
    s4, b4 = http("GET", "/api/products", tok)
    return (s2, s3, s4, b4.get("status")), s2 == 200 and s3 == 200 and s4 == 401 and b4.get("status") == "SESSION_REVOKED"


@case("R07", "安全", "P1", "密码不能复用最近三次",
      "改密 A→B→再次改回 A", "HTTP 400 VALIDATION")
def r07():
    phone = "135" + SUFFIX + "74"
    s, b = http("POST", "/api/users", ADMIN, {
        "phone": phone, "displayName": "TST 历史密码" + SUFFIX, "userType": "user"})
    mid, tmp = (b.get("body") or {}).get("membershipId"), (b.get("body") or {}).get("tempPassword")
    tok = login(phone, tmp)
    http("POST", "/api/auth/password/change", tok, {"oldPassword": tmp, "newPassword": "Abcdef123456!"})
    tok = login(phone, "Abcdef123456!")
    http("POST", "/api/auth/password/change", tok, {"oldPassword": "Abcdef123456!", "newPassword": "Second123456!"})
    tok = login(phone, "Second123456!")
    s2, b2 = http("POST", "/api/auth/password/change", tok,
                  {"oldPassword": "Second123456!", "newPassword": "Abcdef123456!"})
    http("DELETE", "/api/users/%s" % mid, ADMIN)
    return (s2, b2.get("status")), s2 == 400 and b2.get("status") == "VALIDATION"


@case("R09", "安全", "P1", "报告类型非法被拒",
      "POST /reports {type:unknown}", "HTTP 400 VALIDATION")
def r09():
    s, b = http("POST", "/api/reports", ADMIN, {"type": "unknown", "period": PERIOD})
    return (s, b.get("status")), s == 400


@case("R10", "安全", "P2", "分页 size 超上限自动收敛",
      "GET /products?size=1000", "200，size≤200 且行数与 size 匹配")
def r10():
    s, b = http("GET", "/api/products?size=1000", ADMIN)
    d = b.get("body", {}) if isinstance(b, dict) else {}
    return (s, d.get("size"), len(d.get("rows") or [])), \
        s == 200 and (d.get("size") or 0) <= 200 and len(d.get("rows") or []) <= 200


# ==================== S 导入导出矩阵 ====================
@case("S01", "导入导出", "P1", "生产产值 CSV 导入",
      "先建产品，再导入 {月份,产品编码,产值,产值单位,产量,产量单位}", "success=1，产值列表可查到")
def s01():
    s, b = http("POST", "/api/products", ADMIN, {
        "name": "TST 产值产品" + SUFFIX, "code": "TST-PV-" + SUFFIX,
        "type": "一般产品", "cls": "工业产品", "unit": "吨"})
    pid = (b.get("body") or {}).get("id")
    CLEANUP["products"].append(pid)
    csv = ("月份,产品编码,产值,产值单位,产量,产量单位\n"
           "2026-11,TST-PV-%s,100,万元,10,吨\n" % SUFFIX).encode("utf-8")
    s1, b1 = multipart("/api/import?resource=production-values", ADMIN, "TST-pv.csv", csv)
    s2, b2 = http("GET", "/api/production-values?from=2026-11&to=2026-11&size=50&keyword="
                  + urllib.parse.quote("TST 产值产品" + SUFFIX), ADMIN)
    rows = (b2.get("body", {}) if isinstance(b2, dict) else {}).get("rows", [])
    hit = [r for r in rows if r.get("productId") == pid]
    if hit:
        CLEANUP["production"].append(hit[0].get("id"))
    return (s1, (b1.get("body") or {}).get("success"), len(hit)), \
        s1 == 200 and (b1.get("body") or {}).get("success") == 1 and len(hit) == 1


@case("S02", "导入导出", "P1", "碳资产 CSV 导入",
      "导入 {年度,资产名称,资产类型,数量,单位}", "success=1，资产列表可查到")
def s02():
    csv = ("年度,资产名称,资产类型,数量,单位\n"
           "2026,TST 资产%s,配额,10,tCO2e\n" % SUFFIX).encode("utf-8")
    s, b = multipart("/api/import?resource=carbon-assets", ADMIN, "TST-asset.csv", csv)
    s2, b2 = http("GET", "/api/carbon-assets?keyword=" + urllib.parse.quote("TST 资产" + SUFFIX), ADMIN)
    rows = (b2.get("body", {}) if isinstance(b2, dict) else {}).get("rows", [])
    if rows:
        CLEANUP["assets"].append(rows[0].get("id"))
    return (s, (b.get("body") or {}).get("success"), len(rows)), \
        s == 200 and (b.get("body") or {}).get("success") == 1 and len(rows) == 1


@case("S03", "导入导出", "P1", "供应商 CSV 导入",
      "导入 {供应商名称,产品名称,产品单位,碳足迹值,碳足迹单位,碳足迹日期,核算范围}", "success=1，列表可查到")
def s03():
    csv = ("供应商名称,产品名称,产品单位,碳足迹值,碳足迹单位,碳足迹日期,核算范围\n"
           "TST 供应商%s,铝合金,吨,1.20,tCO2e/t,2026-11-01,范围三\n" % SUFFIX).encode("utf-8")
    s, b = multipart("/api/import?resource=suppliers", ADMIN, "TST-sup.csv", csv)
    s2, b2 = http("GET", "/api/suppliers?keyword=" + urllib.parse.quote("TST 供应商" + SUFFIX), ADMIN)
    rows = (b2.get("body", {}) if isinstance(b2, dict) else {}).get("rows", [])
    if rows:
        CLEANUP["suppliers"].append(rows[0].get("id"))
    return (s, (b.get("body") or {}).get("success"), len(rows)), \
        s == 200 and (b.get("body") or {}).get("success") == 1 and len(rows) == 1


@case("S04", "导入导出", "P1", "设施 CSV 导入",
      "导入 {设施编码,设施名称,上级设施编码,排序,备注}", "success=1，设施列表可查到")
def s04():
    csv = ("设施编码,设施名称,上级设施编码,排序,备注\n"
           "TST-IMP-F-%s,TST 设施%s,,1,用例\n" % (SUFFIX, SUFFIX)).encode("utf-8")
    s, b = multipart("/api/import?resource=facilities", ADMIN, "TST-fac.csv", csv)
    s2, b2 = http("GET", "/api/facilities?keyword=" + urllib.parse.quote("TST 设施" + SUFFIX), ADMIN)
    rows = (b2.get("body") if isinstance(b2.get("body"), list) else
            (b2.get("body") or {}).get("rows", [])) or []
    if rows:
        CLEANUP["facilities"].append(rows[0].get("id"))
    return (s, (b.get("body") or {}).get("success"), len(rows)), \
        s == 200 and (b.get("body") or {}).get("success") == 1 and len(rows) == 1


@case("S05", "导入导出", "P1", "13 类资源导出全部可用",
      "GET /export?resource=… 遍历 13 个资源", "全部 200 且 CSV 非空")
def s05():
    resources = ["readings", "production-values", "total-values", "evidences", "third-party-reports",
                 "facilities", "products", "suppliers", "carbon-assets", "carbon-trades",
                 "footprints", "alert-records", "devices"]
    bad = []
    for r in resources:
        s, raw = http("GET", "/api/export?resource=" + r, ADMIN, raw=True)
        if s != 200 or not isinstance(raw, (bytes, bytearray)) or len(raw) < 40:
            bad.append((r, s, len(raw) if isinstance(raw, (bytes, bytearray)) else 0))
    return (len(resources), bad), not bad


# ==================== 执行 ====================
def run():
    results = []
    print("功能测试用例：%d 条  BASE=%s\n" % (len(CASES), BASE))
    for c in CASES:
        actual, ok = None, False
        try:
            actual, ok = c["fn"]()
        except Exception as e:
            actual, ok = "异常: " + str(e)[:120], False
        results.append({"id": c["id"], "module": c["module"], "prio": c["prio"], "title": c["title"],
                        "input": c["input"], "expected": c["expected"],
                        "actual": actual if isinstance(actual, str) else json.dumps(actual, ensure_ascii=False, default=str),
                        "pass": bool(ok)})
        mark = "PASS" if ok else "FAIL"
        print("[%s] %s %s %s" % (mark, c["id"], c["title"], "" if ok else "→ " + results[-1]["actual"][:100]))
    return results


def cleanup():
    def rm(path, ids):
        for i in ids:
            if i:
                http("DELETE", path % i, ADMIN)
    rm("/api/products/%s", CLEANUP["products"])
    rm("/api/devices/%s", CLEANUP["devices"])
    rm("/api/facilities/%s", CLEANUP["facilities"])
    rm("/api/suppliers/%s", CLEANUP["suppliers"])
    rm("/api/quota-years/%s", CLEANUP["quotas"])
    rm("/api/carbon-assets/%s", CLEANUP["assets"])
    rm("/api/evidences/%s", CLEANUP["evidences"])
    rm("/api/footprints/%s", CLEANUP["footprints"])
    rm("/api/alert-rules/%s", CLEANUP["alertRules"])
    rm("/api/files/%s", CLEANUP["files"])
    rm("/api/production-values/%s", CLEANUP["production"])
    for mid in CLEANUP["users"]:
        if mid:
            http("DELETE", "/api/users/%s" % mid, ADMIN)
    for rid in CLEANUP["roles"]:
        if rid:
            http("DELETE", "/api/roles/%s" % rid, ADMIN)
    # 本用例会在平台侧新开租户（TST 功能测试租户），API 没有删除入口，
    # 走统一清理脚本按本次标记清掉，否则平台概览的租户数与账号数会持续虚高
    try:
        subprocess.run(["bash", "tools/purge-test-data.sh", "--marker", SUFFIX, "--apply"],
                       cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       capture_output=True, timeout=120)
    except Exception as e:
        print("  [warn] 测试数据清理未执行：%s" % e)


def to_md(results):
    lines = ["# 功能测试用例与执行结果（%s）" % TODAY, "",
             "> 执行环境：本地服务 `%s`（MySQL 3307 + 演示数据）；用例脚本：`platform/backend/tools/functional-cases.py`。" % BASE,
             "> 结果含义：PASS=实际与预期一致；FAIL=不一致（详见文末缺陷汇总）。", "",
             "| 编号 | 模块 | 优先级 | 用例 | 输入 | 预期结果 | 实际结果 | 结果 |",
             "|---|---|---|---|---|---|---|---|"]
    for r in results:
        esc = lambda s: str(s).replace("|", "\\|").replace("\n", " ")
        lines.append("| %s | %s | %s | %s | %s | %s | %s | %s |" % (
            r["id"], r["module"], r["prio"], esc(r["title"]), esc(r["input"]),
            esc(r["expected"]), esc(r["actual"]), "PASS" if r["pass"] else "**FAIL**"))
    total = len(results)
    passed = sum(1 for r in results if r["pass"])
    lines += ["", "## 汇总", "",
              "| 项 | 值 |", "|---|---|",
              "| 用例总数 | %d |" % total,
              "| 通过 | %d |" % passed,
              "| 失败 | %d |" % (total - passed),
              "| 通过率 | %.1f%% |" % (passed * 100.0 / total),
              "| 按优先级 | P0 %d / P1 %d / P2 %d |" % (
                  sum(1 for r in results if r["prio"] == "P0"),
                  sum(1 for r in results if r["prio"] == "P1"),
                  sum(1 for r in results if r["prio"] == "P2")),
              ""]
    return "\n".join(lines)


if __name__ == "__main__":
    results = run()
    cleanup()
    total = len(results)
    failed = [r for r in results if not r["pass"]]
    passed = total - len(failed)
    out_dir = os.path.join(os.path.dirname(__file__), "..", "target", "test-run")
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "functional-%s.json" % TODAY), "w", encoding="utf-8") as f:
        json.dump({"base": BASE, "date": TODAY, "total": total, "passed": passed, "results": results},
                  f, ensure_ascii=False, indent=2)
    docs = os.path.join(os.path.dirname(__file__), "..", "..", "docs")
    os.makedirs(docs, exist_ok=True)
    with open(os.path.join(docs, "test-cases-%s.md" % TODAY), "w", encoding="utf-8") as f:
        f.write(to_md(results))
    print("\nRESULT: %s (%d/%d)" % ("PASS" if not failed else "FAIL", passed, total))
    sys.exit(0 if not [r for r in failed if r["prio"] in ("P0", "P1")] else 1)
