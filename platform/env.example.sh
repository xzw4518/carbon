# 环境变量模板：复制为 env.sh 后按实际修改
#   cp env.example.sh env.sh && vi env.sh
# 注意：env.sh 含密钥，不要提交到任何仓库。

# ---- JDK / Maven（未装全局时可指定）----
# export JAVA_HOME=/usr/lib/jvm/jdk-17
# export PATH="$JAVA_HOME/bin:$PATH"

# ---- 生产数据源（--spring.profiles.active=prod 时生效）----
export DB_URL='jdbc:mysql://127.0.0.1:3306/carbonbal?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export DB_USER=carbonbal
export DB_PASSWORD='change-me'

# ---- 安全 ----
# 生成：bash deploy/gen-secret.sh .env.production
# 然后：set -a; . .env.production; set +a
# 留空时：dev profile 自动生成一次性随机密钥；prod 直接启动失败（不会退回任何默认密钥）
export JWT_SECRET=''

# 反向代理白名单：只有 remoteAddr 命中这里才信任 X-Forwarded-For（逗号分隔）。
# 留空 = 不信任任何代理，客户端 IP 一律取 remoteAddr（防伪造 XFF 绕过锁定）。
export TRUSTED_PROXIES=''

# ---- 端口 ----
# 云平台（Railway/Render/Fly/Cloud Run）会注入 PORT，应用自动跟随；本机默认 8088
export PORT=8088

# ---- 可选开关 ----
export LOG_FILE='./logs/carbonbal.log'
export JOBS_ENABLED=true          # 预警扫描/月报/配额提醒
export SEED_ENABLED=false         # 首次初始化后关闭种子
export SMS_FIXED_CODE=''          # 生产留空；演示可设 123456
export SMS_EXPOSE_CODE=false      # 生产必须 false
export AUTH_CAPTCHA=true
export AUTH_COOKIE=true
