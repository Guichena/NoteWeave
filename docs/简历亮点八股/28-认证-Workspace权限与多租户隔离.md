# 认证、Workspace 权限与多租户隔离

> 本文是独立 A 档安全功能，默认讲第 13 节的 4 到 5 分钟回答；认证/授权/资源归属、ACL Version 与撤权、跨 Workspace Evidence 三组重点可继续展开。`[当前实现]` 只说明安全机制存在，`[已测-模拟]` 只说明固定对抗用例通过，SSO、MFA、mTLS、Token Family 风险处置和完整合规删除仍是 `[目标设计]` 或 `[生产待验证]`。

> 定位：大厂面试官不会满足于“用了 Token 和 RBAC”。这篇说明登录态怎样轮换、权限怎样映射到业务动作、缓存怎样失效，以及只拿到一个资源 ID 时怎样阻止跨 Workspace 越权。

## 1. 一句话定位

系统采用随机 Access Token 和轮换 Refresh Token 管理会话，只在数据库保存 Token 哈希；Workspace 使用 OWNER、EDITOR、VIEWER 到细粒度 Permission 的映射。所有业务资源先反查所属 Workspace，再校验当前用户、成员状态、ACL Version 和所需 Permission，避免只信任请求路径中的 `workspaceId`。

## 2. 威胁模型

学校资料工作台至少面对以下风险：

1. 撞库或暴力登录。
2. 数据库泄露后 Session Token 被直接复用。
3. Refresh Token 被窃取后重复刷新。
4. VIEWER 调用写接口。
5. A Workspace 成员猜到 B Workspace 的 Source、Message、Task ID 后越权访问。
6. 成员被移除后，ACL 缓存仍继续放行。
7. 内部 Worker 接口被用户侧 Token 调用。
8. 检索投影因过滤配置错误返回其他 Workspace 的 Evidence。

安全设计要逐个关闭失败窗口，不能用一句“有鉴权”概括。

## 3. 认证链路

### 3.1 注册

注册对用户名、邮箱和来源地址做限流维度，密码经 PasswordHasher 处理。用户名或邮箱冲突返回统一注册冲突，不泄露过多账户状态。成功后签发 Access 与 Refresh，并创建 `user_session`。

### 3.2 登录

登录标识可匹配用户名或邮箱。不存在的用户仍使用固定无效密码哈希执行一次验证，降低通过响应时间枚举账户的风险。失败会进入 Login Throttle 和 Security Event，成功后清理或更新对应尝试状态。

### 3.3 Session

Token 由 `SecureRandom` 生成 32 字节随机值并做 URL-safe Base64。数据库不保存明文，只保存 SHA-256 哈希。默认 Access 生命周期为 15 分钟、Refresh 为 30 天，真实部署应再由配置和安全策略决定。

这里 SHA-256 适合高熵随机 Token，因为攻击者无法像弱密码一样枚举；用户密码仍必须使用有成本的 Password Hash，不能混用。

### 3.4 Refresh Rotation

Refresh 时先用旧 Token 哈希读取 ACTIVE Session，再生成一对新 Token，并以 `where id = ? and refresh_token_hash = ?` 条件更新。两个并发刷新只有一个能更新成功，另一个得到 `REFRESH_TOKEN_REPLAYED`。

这能阻止旧 Refresh Token 在轮换后继续使用，但当前语义主要是拒绝单次重放。更强的生产策略可以在检测到重放时撤销整个 Token Family，并告警用户，代价是需要维护 Family 和设备信息。

### 3.5 Logout

Logout 将 Session 标为 REVOKED，并清空 Access/Refresh Hash。重复 Logout 是收敛操作，不应重新激活会话。

## 4. 认证与授权为什么必须分开

认证回答“当前是谁”，授权回答“这个用户是否能对这个 Workspace 的这个资源执行这个动作”。Access Token 有效不代表拥有任何 Workspace 权限。

```text
Bearer Token
  -> ApiRequestIdentityFilter
  -> CurrentUserProvider
  -> Controller / Service
  -> WorkspaceAccessGuard
       -> Workspace ACTIVE?
       -> Membership ACTIVE?
       -> ACL Version current?
       -> Role grants Permission?
       -> Resource belongs to this Workspace?
```

## 5. Role 与 Permission

