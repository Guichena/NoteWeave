# 文件：28_对象存储_gRPC_docreader八股与业务加强版.md

## 1. 本主题面试官想考什么

对象存储和 docreader 是 WeKnora 文档入库链路的底座。面试官通常会围绕以下角度追问：

- 文件为什么不能直接存数据库或本地磁盘。
- 对象存储的 bucket、object key、etag、multipart、presigned URL 是什么。
- MinIO 和 S3/COS/OSS/TOS 的关系是什么。
- 文件上传、DB 元数据、异步解析之间如何保证一致性。
- docreader 为什么独立成 Python 服务。
- gRPC 和 HTTP 怎么取舍。
- protobuf、HTTP/2、多路复用、deadline、streaming 是什么。
- 大文件解析如何控制内存、超时和失败恢复。

这块回答要把“存储原理”和“文档入库业务”绑定起来，否则很容易变成泛泛八股。

## 2. 初学者先理解文档入库链路

WeKnora 的文档入库可以简化为：

```text
用户上传文件
  -> API 保存文件到对象存储
  -> DB 保存 knowledge 元数据和 file_path
  -> Asynq 投递后台任务
  -> Worker 读取文件
  -> 调 docreader 解析
  -> 保存解析图片
  -> 切 chunk
  -> embedding
  -> 写向量库
  -> 更新状态 ready
```

对象存储负责保存“大文件和二进制资源”。  
Postgres 负责保存“这份文件是谁的、属于哪个知识库、当前处理状态是什么”。  
docreader 负责把复杂文件解析成模型可消费的文本/Markdown/图片引用。

## 3. 高频问题清单

### 基础问题

- 对象存储是什么？
- bucket 和 object key 是什么？
- 为什么文件不直接存数据库？
- MinIO 和 S3 是什么关系？
- presigned URL 是什么？
- gRPC 是什么？
- protobuf 是什么？

### 进阶问题

- 本地磁盘、MinIO、云对象存储怎么取舍？
- object key 如何设计？
- 文件上传成功但 DB 写失败怎么办？
- DB 删除成功但对象存储删除失败怎么办？
- docreader 为什么独立服务化？
- gRPC 和 HTTP 有什么区别？
- 大文件通过 gRPC 传输有什么风险？

### 深挖追问

- S3 multipart upload 原理是什么？
- ETag 能不能当 MD5？
- 对象存储强一致和最终一致有什么影响？
- presigned URL 如何生成，如何过期？
- HTTP/2 多路复用解决了什么？
- gRPC deadline 和 context cancellation 有什么用？
- protobuf 如何做兼容升级？

### 业务压力追问

- 如果用户上传 500MB PDF，系统怎么处理？
- docreader 解析超时怎么办？
- PDF 解析乱码怎么排查？
- 解析出来的图片怎么和 chunk 关联？
- 如果客户要求文件不能出内网，怎么部署？
- 文件存储如何做租户隔离？

## 4. 八股知识点 1：对象存储是什么

### 4.1 是什么

对象存储是一种以对象为单位保存数据的存储系统。每个对象通常包含：

- data：对象内容。
- key：对象唯一标识。
- metadata：元数据。
- bucket：对象所在容器。

和文件系统不同，对象存储没有真正的目录树。`tenant/1/a.pdf` 这种路径只是 key 名称里的前缀。

### 4.2 为什么适合文档系统

文档系统有这些特点：

- 文件大小不固定，可能从 KB 到 GB。
- 文件读写和数据库查询模式不同。
- 文件下载、预览、生命周期管理常见。
- 私有化/云上部署都需要支持。
- 多实例服务需要共享文件。

对象存储正好适合：

- 大对象读写。
- multipart upload。
- 横向扩展。
- 生命周期管理。
- 临时访问 URL。
- 云厂商托管或 MinIO 私有化。

### 4.3 为什么不用数据库 BLOB

数据库 BLOB 的问题：

- 备份恢复体积大。
- 大文件占用 DB 连接和 I/O。
- buffer cache 被大对象污染。
- CDN/下载/断点续传不方便。
- 数据库扩容成本高。

