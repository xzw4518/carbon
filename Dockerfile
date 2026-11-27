# 一站式云托管上传专用 Dockerfile（腾讯云 CloudBase 云托管 / 阿里云 SAE 镜像模式 / 通用 PaaS）
# 构建上下文 = 交付包根目录（本文件与 README.md、platform/ 同级）
# 与 platform/deploy/Dockerfile 的区别：上下文从 platform/ 提升到包根，路径前面多了 platform/
#
# 默认启动 dev 配置（H2 内存库，无需 MySQL，重启数据重置）——用于快速拿到访问链接
# 正式运行：设置环境变量 SPRING_PROFILES_ACTIVE=prod + DB_URL/DB_USER/DB_PASSWORD + JWT_SECRET

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# 国内构建加速：阿里云 Maven 镜像（CloudBase/SAE 构建机访问中央仓库较慢）
RUN mkdir -p /root/.m2 && cat > /root/.m2/settings.xml <<'EOF'
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <name>Aliyun Public</name>
      <mirrorOf>central</mirrorOf>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
EOF

COPY platform/backend/pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY platform/backend/src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

ENV TZ=Asia/Shanghai \
    PORT=8080 \
    SPRING_PROFILES_ACTIVE=dev \
    APP_FILE_ROOT=/app/data/files \
    LOG_FILE=/app/logs/carbonbal.log

COPY --from=build /src/target/carbonbal.jar /app/carbonbal.jar
RUN mkdir -p /app/logs /app/data/files

EXPOSE 8080
ENTRYPOINT ["java", "-Xms256m", "-Xmx1g", "-jar", "/app/carbonbal.jar"]
