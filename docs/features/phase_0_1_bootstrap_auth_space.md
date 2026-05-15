# Phase 0/1: 工程骨架、认证、用户、空间与权限

本文档用于指导 NoteWeave 第一阶段编码实现。

范围：

```text
Phase 0: Spring Boot 工程骨架
Phase 1: Auth / User / Space / SpaceMember / SpacePermissionService
```

第一阶段目标不是实现 RAG，而是把所有后续资源的归属、认证和权限边界打牢。

---

## 1. 阶段目标

第一阶段完成后，系统应具备：

- Spring Boot 后端工程可启动。
- MySQL 可连接。
- Redis 可连接。
- 用户可以注册。
- 用户可以登录并获得 JWT。
- 用户注册后自动创建个人 Space。
- 用户可以创建团队 Space。
- OWNER 可以管理团队成员。
- Space 权限判断有统一入口。
- 非成员不能访问团队 Space。

---

## 2. 技术栈定稿

参考 `PaiSmart-main`，第一阶段采用以下技术栈：

```text
Java 17
Spring Boot 3.x
Spring Web
Spring Security
Spring Data JPA
Spring Data Redis
MySQL
JWT
Lombok
Validation
Maven
```

暂不接入：

```text
Kafka
MinIO
Elasticsearch
WebSocket
LLM
Embedding
```

但工程结构要预留这些模块。

---

## 3. Maven 依赖建议

第一阶段需要：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.11.5</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.11.5</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.11.5</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

---

## 4. 推荐包结构

```text
com.noteweave
  ├── NoteWeaveApplication
  ├── common
  │     ├── api
  │     ├── error
  │     ├── security
  │     └── time
  ├── auth
  │     ├── controller
  │     ├── dto
  │     └── service
  ├── user
  │     ├── controller
  │     ├── dto
  │     ├── model
  │     ├── repository
  │     └── service
  ├── space
  │     ├── controller
  │     ├── dto
  │     ├── model
  │     ├── repository
  │     └── service
  └── permission
        └── service
```

预留但第一阶段不实现：

```text
team
personal
chat
task
storage
search
llm
embedding
artifact
```

---

## 5. 统一响应与错误

### 5.1 ApiResponse

所有接口返回统一结构：

```json
{
  "success": true,
  "code": "OK",
  "message": "success",
  "data": {}
}
```

建议类：

```text
common.api.ApiResponse<T>
```

字段：

```text
success
code
message
data
timestamp
```

列表接口必须统一返回分页结构，避免前端为不同模块写特殊适配。

建议类：

```text
common.api.PageResponse<T>
```

字段：

```text
items
page
pageSize
total
totalPages
sort
filters
```

要求：

- 所有 `GET list` 类接口都返回 `ApiResponse<PageResponse<T>>`。
- 分页参数统一使用 `page`、`pageSize`、`sort`，默认 `page=1`、`pageSize=20`。
- 列表筛选条件进入对应 Query DTO，不允许 Controller 临时拼接散乱参数。

### 5.2 错误码

第一阶段至少定义：

```text
OK
BAD_REQUEST
UNAUTHORIZED
FORBIDDEN
NOT_FOUND
CONFLICT
VALIDATION_FAILED
INTERNAL_ERROR

USER_ALREADY_EXISTS
INVALID_CREDENTIALS
SPACE_NOT_FOUND
SPACE_ACCESS_DENIED
MEMBER_NOT_FOUND
OWNER_CANNOT_BE_REMOVED
```

### 5.3 全局异常处理

建议类：

```text
common.error.BusinessException
common.error.ErrorCode
common.error.GlobalExceptionHandler
```

处理：

- 参数校验异常。
- 认证异常。
- 权限异常。
- 业务异常。
- 未预期异常。

---

## 6. 安全设计

### 6.1 JWT

JWT Claims：

```text
sub = userId
username
systemRole
iat
exp
```

建议配置：

```yaml
jwt:
  secret-key: ${JWT_SECRET_KEY:change-me-in-dev}
  access-token-expiration-seconds: ${JWT_ACCESS_TOKEN_EXPIRATION_SECONDS:900}
  refresh-token-expiration-seconds: ${JWT_REFRESH_TOKEN_EXPIRATION_SECONDS:1209600}
```

安全约束：

- `change-me-in-dev` 只允许 `dev` / `test` profile 使用。
- `prod` / `staging` 启动时如果 `JWT_SECRET_KEY` 为空、过短或仍为默认值，应用必须 fail fast。
- 生产环境密钥必须来自环境变量或密钥管理服务，不写入仓库配置文件。

### 6.2 SecurityConfig

放行接口：

```text
POST /api/v1/auth/register
POST /api/v1/auth/login
GET  /actuator/health
```