什么时候可以用 BLOB？

- 小文件。
- 文件必须和事务强绑定。
- 文件数量少。
- 系统规模很小。

WeKnora 文档入库涉及 PDF、Office、图片和解析产物，用对象存储更合适。

### 4.4 可直接复述的面试回答

> 对象存储是按 bucket 和 object key 管理大对象的存储系统，适合 PDF、Office、图片这类文件。数据库更适合存结构化元数据，比如 knowledge_id、tenant_id、file_path、处理状态。文件如果放 DB BLOB，会让备份、连接、I/O 和缓存都变重，也不方便下载、生命周期和多实例共享。WeKnora 用 FileService 抽象对象存储，私有化可以用 MinIO，云上可以接 S3/COS/OSS/TOS，业务层只依赖 object key。

## 5. 八股知识点 2：bucket、key、ETag、multipart

### 5.1 bucket

bucket 是对象容器。可以按环境、业务或租户划分，但不建议每个小租户都单独 bucket，管理复杂。

常见做法：

- 一个环境一个 bucket。
- key 前缀区分租户和业务。

### 5.2 object key

object key 是对象在 bucket 内的唯一标识。

推荐设计：

```text
tenant/{tenant_id}/kb/{kb_id}/knowledge/{knowledge_id}/original/{safe_filename}
tenant/{tenant_id}/kb/{kb_id}/knowledge/{knowledge_id}/images/{image_id}.png
tenant/{tenant_id}/tmp/{request_id}/{filename}
```

原则：

- 带 tenant_id，方便租户隔离。
- 带 knowledge_id，方便删除文档时清理。
- 使用安全文件名，防路径注入。
- tmp 和正式文件分开，便于生命周期清理。

### 5.3 ETag

ETag 是对象版本/内容标识。注意：

- 单分片上传时，很多 S3 兼容实现的 ETag 可能是 MD5。
- multipart upload 时，ETag 通常不是简单 MD5。
- 不同厂商实现可能不同。

所以不要无脑把 ETag 当文件 MD5。需要校验完整性时，最好自己计算 hash 并保存 metadata。

### 5.4 multipart upload

multipart upload 用于大文件上传：

1. Initiate multipart upload。
2. 把文件切成多个 part 上传。
3. 每个 part 有 part number 和 etag。
4. Complete multipart upload 合并。
5. 失败可 Abort 清理。

优点：

- 大文件可并发上传。
- 失败只重传部分。
- 避免单请求超时。

### 5.5 可直接复述的面试回答

> 对象存储里 bucket 是容器，object key 是对象唯一标识。项目里 key 设计要带 tenant_id、kb_id、knowledge_id，这样便于权限隔离和删除清理。ETag 可以辅助判断对象版本，但不能总当 MD5，尤其 multipart upload 后 ETag 往往不是原文件 MD5。大文件上传最好用 multipart，分片并发上传，失败只重传部分，最后 complete 合并。

## 6. 八股知识点 3：presigned URL

### 6.1 是什么

presigned URL 是服务端用访问密钥对某个对象操作生成的临时签名 URL。客户端拿到 URL 后，在过期时间内可以直接访问对象存储，而不需要知道真实 AK/SK。

### 6.2 解决什么问题

- 避免文件下载流量都经过应用服务器。
- 避免暴露对象存储密钥。
- 控制访问时间。
- 可限制操作类型，如 GET/PUT。

### 6.3 原理简化

签名通常包含：

- HTTP method。
- bucket/key。
- 过期时间。
- headers。
- credential scope。
- signature。

对象存储收到请求后，用服务端保存的 secret 重新计算签名，校验是否一致、是否过期。

### 6.4 安全注意点

- URL 过期时间不能太长。
- 不要把 presigned URL 打到日志。
- 只能给用户有权限访问的 object 生成 URL。
- 敏感文件可以走后端鉴权代理，而不是直接长链。
- 下载 URL 泄露后，在过期前别人也可能访问。

### 6.5 可直接复述的面试回答