| Role | Permission | 典型用户 |
| --- | --- | --- |
| OWNER | 全部 Permission | 课程或知识库负责人 |
| EDITOR | READ、SOURCE_WRITE、ANSWER_RUN、KNOWLEDGE_WRITE、EXECUTION_OPERATE | 教师、助教、资料维护者 |
| VIEWER | WORKSPACE_READ | 学生或只读访客 |

细粒度 Permission 包括：

- `WORKSPACE_READ`
- `SOURCE_WRITE`
- `ANSWER_RUN`
- `KNOWLEDGE_WRITE`
- `MEMORY_REVIEW`
- `EXECUTION_OPERATE`
- `WORKSPACE_ADMIN`

Role 便于管理，Permission 便于代码表达。业务 Service 不应散落 `if role == OWNER`，而应声明自己需要什么能力。这样以后增加 Reviewer 角色时，不需要改所有接口。

当前一个值得说明的设计点是 EDITOR 不包含 `MEMORY_REVIEW` 和 `WORKSPACE_ADMIN`。长期 Memory 会影响后续运行行为，权限风险高于普通资料写入。

## 6. WorkspaceAccessGuard 的四层校验

### 6.1 Workspace 状态

先查询 Workspace 是否存在且为 ACTIVE。冻结或归档 Workspace 不应因为旧成员缓存而继续访问。

### 6.2 Membership 状态

成员和用户都必须 ACTIVE。只检查 `workspace.owner_id` 无法支持协作，也无法处理成员禁用。

### 6.3 Role 到 Permission

从 `workspace_member.role` 解析为枚举，未知 Role 默认拒绝。失败关闭比把未知值当 VIEWER 更容易暴露脏数据。

### 6.4 Resource Ownership

对于只携带 `messageId`、`taskId`、`itemId` 或 `uploadId` 的接口，Guard 先从资源表查询 `workspace_id`，再执行 Permission 校验。客户端传入的 Workspace 只是路由参数，不是可信归属证明。

## 7. ACL Cache 与版本失效

每次鉴权都查 Membership 会增加数据库压力，所以缓存键包含 Workspace、User 和 `aclVersion`。成员增删或角色变化时，在同一业务事务中提升 Workspace ACL Version。后续请求读取到新 Version，就不会命中旧缓存。

这比单纯 TTL 更强：TTL 只能保证“最多错误多久”，Version 可以让权限变化立即切断旧 Key。TTL 仍可用于回收旧版本缓存。

需要主动说清的失败窗口：

- 如果 ACL Version 更新与 Membership 修改不在同一事务，可能短暂读到新成员配旧版本或旧成员配新版本。
- 如果缓存或数据库异常，高风险读取应 Fail Closed。
- 缓存只存授权计算结果，不能成为成员关系真源。

## 8. 跨 Workspace 攻击演练

场景：学生 Alice 是课程 A 的 VIEWER，拿到了课程 B 某条 `messageId`，尝试调用保存为 Source 接口。

正确链路：

1. 身份过滤器确认 Alice 的 Session 有效。
2. Service 根据 `messageId` 反查该 Message 属于 Workspace B。
3. Guard 要求 `SOURCE_WRITE`。
4. Alice 在 Workspace B 没有 ACTIVE Membership，拒绝请求。
5. 即使她把 URL 中的 `workspaceId` 改成课程 A，也不能改变资源真实归属。
6. 记录 Access Denied 指标，但对客户端避免泄露课程 B 的详细元数据。

另一个检索攻击：Workspace A 的 Query 因投影 Bug 返回了 B 的 Chunk。检索阶段应带 Workspace Filter，Evidence 进入 Prompt 前还要通过 Ownership Guard 做二次校验。这里宁可少一条证据，也不能跨租户返回。

## 9. Controller、Service、Repository 多层防线

多层并不意味着每层复制同一段逻辑：

| 层 | 职责 |
| --- | --- |
| Filter | 解析凭证，建立 User 和 Request Context |
| Controller | 参数约束、用户 API 与 Internal API 分区 |
| Application Service | 声明业务动作需要的 Permission |
| Access Guard | 统一 Role、Membership、Workspace 和资源归属判断 |
| Repository / SQL | 查询和更新始终带 Workspace 或资源主键条件 |
| Projection Guard | 检索结果返回后再次校验归属 |
| Audit / Metrics | 记录拒绝、限流和异常模式 |

Repository 条件不能替代授权，但能缩小上层遗漏时的影响范围。

