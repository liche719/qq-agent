# syntax=docker/dockerfile:1
# 前端（Vue 3 + Vite）先构建，产物再交给 Maven 打进 jar
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json* ./
# 依赖下载走 BuildKit 缓存挂载，重复构建不必重新下载
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund || npm install --no-audit --no-fund
COPY web/ ./
# 构建到容器内的 dist（本地开发时 vite.config.js 直接输出到后端 static 目录）
RUN npm run build -- --outDir dist --emptyOutDir

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
COPY --from=web /web/dist ./src/main/resources/static
# Maven 依赖同样走缓存挂载
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
COPY --from=build /workspace/target/wechat-agent-java-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 443
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