> presigned URL 是服务端基于对象 key、HTTP method、过期时间和密钥生成的临时签名链接。客户端不需要拿 AK/SK，就可以在有效期内直接访问对象存储。它适合文件下载和上传直传，能减轻应用服务器流量压力。但生成前必须先做业务权限校验，URL 有效期要短，也不能随意打印到日志，因为链接泄露后在过期前就相当于临时授权。

## 7. 文件、DB、对象存储一致性

### 7.1 为什么有不一致

对象存储不参与 Postgres 事务，所以会出现：

- 文件上传成功，DB 写失败。
- DB 写成功，文件上传失败。
- DB 删除成功，文件删除失败。
- 文件存在，但 knowledge 被删除。

### 7.2 设计原则

- DB 是事实源。
- 对象存储保存文件本体。
- 上传使用 tmp prefix 或先上传后提交元数据。
- 删除尽量异步重试。
- 定期扫描孤儿对象。

### 7.3 常见流程

上传：

```text
上传到 tmp/object key
写 DB knowledge processing
成功后标记正式 key 或移动/引用
投递任务
```

删除：

```text
DB 标记 deleting/deleted
异步删除对象存储和向量
失败进入 retry/dead-letter
定期 reconcile
```

### 7.4 可直接复述的面试回答

> 对象存储和 DB 不能放在一个事务里，所以要接受最终一致。我的设计会让 DB 做事实源，文件 key 写在 DB 里。上传失败就不写 DB，DB 写失败产生的临时对象通过 tmp prefix 生命周期或清理任务回收。删除时先在 DB 标记 deleting/deleted，检索层不再返回，再异步删除对象和向量；删除失败可以重试或进 dead-letter。这样即使外部存储短暂失败，也不会影响权限和检索正确性。

## 8. docreader 为什么独立服务化

### 8.1 是什么

docreader 是文档解析服务，负责把 PDF、Office、图片等原始文件转换成 Markdown/text、图片引用、metadata 等结构。

### 8.2 为什么不放 Go 主服务里

原因：

- 文档解析库 Python 生态更丰富。
- OCR/PDF/Office 解析依赖复杂。
- 解析任务 CPU/内存消耗高。
- 文件解析容易崩溃或超时，独立服务能隔离故障。
- 可以单独扩容 docreader。
- 主服务镜像更轻、更稳定。

### 8.3 服务拆分的代价

- 多一次 RPC。
- 多一个容器和部署配置。
- 需要超时、重试、认证。
- 大文件传输要控制内存。
- 需要 trace 串联。

### 8.4 可直接复述的面试回答

> docreader 独立出来主要是为了解析能力和主业务解耦。PDF、Office、OCR、图片抽取这些依赖在 Python 生态更成熟，而且解析任务资源消耗大、失败率也比普通 API 高。如果放在 Go 主服务里，会让镜像和依赖变复杂，解析崩溃还可能影响 API 稳定性。独立服务后可以单独限流、扩容、重启和替换解析引擎。代价是多了 RPC，所以要处理超时、消息大小、认证和错误恢复。

## 9. gRPC 八股原理

### 9.1 gRPC 是什么

gRPC 是一个高性能 RPC 框架，通常使用：

- HTTP/2 作为传输协议。
- protobuf 作为接口定义和序列化格式。
- 支持 unary、server streaming、client streaming、bidirectional streaming。

### 9.2 protobuf 是什么

protobuf 是一种 IDL 和二进制序列化协议。

优点：

- 强类型。
- 体积小。
- 解析快。
- 支持多语言代码生成。
- 字段编号支持兼容升级。

兼容规则：

- 不要复用已删除字段编号。
- 新字段使用新的 tag。
- 字段类型变更要谨慎。
- 老客户端会忽略不认识的新字段。

### 9.3 HTTP/2 多路复用

HTTP/1.1 一个连接同一时间处理请求能力有限，容易队头阻塞。HTTP/2 在一个 TCP 连接上支持多个 stream 并发传输。

优点：

- 连接复用。
- 减少握手。
- 多请求并发。
- header 压缩。

### 9.4 gRPC deadline

deadline 表示调用最晚完成时间。客户端设置 deadline 后：