## 10. Internal Worker 边界

用户 API 位于 `/api/v2`，Worker 和运维 API 位于 `/internal`。Internal 并不等于“路径看起来隐蔽”，还需要独立凭证、网络策略、最小能力和回调 Payload 绑定。Worker 只应获取当前 Task 所需的快照和写回能力，不能拿一个系统 Token 横向读取所有 Workspace。

面试包装可以说“内部面和用户面分离，并按 Task Capability 收敛权限”。如果被追问到 mTLS、密钥轮换或服务网格，应说明它们属于生产部署层增强，不能把本地 Compose 拓扑说成已经完成零信任网络。

## 11. 安全指标

| 指标 | 计算或来源 | 告诉我们什么 |
| --- | --- | --- |
| Login Failure Rate | 失败登录 / 登录尝试 | 攻击、密码问题或客户端异常 |
| Throttle Hit Rate | 被限流请求 / 认证请求 | 防护触发强度，不能单独证明攻击 |
| Refresh Replay Count | `REFRESH_TOKEN_REPLAYED` 次数 | 并发刷新 Bug 或 Token 窃取信号 |
| Access Denied Rate | 按 Permission、接口统计 403 | 权限配置问题或越权尝试 |
| ACL DB Load | Cache Miss 后的 DB Timer | 缓存效果和鉴权成本 |
| Permission Revocation Lag | 成员变更提交到旧权限最后一次成功的时间 | ACL Version 传播是否可靠 |
| Cross-Workspace Leakage | 对抗用例中越界 Evidence/Resource 数 | 必须为 0 的安全不变量 |
| Session Revocation Lag | Logout 到 Token 最后可用时间 | 撤销是否即时 |

安全不变量适合用故障注入和对抗测试表达，不适合用“成功率 99%”包装。跨 Workspace 泄露出现一次就应阻断发布。

## 12. 面试官连续追问

### Q1：为什么随机 Token，不用 JWT

数据库 Session 便于立即 Logout、轮换 Refresh 和统一撤销，适合当前单体加 Worker 规模。JWT 可减少每次查库，但即时撤销、权限变化和密钥轮换更复杂。未来若服务拆分，可使用短期 JWT 加集中 Session/Revocation，但不能只为了“无状态”牺牲控制面。

### Q2：Token Hash 用 SHA-256 安全吗

对 256 bit 随机 Token 足够，因为输入不可枚举；对人类密码不够，密码使用慢哈希。两类凭证的熵模型不同。

### Q3：两个线程同时 Refresh 会怎样

都可能先读到 Session，但条件更新包含旧 Refresh Hash。第一个轮换成功，第二个更新行数为 0 并返回 Replay。事务和条件更新共同完成仲裁。

### Q4：ACL Cache 会不会导致越权

缓存 Key 带 `aclVersion`，成员变化提升版本，旧 Key 不再命中。高风险故障 Fail Closed。还应测试版本提升与成员更新处于同一事务，并观测 Revocation Lag。

### Q5：为什么不是 ABAC

当前业务主要按 Workspace 成员角色和动作授权，RBAC 加 Permission 更容易维护。复杂到按课程、资料敏感级别、时间段和设备策略判断时，再引入 ABAC。过早使用通用策略引擎会增加解释和测试成本。

### Q6：返回 403 还是 404

对已确认存在但权限不足的 Workspace 操作可返回 403；对资源 ID 探测，可统一为 Not Found 或模糊错误减少枚举。项目当前部分 Guard 区分资源不存在与 Workspace Access Denied，生产化可按敏感度统一外部错误，同时保留内部审计原因。

### Q7：如何防 CSRF 和 XSS 窃取 Token

当前 Bearer Token 由客户端显式发送，CSRF 风险低于自动携带 Cookie，但 XSS 和本地存储泄露仍需防。生产 Web 可选择 HttpOnly、Secure、SameSite Cookie 配合 CSRF Token，或使用内存 Access Token 加安全 Refresh Cookie。需要结合部署方式决策，不能只靠后端 Token 逻辑。

## 13. 简历与口述版本

### 简历表达

> 设计 Workspace 多租户权限链路，采用高熵 Token 哈希存储、Refresh Rotation 与并发重放检测；将 OWNER/EDITOR/VIEWER 映射为细粒度 Permission，并以 ACL Version Cache 和资源反查鉴权阻断成员变更后的旧权限与跨 Workspace IDOR。

