# NoteWeave 启动指南

## 1. 项目说明

NoteWeave 是一个把团队知识、个人研究和成果沉淀放进同一工作台的应用。

当前本地验收地址：

- 登录页：[http://127.0.0.1:18082/login](http://127.0.0.1:18082/login)

## 2. 启动前准备

本地需要准备：

- JDK 17+
- Maven 3.9+
- Docker Desktop 或可用的 Docker Compose 环境

如果你走下面的全栈 Docker 启动方式，本机可以不单独安装 JDK 和 Maven。

## 3. 依赖服务与端口

项目依赖以下服务：

- MySQL：`13307`
- Redis：`6380`
- MinIO API：`19100`
- MinIO Console：`19101`
- Elasticsearch：`19200`
- Kafka：`19092`

说明：

- 这个项目当前不是默认的 `3307 / 9200 / 9092`
- 本地启动时要特别注意 MySQL 用 `13307`，Elasticsearch 用 `19200`
- Kafka 默认改为 `19092`，避免和其他项目冲突

## 4. 一键启动整套环境（推荐）

在项目根目录执行：

```powershell
Copy-Item .env.example .env
docker compose up -d --build
```

检查容器是否启动：

```powershell
docker ps
```

如果只想看关键服务状态，可以重点确认：

- `noteweave-mysql`
- `noteweave-redis`
- `noteweave-minio`
- `noteweave-elasticsearch`
- `noteweave-kafka`
- `noteweave-app`

这条路径会同时启动中间件和 NoteWeave 应用，浏览器直接访问：

- 登录页：[http://127.0.0.1:18082/login](http://127.0.0.1:18082/login)
- OpenAPI：[http://127.0.0.1:18082/swagger-ui.html](http://127.0.0.1:18082/swagger-ui.html)
- 健康检查：[http://127.0.0.1:18082/actuator/health](http://127.0.0.1:18082/actuator/health)

## 5. 本地源码调试启动

如果你要本地断点调试，先只启动依赖服务：

```powershell
Copy-Item .env.example .env
docker compose up -d mysql redis minio minio-init elasticsearch kafka kafka-init
```

然后再启动后端应用。

### 方式 A：直接运行打包后的 Jar

先打包：

```powershell
.\mvnw.cmd -q -DskipTests package
```

再启动：

```powershell
java -Dspring.profiles.active=dev -Dserver.port=18082 -DDB_URL="jdbc:mysql://localhost:13307/noteweave?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" -DES_URIS="http://localhost:19200" -jar target/noteweave-0.0.1-SNAPSHOT.jar
```

### 方式 B：用 Maven 直接启动

```powershell
$env:SPRING_PROFILES_ACTIVE="dev"
$env:SERVER_PORT="18082"
$env:DB_URL="jdbc:mysql://localhost:13307/noteweave?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
$env:ES_URIS="http://localhost:19200"
.\mvnw.cmd spring-boot:run
```

## 6. 启动成功后的访问地址

- 登录页：[http://127.0.0.1:18082/login](http://127.0.0.1:18082/login)
- OpenAPI：[http://127.0.0.1:18082/swagger-ui.html](http://127.0.0.1:18082/swagger-ui.html)
- 健康检查：[http://127.0.0.1:18082/actuator/health](http://127.0.0.1:18082/actuator/health)

## 7. 测试账号

开发环境内置了以下账号，密码统一为：

- 密码：`NoteWeave123!`

账号如下：

- `admin`
  - 显示名：`管理员`
  - 角色：系统管理员，拥有后台与团队空间完整能力
- `alice`
  - 显示名：`艾丽丝`
  - 角色：普通用户，拥有个人空间，并且是团队空间编辑者
- `bob`
  - 显示名：`鲍勃`
  - 角色：普通用户，是团队空间只读成员

## 8. 当前内置示例数据

为了方便验收，系统已自动写入一批中文假数据：

- 团队空间：`产品策略协作台`
- 团队知识库：`AI 产品研究资料库`、`发布准备台`
- 个人研究项目：`三季度新手引导阻力研究`、`竞品表述跟踪`
- 成果：`三季度新手引导建议简报`
- 团队 Wiki：`研究协作原则`

## 9. 常见问题

### 9.1 登录页打不开

先检查应用是否真的监听在 `18082`：

```powershell
curl.exe -I http://127.0.0.1:18082/login
```

如果有响应，通常说明服务已经启动。

### 9.2 数据库连不上

重点检查：

- MySQL 端口是不是 `13307`
- 连接串是不是：

```text
jdbc:mysql://localhost:13307/noteweave?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
```

### 9.3 Elasticsearch 连不上

重点检查：

- ES 地址是不是 `http://localhost:19200`
- 不是默认的 `http://localhost:9200`

### 9.4 页面有数据但名称不对

当前开发种子数据已经切换为中文。
如果你看到旧英文名，通常说明应用还在使用旧数据或旧进程，建议重启当前应用。

## 10. 验收文档

角色、权限和验收路径说明见：

- [NOTEWEAVE_验收说明.md](/D:/java-projects/NoteWeave/NOTEWEAVE_验收说明.md)
