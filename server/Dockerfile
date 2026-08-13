# NotifySync Server
# better-sqlite3 是原生模块，需要构建工具编译预编译二进制
FROM node:22-bookworm-slim

# 安装构建工具（better-sqlite3 原生模块需要）
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 make g++ \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 先复制依赖清单，利用 Docker 层缓存
COPY package*.json ./
RUN npm install --omit=dev && npm cache clean --force

# 复制源码
COPY src ./src

ENV NODE_ENV=production
ENV PORT=3000

EXPOSE 3000

# 数据目录（docker-compose 会挂载 volume）
VOLUME ["/app/data"]

CMD ["node", "src/index.js"]