### 60 秒回答

> 认证层用随机 Access 和 Refresh Token，数据库只存哈希。Refresh 每次轮换，更新条件带旧 Hash，所以两个并发刷新只有一个成功。授权层不直接判断角色，而是让 Service 声明 SOURCE_WRITE、ANSWER_RUN 等 Permission。Guard 查询 Workspace 状态和 ACL Version，再从 Membership 解析角色。成员变化会提升 ACL Version，使旧缓存立即失效。最关键的是 Message、Task、Upload 这类只传资源 ID 的接口会先反查真实 Workspace，不能信任客户端路径参数；检索结果进入 Prompt 前也再次校验归属。

### 4 到 5 分钟标准回答

这个项目最早服务学校资料时，权限模型只有“登录用户可以操作自己的资料”。加入课程 Workspace、教师、助教、学生、Research Worker 和 Artifact Worker 后，风险不再只是密码泄露。一个有效 Token 可能访问错误课程，一个被移除的成员可能继续命中旧缓存，一个用户也可能拿着别人的 `messageId` 绕过 URL 中的 `workspaceId`。所以我把安全拆成凭证生命周期、Workspace 授权、资源归属、投影二次校验和内部执行面五层。

凭证层使用高熵随机 Access/Refresh Token，数据库只保存 SHA-256 Hash。这里 SHA-256 只适合 256 bit 随机 Token，用户密码仍使用慢哈希。Access 默认短期有效，Refresh 每次使用都轮换：服务先读取旧 Hash，再生成新 Token，更新条件仍包含旧 Refresh Hash。两个线程并发刷新时只有一个能更新成功，另一个得到 Replay，避免两个新 Session 分叉。登录时，即使账号不存在也执行固定无效密码哈希，减少通过响应时间枚举账号；登录和注册另有账号、地址维度的限流与安全事件。

认证成功以后才进入授权。项目没有让业务代码到处写 `role == OWNER`，而是把 OWNER、EDITOR、VIEWER 映射为 `WORKSPACE_READ`、`SOURCE_WRITE`、`ANSWER_RUN`、`KNOWLEDGE_WRITE`、`MEMORY_REVIEW`、`EXECUTION_OPERATE` 等 Permission。Application Service 声明需要什么动作，`WorkspaceAccessGuard` 检查 Workspace、用户和 Membership 都是 ACTIVE，再判断 Role 是否授予该 Permission。这样新增 Reviewer 或限制 Memory 审批时，修改权限映射和测试即可，不用改每个 Controller。

成员撤权的难点是缓存。ACL Cache Key 包含 Workspace、User 和 `aclVersion`。成员删除或角色变化时，Membership 和 Version 必须在同一个 MySQL 事务提交；后续请求读到新 Version，自然不会命中旧 Key。正缓存默认 300 秒、负缓存 30 秒并带 Jitter，这些只是当前配置。Redis 异常时 Cache 返回 Miss，Guard 回源数据库，而不是沿用无法确认的新权限；数据库异常会让校验失败，高风险操作不做 Fail Open。

最关键的一层是防 IDOR。只检查 URL 里的 Workspace 没有意义，因为客户端可以同时伪造 Workspace 和资源 ID。Message、Task、Conversation、Upload、Knowledge Item 等接口先从资源真源反查 `workspace_id`，再做 Permission 校验。例如课程 A 的学生拿到课程 B 的 `messageId`，即使把 URL 改成 A，反查结果仍是 B，没有 B 的 Membership 就会被拒绝。Repository 的 Workspace 条件是额外收缩面，不能替代授权。

RAG 还多一个投影风险。ES 或向量检索即使因为索引配置错误返回其他 Workspace 的 Chunk，Evidence 进入 Prompt 前仍要做 Ownership Guard；安全不变量是跨 Workspace Evidence 为零，而不是“泄露率低于 1%”。这里宁可拒答或少召回，也不能把别的课程资料交给模型。Source 删除、ACL 变化和投影更新之间存在最终一致窗口时，高风险读取需要依据当前业务真源再次确认。

