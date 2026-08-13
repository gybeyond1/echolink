# ===== 构建阶段：安装原生模块编译工具并安装依赖 =====
FROM node:22-bookworm-slim AS build
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 make g++ \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY package*.json ./
RUN npm install --omit=dev && npm cache clean --force

# ===== 运行阶段：只拷贝编译好的依赖与源码，不含构建工具链 =====
FROM node:22-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3000

# 从构建阶段拷贝已编译的依赖（含 better-sqlite3 原生二进制）
COPY --from=build /app/node_modules ./node_modules
COPY package*.json ./
COPY src ./src
# 管理后台前端页面（之前漏拷，导致镜像里没有 WebUI）
COPY public ./public

VOLUME ["/app/data"]
EXPOSE 3000
CMD ["node", "src/index.js"]
