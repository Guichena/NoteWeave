# 文件：07_多租户RBAC与安全设计.md

## 1. 本主题面试官想考什么

这个主题考察企业级系统的底线能力：认证、租户隔离、角色权限、资源归属、审计、凭据加密、SSRF 防护、文件安全、MCP/Agent 安全和沙箱隔离。大厂面试会重点看你是否只关注功能，还是能意识到“知识库系统里数据就是资产”。

基于仓库可确认的信息：项目支持 JWT、API Key、多租户、RBAC；角色包括 viewer、contributor、admin、owner；`router/rbac.go` 明确区分 role-only guard 和 ownership guard；`docs/rbac.md` 描述 `tenant_members` 和 `audit_logs`；项目对 API key、vector store 凭据等敏感字段使用 AES-GCM 加密；URL 导入有 SSRF 校验；MCP stdio transport 被禁用。  
需要你补充的信息：你是否参与过 RBAC 设计、安全修复、审计日志或权限 bug 排查。

## 2. 高频问题清单

### 基础问题

- 项目如何做多租户隔离？
- RBAC 角色有哪些？权限边界是什么？
- API Key 和 JWT 分别适合什么场景？
- 敏感凭据如何存储？

### 进阶问题

- Contributor 为什么只能改自己创建的资源？
- Admin 和 Owner 有什么区别？
- 共享知识库如何避免越权访问？
- URL 导入为什么需要 SSRF 防护？

### 深挖追问

- 如果一个用户拿到别人的 knowledge_id，能不能访问？
- API Key 调用怎么映射租户身份？
- RBAC 关闭时为什么要保留日志模式？
- 资源删除、分享、复制时权限如何判断？

### 压力追问

- 你们系统最可能出现的越权点在哪里？
- CORS 配置过宽会有什么风险？
- Agent/MCP 如何防 prompt injection？
- 如果加密密钥丢了，历史凭据还能恢复吗？

## 3. 问答与讲解

### Q1：WeKnora 如何做多租户和 RBAC？

#### 面试官为什么问

企业知识库最核心的风险是数据越权。面试官想知道你是否理解租户隔离和权限矩阵，而不是只会说“加了 JWT”。

#### 先给结论

WeKnora 的权限不是简单登录态，而是租户级 RBAC 加资源 ownership。

#### 回答思路

1. 请求先通过认证，解析 JWT/API Key。
2. 上下文中注入 tenant/user 信息。
3. RBAC 基于 `tenant_members` 的 role 做权限判断。
4. 资源操作还要结合 creator ownership。
5. 越权访问记录 audit log。

#### 结合我的项目怎么答

`docs/rbac.md` 里定义角色：viewer、contributor、admin、owner。`router/rbac.go` 说明：

- Viewer：读和问答。
- Contributor：可以创建资源，能管理自己创建的 KB/Agent。
- Admin：管理租户内大多数资源和基础设施配置。
- Owner：拥有 Admin 能力，并可删除租户，不能被其他 Admin 降级。

`rbacGuards` 中有 `Viewer()`、`Contributor()`、`Admin()`、`Owner()` 这样的角色 guard，也有 `OwnedKBOrAdmin()`、`OwnedAgentOrAdmin()`、`OwnedKnowledgeKBOrAdmin()`、`OwnedChunkKBOrAdmin()` 这样的资源归属 guard。

#### 技术原理 / 链路设计讲解

RBAC 只回答“这个用户在租户中是什么角色”，但不能回答“这个资源是不是他创建的”。因此项目又引入 ownership guard。典型规则是：

```text
创建资源：Contributor+
修改自己创建的 KB/Agent/Knowledge/Chunk/Wiki：资源 owner 或 Admin+
修改租户级基础设施：Admin+
删除租户：Owner
读取：Viewer+，但还要满足 KB read access
```

这种设计能实现“Contributor 在自己的资源上像 owner，在别人资源上像只读用户”的体验。

#### 技术栈特点与选型理由

Gin middleware 适合在路由层统一做权限校验；GORM/DB 保存 `tenant_members` 和 `audit_logs`；配置项 `tenant.enable_rbac` 支持灰度开启。

#### 可直接复述的面试回答

