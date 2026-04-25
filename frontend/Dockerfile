# syntax=docker/dockerfile:1.7
FROM node:20-alpine AS build
WORKDIR /build
RUN corepack enable && corepack prepare pnpm@9 --activate
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/
WORKDIR /build/frontend
RUN pnpm install --frozen-lockfile
COPY frontend .
RUN pnpm build

# 产物镜像：只含 dist/，通过 volume 暴露给 Nginx
FROM alpine:3.20
RUN mkdir -p /dist
COPY --from=build /build/frontend/dist /dist
CMD ["sh", "-c", "cp -r /dist/. /share/ && tail -f /dev/null"]
# 运行时：通过 bind-mount volume，把 /share 共享给 Nginx 容器