其他接口需要认证。

### 6.3 当前用户获取

建议提供：

```text
common.security.CurrentUser
common.security.CurrentUserProvider
```

业务层不要直接解析 JWT。

---

## 7. 数据模型

### 7.1 User

表名：

```text
users
```

字段：

```text
id BIGINT PK AUTO_INCREMENT
username VARCHAR(64) NOT NULL UNIQUE
email VARCHAR(128) UNIQUE
password_hash VARCHAR(255) NOT NULL
display_name VARCHAR(64)
avatar_url VARCHAR(255)
system_role VARCHAR(32) NOT NULL DEFAULT 'USER'
status VARCHAR(32) NOT NULL
last_login_at DATETIME
disabled_at DATETIME
created_at DATETIME NOT NULL
updated_at DATETIME NOT NULL
```

枚举：

```text
status: ACTIVE, DISABLED
system_role: USER, ADMIN
```

说明：

- 密码必须使用 BCrypt。
- 登录标识第一阶段使用 `usernameOrEmail`，支持 username 或 email。
- email 建议必填，便于成员邀请和添加。

### 7.2 Space

表名：

```text
space
```

字段：

```text
id BIGINT PK AUTO_INCREMENT
name VARCHAR(128) NOT NULL
type VARCHAR(32) NOT NULL
owner_id BIGINT NOT NULL
description VARCHAR(500)
status VARCHAR(32) NOT NULL
created_at DATETIME NOT NULL
updated_at DATETIME NOT NULL
```

枚举：

```text
type: PERSONAL, TEAM
status: ACTIVE, ARCHIVED
```

约束：

- 每个用户只能有一个 PERSONAL Space。
- TEAM Space 由用户主动创建。

### 7.3 SpaceMember

表名：

```text
space_member
```

字段：

```text
id BIGINT PK AUTO_INCREMENT
space_id BIGINT NOT NULL
user_id BIGINT NOT NULL
role VARCHAR(32) NOT NULL
status VARCHAR(32) NOT NULL
joined_at DATETIME
removed_at DATETIME
removed_by BIGINT
created_at DATETIME NOT NULL
updated_at DATETIME NOT NULL
```

枚举：

```text
role: OWNER, EDITOR, VIEWER
status: ACTIVE, REMOVED
```

约束：

```text
UNIQUE(space_id, user_id)
```

说明：

- Space OWNER 也必须有 SpaceMember 记录。
- PERSONAL Space 的 owner 是唯一成员，角色为 OWNER。

### 7.4 UserSession / RefreshToken

表名：

```text
user_session
```

字段：

```text
id BIGINT PK AUTO_INCREMENT
user_id BIGINT NOT NULL
refresh_token_hash VARCHAR(255) NOT NULL UNIQUE
device_info VARCHAR(255)
ip_address VARCHAR(64)
user_agent VARCHAR(512)
expires_at DATETIME NOT NULL
revoked_at DATETIME
created_at DATETIME NOT NULL
updated_at DATETIME NOT NULL
```

说明：

- refresh token 只保存 hash，不保存明文。
- logout 吊销当前 session，logout-all 吊销当前用户全部未过期 session。
- 禁用用户后，所有未过期 session 必须失效。

---

## 8. JPA 实体清单

```text
user.model.User
user.model.UserSession
space.model.Space
space.model.SpaceMember
```

通用字段建议直接写在实体内，第一阶段不强制抽象 BaseEntity，避免过早抽象。

时间字段：

- `createdAt`
- `updatedAt`

可以使用：

```text
@CreationTimestamp
@UpdateTimestamp
```

---

## 9. Repository 清单

### UserRepository

```java
Optional<User> findByUsername(String username);
Optional<User> findByEmail(String email);
boolean existsByUsername(String username);
boolean existsByEmail(String email);
```

### SpaceRepository

```java
Optional<Space> findByIdAndStatus(Long id, SpaceStatus status);
List<Space> findByOwnerIdAndType(Long ownerId, SpaceType type);
boolean existsByOwnerIdAndType(Long ownerId, SpaceType type);
```

### SpaceMemberRepository

```java
Optional<SpaceMember> findBySpaceIdAndUserIdAndStatus(Long spaceId, Long userId, MemberStatus status);
List<SpaceMember> findByUserIdAndStatus(Long userId, MemberStatus status);
List<SpaceMember> findBySpaceIdAndStatus(Long spaceId, MemberStatus status);
boolean existsBySpaceIdAndUserIdAndStatus(Long spaceId, Long userId, MemberStatus status);
```

---

## 10. Service 设计

### 10.1 AuthService

职责：

- 注册。
- 登录。
- 生成 JWT。
- 刷新 token。
- 退出登录并吊销 refresh token。

