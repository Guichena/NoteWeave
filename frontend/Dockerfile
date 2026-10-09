FROM node:22-alpine AS build
WORKDIR /workspace
# 构建需要经过 HTTP(S)_PROXY 时设为 1；否则 corepack 使用的 Node fetch 会忽略代理变量。
ARG NODE_USE_ENV_PROXY

RUN corepack enable && corepack prepare pnpm@11.7.0 --activate
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

COPY . .
RUN pnpm build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html

EXPOSE 80