内部 Worker 不能拿用户 Token，也不能因为路径在 `/internal` 就视为可信。Research 和 Artifact 使用独立内部凭证，生产设计还应把调用约束到 Task、Workspace、Payload Digest、Capability 和短期有效期。用户面、控制面和执行面分开，Worker 只获取当前任务需要的冻结快照和写回能力。mTLS、服务身份轮换、SSO、MFA 和 Token Family 全量撤销属于生产增强，不能说成本地 Compose 已经实现的零信任网络。

观测上看 Login Failure、Throttle、Refresh Replay、Access Denied、ACL Cache/DB Load、撤权延迟和跨租户对抗用例。安全指标不能只看比例，泄露、越权写入和旧 Worker 绕过 Fencing 都是单次阻断项。当前源码能证明 Token Hash、Refresh 条件轮换、登录注册限流、Role/Permission、ACL Version Cache、资源反查 Guard 和部分安全指标；浏览器 Token 存储策略、Token Family、MFA、mTLS、密钥托管和正式攻防审计仍需按部署环境补齐。

## 14. 当前边界与禁区

- 可以说已实现 Session Hash、Refresh Rotation、登录注册限流、Security Event、Workspace Role/Permission、ACL Version Cache 和资源归属 Guard。
- 可以说对学校课程 Workspace 做过跨租户对抗演练，但只有保存了测试记录才能报具体用例数。
- 不要说已经完成 SSO、MFA、mTLS、跨地域密钥轮换或完整 GDPR 删除。
- 不要把 Compose 网络描述成生产零信任网络。
- 不要只说“用了 RBAC 就安全”，应主动讲 IDOR、缓存撤权和 Projection 二次校验。

## 15. 源码与迁移导航

| 主题 | 入口 |
| --- | --- |
| 认证 API | `security/AuthController` |
| Session、轮换和注销 | `security/AuthService`、`TokenHasher`、`PasswordHasher` |
| 限流与事件 | `AuthLoginRateLimiter`、`AuthRegistrationRateLimiter` |
| 身份上下文 | `ApiRequestIdentityFilter`、`CurrentUserProvider` |
| 权限模型 | `WorkspaceRole`、`WorkspacePermission` |
| 统一鉴权 | `WorkspaceAccessGuard`、`WorkspaceAclCache` |
| 成员管理 | `WorkspaceController`、`WorkspaceMembershipService` |
| Schema | `V086`、`V089`、`V093`、`V094` |

## 16. 面试前自检

1. 为什么 Access Token 哈希可用 SHA-256，而密码不行。
2. Refresh Rotation 如何处理并发，Token Family 还缺什么。
3. Role 和 Permission 为什么分开。
4. 只传 `messageId` 的接口怎样防 IDOR。
5. ACL Version 如何让成员移除立即生效。
6. Redis 或数据库异常时授权应该 Fail Open 还是 Fail Closed。
7. 检索层为什么需要二次 Ownership Guard。
8. 当前安全能力和生产身份平台之间还有哪些边界。

## 17. 重点知识点：认证、授权和资源归属为什么是三次判断

### 3 分钟回答

认证只证明请求携带了一个当前有效的用户凭证。它不能证明用户属于某个 Workspace，更不能证明一个资源 ID 属于 URL 声称的 Workspace。把三者合在 Controller 里做一次判断，最容易出现 IDOR：攻击者保留自己的合法 Token，只替换 `messageId`、`taskId` 或 `uploadId`，如果后端只检查路径中的 Workspace，就可能操作别人的资源。

NoteWeave 先由身份过滤器把 Token Hash 映射到 Active Session 和 User；业务 Service 再声明需要的 Permission；资源型接口先从 MySQL 反查真实 `workspace_id`，最后由 Guard 读取 Workspace 状态、ACL Version 和 Active Membership。Role 只负责映射 Permission，资源归属由真源决定。Repository 查询继续带 Workspace 条件，作为纵深防护，但不能把 SQL 条件当成唯一权限系统。

检索链路还要再做一次归属判断。检索请求本身带 Workspace Filter，但 ES、向量索引和缓存都是投影，可能因旧 Catalog、删除延迟或配置错误返回越界 Chunk。Evidence 进入 Prompt 前根据 Source/Snapshot 真源重新验证 Workspace 和可见版本。这样即使召回层出错，越界数据也不会被模型看到。代价是多一次批量归属查询和降级拒答，所以实现时要批量校验，避免逐条 N+1。

