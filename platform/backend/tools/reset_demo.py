# -*- coding: utf-8 -*-
"""演示数据复位：把核算期间退回草稿，并清理测试残留。

用法：
    bash tools/reset-demo.sh                # 默认 2026-11 期间 + 清理 TST 残留
    bash tools/reset-demo.sh --period 2026-12
    bash tools/reset-demo.sh --no-purge     # 只退期间，不清理

背景：functional-cases.py / ui-flows.cjs 会真实走「计算→提交」，
跑完演示库会停在 submitted；本脚本按正规流转（提交→复核→批准→锁定→解锁）
退回 draft，保留计算结果、报告与审计，不使用直接改库的旁路。
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("BASE", "http://localhost:8088")

ACCOUNTS = {
    "admin": (os.environ.get("RESET_ADMIN_PHONE", "13800000001"),
              os.environ.get("RESET_ADMIN_PWD", "Admin@2026!Pass")),
    "filler": (os.environ.get("RESET_FILLER_PHONE", "13800000002"),
               os.environ.get("RESET_FILLER_PWD", "Test@2026!Pass")),
    "reviewer": (os.environ.get("RESET_REVIEWER_PHONE", "13800000004"),
                 os.environ.get("RESET_REVIEWER_PWD", "Test@2026!Pass")),
    "approver": (os.environ.get("RESET_APPROVER_PHONE", "13800000005"),
                 os.environ.get("RESET_APPROVER_PWD", "Test@2026!Pass")),
}


def http(method, path, token=None, body=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {} if data is None else {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}


def captcha():
    s, b = http("GET", "/api/auth/captcha")
    bd = b.get("body") or {}
    svg = base64.b64decode((bd.get("image") or "").split(",", 1)[1]).decode()
    return bd.get("captchaId"), "".join(re.findall(r"<text[^>]*>([^<])</text>", svg))


def login(role):
    phone, pwd = ACCOUNTS[role]
    cid, code = captcha()
    s, b = http("POST", "/api/auth/login", None,
                {"phone": phone, "password": pwd, "captchaId": cid, "captchaCode": code})
    if s != 200:
        raise SystemExit("登录失败 %s: %s %s" % (role, s, b))
    return b["body"]["token"]


def period_state(token, period):
    s, b = http("GET", "/api/accounting-periods/" + period, token)
    if s != 200:
        raise SystemExit("读取期间失败 %s: %s %s" % (period, s, b))
    body = b.get("body") or {}
    return body.get("status"), body.get("version")


PLAN = [
    ("calculated", "submit", "filler", {}),
    ("submitted", "review", "reviewer", {}),
    ("reviewed", "approve", "approver", {}),
    ("approved", "lock", "admin", {}),
    ("locked", "unlock", "admin", {"reason": "演示数据复位"}),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--period", default="2026-11")
    ap.add_argument("--no-purge", action="store_true")
    args = ap.parse_args()

    tokens = {role: login(role) for role in ACCOUNTS}
    status, version = period_state(tokens["admin"], args.period)
    print("期间 %s 当前状态：%s（v%s）" % (args.period, status, version))

    if status == "draft":
        print("已是草稿，跳过流转")
    else:
        for _ in range(8):
            status, version = period_state(tokens["admin"], args.period)
            if status == "draft":
                break
            step = next((p for p in PLAN if p[0] == status), None)
            if step is None:
                print("当前状态 %s 无可用流转，请人工确认" % status)
                break
            _, action, role, extra = step
            payload = dict(version=version)
            payload.update(extra)
            s, b = http("POST", "/api/accounting-periods/%s/%s" % (args.period, action), tokens[role], payload)
            body = b.get("body") or {}
            print("  %-8s -> %s" % (action, body.get("status") or b.get("status")))
            if s != 200:
                print("  中断：%s" % json.dumps(b, ensure_ascii=False)[:200])
                break
        status, version = period_state(tokens["admin"], args.period)
        print("期间 %s 复位为：%s（v%s）" % (args.period, status, version))

    if not args.no_purge:
        script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "purge-test-data.sh")
        if os.path.exists(script):
            print("清理测试残留 ...")
            subprocess.run(["bash", script, "--apply"], check=False)

    return 0 if status == "draft" else 1


if __name__ == "__main__":
    sys.exit(main())