WeKnora 的权限不是简单登录态，而是租户级 RBAC 加资源 ownership。请求进来先通过 JWT 或 API Key 认证，拿到 tenant 和 user，再根据 tenant_members 表里的角色判断权限。角色分 viewer、contributor、admin、owner。读操作通常 viewer 就可以，创建 KB 或 Agent 要 contributor，租户级模型、向量库、IM、MCP 配置要 admin，删除租户要 owner。对于 KB、Agent、Knowledge、Chunk、Wiki 这类有创建者的资源，Contributor 只能修改自己创建的资源，别人创建的资源需要 Admin+ 才能改。拒绝访问会记录 audit log，便于审计。

#### 常见追问

- API Key 调用算哪个角色？
- shared KB 的读写怎么判断？
- Owner 为什么不能被 Admin 降级？

#### 常见坑

不要只说“每张表都有 tenant_id”。tenant_id 是隔离基础，但权限判断还需要角色、资源归属和共享关系。

---

### Q2：如何防止知识库越权访问？

#### 面试官为什么问

这是 RBAC 的真实落地点。很多系统有认证但子资源接口漏鉴权，比如通过 chunk_id、knowledge_id 直接访问。

#### 先给结论

防越权不能只靠前端隐藏按钮，也不能只在列表接口过滤。

#### 回答思路

1. 所有资源查询都要带 tenant 范围。
2. 子资源要回溯父资源权限。
3. 路由层使用 KBAccessRead/Write guard。
4. Contributor 修改子资源要检查父 KB creator。
5. 共享资源要单独处理 read/write scope。

#### 结合我的项目怎么答

`router/rbac.go` 注释明确强调 sub-resource must align with parent。比如 chunk 的权限不能只看 chunk_id，而要走 `chunk_id -> knowledge_id -> kb_id -> creator_id`。知识、chunk、wiki page 这些子资源的 guard 都会回溯到 KB 级 owner。

#### 技术原理 / 链路设计讲解

越权常见路径：

- 用户不能访问 KB，但通过 knowledge_id 查询文档。
- 用户不能编辑 KB，但通过 chunk_id 修改 chunk。
- 用户不能看私有 KB，但通过 shared agent 间接查询。
- 用户知道资源 ID，绕过列表接口直接访问详情。

解决办法是所有 detail/mutation 接口都做权限校验，不能只在列表页过滤。

#### 技术栈特点与选型理由

将权限 guard 放在路由注册处，可以让“这条路由需要什么权限”一眼可见；creator lookup 作为闭包注入 guard，可以复用权限逻辑。

#### 可直接复述的面试回答

防越权不能只靠前端隐藏按钮，也不能只在列表接口过滤。WeKnora 在路由层对知识库、文档、chunk、wiki page 这些资源都挂了 RBAC guard。特别是子资源会回溯到父 KB，比如通过 chunk_id 修改 chunk 时，系统要查到它所属的 knowledge，再查到 KB 的 creator_id，然后判断调用者是这个 KB 的创建者还是 Admin+。这样即使用户猜到了 chunk_id，也不能绕过知识库权限。

#### 常见追问

- 资源 ID 是否可枚举？
- 共享空间和普通租户权限如何叠加？
- 批量操作跨多个 KB 怎么判断权限？

#### 常见坑

不要说“UUID 很难猜所以安全”。安全不能依赖 ID 难猜，必须做服务端鉴权。

---

### Q3：敏感凭据如何保护？

#### 面试官为什么问

项目接入大量模型、向量库、对象存储、MCP、数据源，凭据保护是企业级系统必问点。

#### 先给结论

项目里模型、向量库、对象存储、MCP 都会涉及 API Key 或密码，这些不能明文落库和返回前端。

#### 回答思路

1. 不把密钥明文写日志或返回前端。
2. DB 存储前加密，比如 AES-GCM。
3. 使用主密钥/盐，生产环境必须安全注入。
4. 更新配置要避免覆盖已有密钥。
5. 密钥轮换和丢失要有预案。

#### 结合我的项目怎么答

`types/vectorstore.go` 中 `ConnectionConfig.Value()` 会用 `utils.GetAESKey()` 对 `Password` 和 `APIKey` 做 AES-GCM 加密；`Scan()` 会解密。`mcp_service.go` 注释也强调 raw entity 包含凭据，handler 返回前必须转换 DTO 去掉 secret 字段。