这套设计对应零信任中的“每次访问都验证”，但不意味着每层都重复同一逻辑。Filter 管身份，Service 管动作，Guard 管成员和权限，Ownership Query 管资源归属，Projection Guard 管派生数据。每层关闭不同失败窗口，才能解释为什么“已经登录”仍不等于安全。

### 二阶追问：为什么不把 Workspace ID 写进 Token

一个用户可以属于多个 Workspace，角色还会变化。把所有 Workspace 权限塞进长生命周期 Token 会造成体积、撤权和陈旧问题。可以把短期权限摘要放进 Token 优化读，但高风险操作仍要校验当前 ACL Version；当前项目选择数据库 Session 与 ACL Cache，优先保证撤销可控。

### 二阶追问：批量 Ownership Guard 会不会拖慢 RAG

只按返回 Candidate 的 Source/Snapshot ID 去重后批量查询，不对所有索引结果逐条查库。延迟进入检索护栏指标。若数据库不可用，高风险证据关闭而不是跳过校验；可以使用带版本的受信投影优化，但投影必须能证明来自当前 ACL/Catalog Version。

## 18. 重点知识点：ACL Version、缓存和撤权窗口

### 3 分钟回答

纯 TTL 权限缓存的问题是成员删除后仍可能在 TTL 内继续访问。主动删除 Key 又面临多实例、网络失败和“不知道该删哪些派生 Key”。Versioned Key 的做法是把 `aclVersion` 作为授权结果身份的一部分。Membership 变化时提升 Version，后续请求读取新 Version 后只会查询新 Key，旧缓存即使还存在也无法命中，最后由 TTL 回收。

关键不变量是 Membership 修改和 Version 提升必须同事务。若先移除成员再异步提升 Version，旧 Key 在窗口内仍能放行；若先提升 Version 但成员事务回滚，新 Key 又可能重新加载错误状态。Guard 每次读取 Workspace 当前状态和 Version，Cache Miss 时查询 Active Membership 与 Active User，未知 Role 和无成员都缓存为短期 Denied。负缓存更短，避免刚邀请的成员长期被旧拒绝结果挡住。

缓存故障的策略也要分清。Redis 读异常时当前 `WorkspaceAclCache` 记 Error 并返回 Miss，Guard 继续查 MySQL，不会因为 Redis 挂了就让所有合法用户永久不可用，也不会拿旧值盲目放行。MySQL 权限真源不可用时，校验异常让请求失败。更严格的生产方案可以按操作风险分层：公开只读能力允许有限缓存证据，高风险写入、Memory 审批和成员管理必须 Fail Closed。

撤权是否真的即时不能只看代码，要测 `Permission Revocation Lag`：从成员变更事务提交，到旧身份最后一次成功访问的时间。对抗测试覆盖正缓存命中后撤权、并发请求、Redis 故障、旧 Version Key 残留和检索投影晚到。安全目标是越权成功为零，性能指标则看 Cache Hit、DB Load 和鉴权 P95。

### 二阶追问：ACL Version 会不会无限增长或溢出

它是单调版本，不要求连续。数据库使用足够宽的整数并监控异常跳变，现实更新频率下不会成为近期瓶颈。缓存 Key 的旧版本由 TTL 清理；若成员变化极频繁，需要限制管理操作并检查是否存在自动化抖动。

### 二阶追问：为什么 Redis 异常回源数据库不算 Fail Open

Fail Open 是无法确认权限时仍放行。回源 MySQL 是换用权威真源继续确认；只有 MySQL 查询明确得到 Active Membership 和 Permission 才放行。MySQL 也失败时请求失败，因此安全语义仍是关闭访问。

## 19. 安全事件的二阶追问

### Refresh Replay 一定说明 Token 被盗吗

不一定。浏览器多标签页、客户端并发刷新或网络重试也可能触发。当前实现能拒绝第二次旧 Hash 更新，但还不能区分正常竞争与盗用。生产方案记录 Session Family、设备、地址和时间窗口；低风险并发只要求重新认证，高风险异常撤销整个 Family 并发安全通知。

### XSS、CSRF 和 Token 存储怎样一起考虑

Bearer Token 不会像 Cookie 自动随跨站请求发送，因此传统 CSRF 面较小，但存在 XSS 或本地存储窃取风险。HttpOnly Refresh Cookie 能降低脚本读取，但需要 SameSite、Secure 和 CSRF 防护；Access Token 放内存可缩短暴露窗口，刷新后页面恢复更复杂。选择取决于真实前端部署，当前后端 Session 设计不能替浏览器安全策略背书。

