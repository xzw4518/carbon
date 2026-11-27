#!/usr/bin/env bash
# 共用：图形验证码解析 + 登录（smoke.sh / e2e.sh 共用，避免两套实现漂移）。
#
# 背景（P1-7）：密码登录已强制图形验证码，smoke.sh 早期版本只发 phone/password，
# 改造后必然 400，导致"上线后冒烟永远失败"。验证码解析只允许有一份实现。
#
# 用法：
#   BASE=http://127.0.0.1:8088
#   . tools/lib-auth.sh
#   TOKEN=$(login 13800000001 'Admin@2026!Pass')
set -uo pipefail

: "${BASE:=http://127.0.0.1:8088}"

# JSON 取值：jget "d['body']['token']" <<< "$json"
jget() {
  python -c "import sys,json;d=json.load(sys.stdin);print(eval(sys.argv[1]))" "$1" 2>/dev/null
}

# 取一张图形验证码，输出 "captchaId|code"（SVG 里字符以 <text> 渲染）
cap_token() {
  local raw id img svg code
  raw=$(curl -s "$BASE/api/auth/captcha")
  id=$(printf '%s' "$raw" | jget "d['body']['captchaId']")
  img=$(printf '%s' "$raw" | jget "d['body']['image']")
  svg=$(printf '%s' "$img" | sed 's/^data:image\/svg+xml;base64,//' | base64 -d 2>/dev/null)
  code=$(printf '%s' "$svg" | grep -o '<text[^>]*>[^<]</text>' | sed 's/.*>\(.\)<\/text>/\1/' | tr -d '\n')
  printf '%s|%s' "$id" "$code"
}

# 手机号 + 密码登录（带图形验证码），输出 token
login() {
  local c id code
  c=$(cap_token); id=${c%%|*}; code=${c##*|}
  curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$1\",\"password\":\"$2\",\"captchaId\":\"$id\",\"captchaCode\":\"$code\"}" \
    | jget "d['body']['token']"
}

# 按用户名登录（回滚兼容入口）
login_username() {
  local c id code
  c=$(cap_token); id=${c%%|*}; code=${c##*|}
  curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\",\"captchaId\":\"$id\",\"captchaCode\":\"$code\"}" \
    | jget "d['body']['token']"
}
