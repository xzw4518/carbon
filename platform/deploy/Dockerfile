# 单 jar 部署：先构建再运行
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY backend/pom.xml .
RUN mvn -q -o dependency:go-offline || mvn -q dependency:go-offline
COPY backend/src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/carbonbal.jar /app/carbonbal.jar
ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod \
    LOG_FILE=/app/logs/carbonbal.log
RUN mkdir -p /app/logs /app/data/files
EXPOSE 8088
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8088/api/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-Xms256m", "-Xmx1g", "-jar", "/app/carbonbal.jar"]