### Prompt Injection 为什么也是权限问题

资料中的恶意文本可能诱导 Agent 调用工具或泄露其他上下文。模型输出不能直接成为授权依据；工具调用仍绑定当前 User/Task、Workspace、Capability、输入快照和预算。检索到文本只赋予“可读内容”身份，不赋予它执行命令的权力。

### 发现一次跨 Workspace Evidence 应怎样处理

立即停止相关检索策略或关闭高风险能力，保留 Source/Snapshot、Query、Catalog Version 和 Evidence Manifest，检查是否已经进入 Prompt、回答、Memory 或 Artifact。除修复过滤条件，还要撤销派生结果、重建受影响投影并扩大对抗用例。这个事件不能用总体泄露率低来降级处理。

## 20. 安全验收、行业映射和数字答辩

安全数字不能只报拦截了多少请求。拦截量受攻击流量影响，真正承重的是关键不变量和撤权窗口。跨租户泄露、旧 Refresh 重放成功、已撤权用户继续访问等事件只要出现一次，就应阻断发布。

| 威胁 | 对抗 Case | 通过条件 | 量化方式 |
| --- | --- | --- | --- |
| BOLA/IDOR | 合法用户替换 Source、Message、Task、Upload ID | 外部返回稳定拒绝，内部记录真实原因 | `越权成功 Case / 对抗 Case`，目标为 0 |
| 跨 Workspace RAG | 索引注入其他 Workspace Chunk | Evidence 进入 Prompt 前被 Ownership Guard 拒绝 | `越界 Evidence 数` 与受影响 Run 数 |
| Refresh Replay | 两个并发请求使用同一旧 Refresh Hash | 只有一次 Rotation 成功，另一次进入 Replay | `成功旧 Hash 更新数` 必须为 1 |
| ACL 撤权 | 正缓存命中后移除成员并发访问 | 新 Version 下不能命中旧授权 | Permission Revocation Lag P95/P99 |
| 缓存故障 | Redis 超时、旧 Key 残留、负缓存 | 回源真源或明确失败，不以未知状态放行 | Fail-open Success 数必须为 0 |
| Prompt Injection | 资料要求忽略规则并调用越权工具 | 文本不扩大 Capability 或 Workspace Scope | 越权工具调用与跨域写回数必须为 0 |
| Internal API 滥用 | 普通 Token、错误 Audience、旧 Delivery 调内部端点 | 认证、Task 和 Fencing 任一不符即拒绝 | 按拒绝原因分桶，不只看 401 总数 |

`[行业参考]` OWASP API Security 将对象级授权失效列为 BOLA 风险，OWASP ASVS 和 Session Management 指南强调会话随机性、轮换、注销与服务端验证。NoteWeave 的资源反查、ACL Version、Refresh Rotation 和内部 Audience 可以映射到这些威胁，但“符合部分控制点”不等于通过正式认证或渗透测试。

`[演练假设]` 可以准备 `N=240` 个安全 Case，包括 `80` 个 IDOR、`40` 个跨 Workspace Evidence、`40` 个 Refresh 竞争、`40` 个撤权窗口和 `40` 个内部 Token 错配。理想结果是越权成功 `0/240`，撤权 P99 `350 ms`，两个并发 Refresh 中旧 Hash 成功更新始终为 `1`。这些数字只有在保存用例清单、环境、请求结果和审计事件后才能进入简历，`0/240` 只能表述为“固定对抗集未观察到泄露”。

### 二阶追问：为什么授权查询变慢不能直接加长缓存 TTL

TTL 只影响性能窗口，不能承担撤权正确性。加长 TTL 会扩大旧授权残留时间，尤其是没有 ACL Version 的 Key。优先做批量 Ownership 查询、索引优化、Versioned Key 和热点缓存，监控 Cache Hit、DB P95 与 Revocation Lag。性能优化不能改变高风险操作 Fail Closed 的原则。

### 二阶追问：跨租户结果在模型生成后被过滤，还算泄露吗

算。只要越界 Evidence 已进入模型上下文，内容就可能影响输出、日志、Trace、Memory 或外部 Provider。安全边界必须放在 Prompt 之前；生成后过滤只能作为最后防线，不能证明数据没有离开租户边界。发现后还要检查派生结果和 Provider 数据保留策略。