- 服务端可感知取消。
- 避免无限等待。
- 资源能及时释放。

在文档解析中，deadline 很重要，因为 PDF/OCR 可能卡住。

### 9.5 gRPC streaming

四种模式：

- Unary：一次请求一次响应。
- Server streaming：一次请求，多次响应。
- Client streaming：多次请求，一次响应。
- Bidirectional streaming：双方都可连续发送。

大文件传输如果用 unary，可能一次性占用大量内存。更好的方式：

- 传 object key，让 docreader 自己拉文件。
- 或使用 client streaming 分块传输。
- 或限制文件大小。

### 9.6 可直接复述的面试回答

> gRPC 适合内部服务通信，因为它用 protobuf 定义强类型接口，基于 HTTP/2 支持连接复用和多路复用，deadline、metadata、streaming 这些机制也比较成熟。docreader 是内部服务，Go 和 Python 都能通过 proto 生成客户端和服务端代码，所以接口契约更稳定。大文件场景要注意，unary gRPC 会受 max message size 和内存影响，超大文件更适合传对象存储 key 或用 streaming。

## 10. 大文件解析怎么回答

### 10.1 风险

- HTTP/gRPC 超时。
- 内存占用过高。
- docreader CPU 打满。
- PDF 页数过多。
- OCR 慢。
- 解析图片很多导致对象存储写入放大。
- embedding chunk 数暴涨。

### 10.2 处理策略

- 文件大小限制。
- 页数限制。
- 分片/流式读取。
- docreader worker 限流。
- Asynq 后台处理，不阻塞 HTTP。
- 解析超时和错误状态。
- 对图片/OCR 设置开关。
- chunk 数量上限。
- 失败进入 dead-letter。

### 10.3 可直接复述的面试回答

> 超大文件不能当普通请求处理。上传阶段可以走对象存储 multipart，API 只保存元数据和任务；解析阶段由 Asynq worker 调 docreader，避免 HTTP 请求阻塞。docreader 要有限流、超时、最大文件大小、最大页数和最大图片数控制。gRPC unary 传大文件有内存和消息大小风险，更稳的是传 object key 或 streaming。解析失败后状态标记 failed，并进入重试或 dead-letter，避免任务一直卡住。

## 11. PDF 解析乱码/空内容怎么排查

### 11.1 排查路径

1. 文件是否上传完整。
2. MIME/type 是否识别正确。
3. PDF 是文本型还是扫描型。
4. 是否需要 OCR。
5. 字体编码是否特殊。
6. docreader 选择的 engine 是否支持。
7. 解析超时或内存不足。
8. 图片输出路径是否可写。
9. 解析结果是否成功写回 DB/chunk。

### 11.2 面试回答

> PDF 解析问题要先区分是文件问题、解析引擎问题还是后处理问题。文本型 PDF 可以直接抽取文字，扫描型 PDF 需要 OCR；有些 PDF 字体编码特殊，会出现乱码。排查时我会先确认对象存储文件完整，再看 MIME 和解析 engine，检查 docreader 日志、超时、内存和 OCR 配置，然后看解析出的 Markdown 是否为空，最后看 chunk 是否生成并写入 DB/向量库。

## 12. 本主题总结

对象存储和 docreader 要讲清：

1. 对象存储适合大文件，DB 适合元数据。
2. bucket/key/presigned URL/multipart 是核心概念。
3. MinIO 是私有化 S3 兼容方案，云对象存储是托管方案。
4. DB 与对象存储之间是最终一致，要靠状态机和清理任务。
5. docreader 独立服务化是为了依赖、资源和故障隔离。
6. gRPC 适合内部强契约通信，但大文件要注意 streaming/object key。

## 13. 面试前自查清单

- 我是否能解释为什么不把文件存 DB？
- 我是否能设计 object key？
- 我是否能讲清 presigned URL 原理和风险？
- 我是否能说明 multipart upload 解决什么问题？
- 我是否能解释 docreader 独立服务的原因和代价？
- 我是否能比较 gRPC 和 HTTP？
- 我是否能讲清 protobuf 兼容规则？
- 我是否能给出大文件解析方案？
