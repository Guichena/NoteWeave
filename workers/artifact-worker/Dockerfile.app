# 日常源码变更用：apt 与 pip 依赖已冻结在 base 镜像里，重建只复制源码，秒级完成。
# 只有首次构建或依赖变更时才用同目录下的完整 Dockerfile（需 4–5 GB 磁盘）。
#
# 用法：
#   docker build -f Dockerfile.app -t noteweave-artifact-worker-api:latest .
#
# base 镜像由完整 Dockerfile 构建成功后打标签得到：
#   docker tag noteweave-artifact-worker-api:<已构建的标签> noteweave-artifact-worker-base:py312
ARG WORKER_BASE=noteweave-artifact-worker-base:py312
FROM ${WORKER_BASE}

WORKDIR /app

# 先清空源码目录，避免删除过的文件残留在旧层里。
RUN rm -rf /app/app /app/mcp_servers
COPY app ./app
COPY mcp_servers ./mcp_servers
RUN mkdir -p /app/runtime

EXPOSE 18092
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "18092"]
