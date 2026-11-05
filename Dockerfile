# 找个搭子 · 校园活动组队与候补平台
# 单容器部署：前端 Vue 构建产物由后端 jar 同源提供（/api 与页面同一域名，
# 手机 PWA 与电脑浏览器用同一个网址）。
# 构建分三段：前端构建 -> 后端打包 -> 精简运行镜像。

# ---- 1. 构建前端 ----
FROM node:20-alpine AS frontend-build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/src ./src
COPY frontend/public ./public
COPY frontend/index.html frontend/vite.config.js ./
RUN npm run build

# ---- 2. 打包后端 ----
FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /build
COPY backend/pom.xml backend/settings-aliyun.xml ./backend/
COPY backend/src ./backend/src
# 前端构建产物放进后端静态资源目录，随 jar 一起提供
COPY --from=frontend-build /build/frontend/dist ./backend/src/main/resources/static
WORKDIR /build/backend
RUN mvn -s settings-aliyun.xml package -DskipTests

# ---- 3. 运行 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=backend-build /build/backend/target/campus-buddy-1.0.0.jar ./app.jar
# Render 会注入 PORT；数据库连接由环境变量 DB_HOST/DB_PORT/DB_NAME/
# DB_USER/DB_PASSWORD 提供（见 application.properties，默认值为本地开发值）
EXPOSE 8080
CMD ["java", "-Dfile.encoding=UTF-8", "-Djava.net.preferIPv4Stack=true", "-jar", "app.jar"]
