# =============================================================================
# DocMind 前端镜像：Node 构建 → Nginx 托管静态产物
# =============================================================================

# ---------- stage 1: build ----------
FROM node:22.22-alpine AS builder

WORKDIR /build
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund || npm install --no-audit --no-fund

COPY . .
ARG VITE_API_BASE=/api
ENV VITE_API_BASE=$VITE_API_BASE
RUN npm run build

# ---------- stage 2: runtime ----------
FROM nginx:1.29-alpine

RUN apk add --no-cache curl \
 && rm -f /etc/nginx/conf.d/default.conf

COPY --from=builder /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/docmind.conf

# 健康检查探针（纯静态，不依赖后端）
RUN echo "ok" > /usr/share/nginx/html/healthz

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