方法：

```java
AuthResponse register(RegisterRequest request);
AuthResponse login(LoginRequest request);
AuthResponse refresh(RefreshTokenRequest request);
void logout(Long userId, LogoutRequest request);
void logoutAll(Long userId);
```

注册流程：

```text
校验 username 唯一
  ↓
校验 email 唯一
  ↓
BCrypt 加密密码
  ↓
创建 User
  ↓
创建 PERSONAL Space
  ↓
创建 SpaceMember OWNER
  ↓
生成 JWT
  ↓
返回用户信息和 token
```

注册必须使用事务。

### 10.2 UserService

职责：

- 查询当前用户。
- 查询用户基础信息。
- 更新当前用户资料。
- 修改密码。

方法：

```java
UserProfileResponse getMe(Long userId);
UserProfileResponse updateMe(Long userId, UpdateUserProfileRequest request);
void changePassword(Long userId, ChangePasswordRequest request);
User getRequiredUser(Long userId);
```

### 10.3 SpaceService

职责：

- 创建团队 Space。
- 查询当前用户可见 Space。
- 查询 Space 详情。
- 管理成员。

方法：

```java
SpaceResponse createTeamSpace(Long userId, CreateSpaceRequest request);
Space createPersonalSpace(Long userId);
List<SpaceResponse> listMySpaces(Long userId);
SpaceResponse getSpace(Long userId, Long spaceId);
MemberResponse addMember(Long operatorId, Long spaceId, AddMemberRequest request);
MemberResponse updateMemberRole(Long operatorId, Long spaceId, Long memberId, UpdateMemberRoleRequest request);
void removeMember(Long operatorId, Long spaceId, Long memberId);
```

`createPersonalSpace` 要求：

- 只能由注册流程或用户初始化修复任务调用。
- 必须和 `User` 创建处于同一事务，保证用户、PERSONAL Space、OWNER SpaceMember 同时成功或同时回滚。
- 创建前检查用户是否已有 PERSONAL Space，重复调用应幂等返回既有空间。

### 10.4 SpacePermissionService

职责：

- 统一判断 Space 权限。
- 后续所有团队侧服务都依赖它。

方法：

```java
boolean canViewSpace(Long userId, Long spaceId);
boolean canManageSpace(Long userId, Long spaceId);
boolean canUploadDocument(Long userId, Long spaceId);
boolean canEditWiki(Long userId, Long spaceId);
boolean canAskQuestion(Long userId, Long spaceId);

void requireViewSpace(Long userId, Long spaceId);
void requireManageSpace(Long userId, Long spaceId);
void requireUploadDocument(Long userId, Long spaceId);
void requireAskQuestion(Long userId, Long spaceId);
```

权限矩阵：

| Action | OWNER | EDITOR | VIEWER |
|---|---:|---:|---:|
| View Space | Y | Y | Y |
| Manage Space | Y | N | N |
| Upload Document | Y | Y | N |
| Edit Wiki | Y | Y | N |
| Ask Question | Y | Y | Y |

PERSONAL Space：

- 只有 owner 可以访问。
- owner 拥有全部权限。

TEAM Space：

- 只有 ACTIVE SpaceMember 可以访问。

---

## 11. Controller 与 API

### 11.1 AuthController

```http
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
POST /api/v1/auth/logout-all
```

RegisterRequest：

```json
{
  "username": "alice",
  "email": "alice@example.com",
  "password": "Password123!",
  "displayName": "Alice"
}
```

LoginRequest：

```json
{
  "usernameOrEmail": "alice",
  "password": "Password123!"
}
```

AuthResponse：

```json
{
  "accessToken": "jwt",
  "refreshToken": "refresh-token",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": {
    "id": 1,
    "username": "alice",
    "displayName": "Alice"
  }
}
```

### 11.2 UserController

```http
GET /api/v1/users/me
PUT /api/v1/users/me
PUT /api/v1/users/me/password
```

### 11.3 SpaceController

```http
POST /api/v1/spaces
GET  /api/v1/spaces
GET  /api/v1/spaces/{spaceId}
POST /api/v1/spaces/{spaceId}/members
GET  /api/v1/spaces/{spaceId}/members
PUT  /api/v1/spaces/{spaceId}/members/{memberId}/role
DELETE /api/v1/spaces/{spaceId}/members/{memberId}
```

CreateSpaceRequest：

```json
{
  "name": "团队知识库",
  "description": "项目资料和方案沉淀"
}
```

说明：

- `POST /api/v1/spaces` 第一阶段只创建 TEAM Space。
- PERSONAL Space 只在注册时自动创建。

AddMemberRequest：