#### 技术原理 / 链路设计讲解

AES-GCM 是认证加密模式，既保证机密性，也能发现密文被篡改。相比只做 Base64 或简单混淆，它更适合存储 API Key、数据库密码、对象存储密钥。

但加密只能保护数据库泄露场景；应用运行时仍然需要明文使用凭据，所以日志脱敏、响应脱敏、内存暴露和权限控制同样重要。

#### 技术栈特点与选型理由

GORM 的 `driver.Valuer` 和 `sql.Scanner` 可以在数据入库和读取时统一加解密，减少业务层遗漏。

#### 可直接复述的面试回答

项目里模型、向量库、对象存储、MCP 都会涉及 API Key 或密码，这些不能明文落库和返回前端。WeKnora 对 vector store 的 Password、APIKey 这类字段在写 DB 前用 AES-GCM 加密，读取时再解密给内部服务使用。MCP 服务也要求 handler 返回 DTO 时剥离 secret 字段。这里还要注意生产环境的主密钥必须安全注入，日志里要做脱敏，否则加密落库没有意义。

#### 常见追问

- AES-GCM 和 AES-CBC 有什么区别？
- 加密密钥放在哪里？
- 密钥轮换怎么做？

#### 常见坑

不要把“加密落库”等同于完整安全。还要考虑日志、响应、备份、密钥管理和运行时权限。

---

### Q4：URL 导入为什么需要 SSRF 防护？

#### 面试官为什么问

知识库系统允许导入网页或远程文件，SSRF 是高频安全风险。

#### 先给结论

URL 导入是典型 SSRF 风险点，因为用户给一个 URL，服务端会替用户去请求。

#### 回答思路

1. 用户提交 URL，服务端会主动请求。
2. 攻击者可能让服务访问内网地址、云元数据地址、本机管理端口。
3. 需要校验协议、域名/IP、私网地址、重定向。
4. 白名单必须谨慎。

#### 结合我的项目怎么答

`CreateKnowledgeFromURL` 中会先校验 URL 格式，再调用 `secutils.ValidateURLForSSRF(url)`。`.env` 支持 `SSRF_WHITELIST`，但文档提示生产环境谨慎配置。

#### 技术原理 / 链路设计讲解

SSRF 攻击示例：

```text
用户提交 http://169.254.169.254/latest/meta-data/
服务端在云主机内访问该地址
攻击者获取云实例 metadata 或临时凭据
```

因此只校验字符串是否是 URL 不够，还要解析 DNS、判断 IP 范围、限制协议、处理重定向后的地址，并避免访问内网和保留地址。

#### 技术栈特点与选型理由

集中封装 SSRF 校验函数比在每个 URL 入口手写规则更可靠，也便于维护白名单和测试。

#### 可直接复述的面试回答

URL 导入是典型 SSRF 风险点，因为用户给一个 URL，服务端会替用户去请求。如果不做限制，攻击者可能让服务端访问内网地址、localhost、云 metadata 服务或 Redis/管理端口。WeKnora 在 URL 入库时不仅校验格式，还调用统一的 SSRF 校验，并支持白名单配置。生产环境白名单必须非常谨慎，只给确实可信的内部域名或网段放行。

#### 常见追问

- DNS rebinding 怎么防？
- 重定向到内网怎么办？
- 白名单支持通配符会有什么风险？

#### 常见坑

不要只判断 URL 以 `http` 开头。SSRF 防护必须处理解析后的 IP、重定向和私网网段。

---

## 4. 本主题总结

安全问题要从“身份、权限、数据、执行、网络”五层回答。WeKnora 的加分点是 RBAC + ownership guard、audit log、AES-GCM 凭据加密、SSRF 校验、MCP stdio 禁用、Agent sandbox 和工具审批。

## 5. 面试前自查清单

- 我是否能讲清 viewer/contributor/admin/owner 权限矩阵？
- 我是否能解释子资源为什么要回溯父 KB 权限？
- 我是否能说明凭据加密和响应脱敏的区别？
- 我是否能说出 URL 导入的 SSRF 风险？
- 我是否能举一个可能越权接口的排查案例？