```json
{
  "email": "bob@example.com",
  "role": "EDITOR"
}
```

UpdateMemberRoleRequest：

```json
{
  "role": "VIEWER"
}
```

---

## 12. 配置文件建议

`application.yml`：

```yaml
server:
  port: ${SERVER_PORT:8081}

spring:
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/noteweave?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true}
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    show-sql: false
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}

jwt:
  secret-key: ${JWT_SECRET_KEY:change-me-in-dev}
  access-token-expiration-seconds: ${JWT_ACCESS_TOKEN_EXPIRATION_SECONDS:900}
  refresh-token-expiration-seconds: ${JWT_REFRESH_TOKEN_EXPIRATION_SECONDS:1209600}

logging:
  level:
    org.springframework.security: INFO
    com.noteweave: INFO
```

本阶段必须启用 Flyway 或 Liquibase 管理迁移；`ddl-auto=update` 只能作为本地临时实验配置，不进入提交默认配置。

生产配置校验：

- `prod` / `staging` profile 不允许使用 `JWT_SECRET_KEY` 默认值。
- 推荐增加 `JwtPropertiesValidator` 或启动期检查，发现默认值、空值、长度不足时直接拒绝启动。

---

## 13. 第一阶段测试建议

### 13.1 单元测试

```text
AuthServiceTest
SpacePermissionServiceTest
SpaceServiceTest
```

重点覆盖：

- 注册创建用户。
- 注册自动创建 PERSONAL Space。
- 重复 username 失败。
- 登录密码错误失败。
- OWNER 权限。
- EDITOR 权限。
- VIEWER 权限。
- 非成员无权限。

### 13.2 接口测试

```text
AuthControllerTest
SpaceControllerTest
```

重点覆盖：

- 未登录访问 `/api/v1/spaces` 返回 401。
- 登录后可以访问 `/api/v1/users/me`。
- OWNER 可以添加成员。
- VIEWER 不能添加成员。

---

## 14. 验收清单

Phase 0 验收：

- `mvn test` 可以执行。
- 应用可以启动。
- `/actuator/health` 或简单健康检查接口可访问。
- MySQL 连接正常。
- Redis 连接正常。
- 全局异常返回统一结构。

Phase 1 验收：

- 用户可以注册。
- 注册后自动创建 PERSONAL Space。
- 注册后自动创建 PERSONAL SpaceMember OWNER。
- 用户可以登录并获得 JWT。
- 用户可以刷新 token。
- 用户可以退出登录，退出后 refresh token 不可再次使用。
- 用户可以更新个人资料和修改密码。
- JWT 可以访问受保护接口。
- 用户可以创建 TEAM Space。
- TEAM Space 创建者自动成为 OWNER。
- OWNER 可以添加 EDITOR / VIEWER。
- OWNER 可以调整成员角色。
- OWNER 可以移除成员。
- 非 OWNER 不能管理成员。
- 非成员不能查看 TEAM Space。
- `SpacePermissionService` 权限矩阵测试通过。

---

## 15. 第一阶段不做的事

- 不做文件上传。
- 不做 Kafka。
- 不做 MinIO。
- 不做 Elasticsearch。
- 不做 RAG。
- 不做 WebSocket。
- 不做个人 ResearchProject。
- 不做 Artifact。
- 不做复杂组织、多租户和细粒度文档权限。

这些能力从 Phase 2 开始接入。

---

## 16. 实现顺序建议

```text
1. 创建 Spring Boot 项目
2. 添加 Maven 依赖和 Flyway/Liquibase
3. 添加 application.yml
4. 实现 ApiResponse / ErrorCode / BusinessException / GlobalExceptionHandler
5. 实现 User 实体和 UserRepository
6. 实现 Space / SpaceMember 实体和 Repository
7. 实现 JwtService / RefreshTokenService / JwtAuthenticationFilter / SecurityConfig
8. 实现 AuthService
9. 实现 AuthController
10. 实现 CurrentUserProvider
11. 实现 UserController
12. 实现 SpacePermissionService
13. 实现 SpaceService
14. 实现 SpaceController
15. 补单元测试和接口测试
```

---

## 17. 后续衔接点

Phase 2 文件上传会依赖：

```text
SpacePermissionService.canUploadDocument
Space.id
User.id
```

Phase 4 团队 RAG 会依赖：

```text
SpacePermissionService.canAskQuestion
SpaceMember
ChatSession.space_id
Document.space_id
DocumentChunk.space_id
```

Phase 5 WebSocket 会话底座会依赖：

```text
JWT
CurrentUserProvider
ChatSession
SpacePermissionService
```

因此第一阶段必须保证：

- 用户身份稳定。
- Space 归属稳定。
- 权限判断入口稳定。


