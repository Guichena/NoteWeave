# 数据库与 API 蓝图

本文档从 `note_weave_功能说明与架构文档 (1).md` 的数据库和接口章节抽取而来，并按当前 NoteWeave 工程拆分做了收口修正。

关键修正：

- `user` 表改为 `users`，避免数据库关键字和函数命名冲突。
- `generated_artifact` 统一改为 `artifact`。
- `generation_task` / `index_task` 统一收口为通用 `task`。
- `citation_ids`、`artifact_ids`、`source_ids` 等核心关联不放 JSON 字段，改用关联表。
- 文档上传补充 `document_upload`、`upload_chunk`，支持分片上传、断点续传、MinIO 和 Kafka 异步处理。
- `chat_session` 补充 `session_kind`、`runtime_status`、`latest_context_snapshot_json`，支撑 WebSocket 流式、中断和恢复。
- 补充 `session_summary`、`space_memory`、`user_memory`，支撑分层记忆。
- API 统一使用 `/api/v1` 前缀。

---

## 1. 通用约定

### 1.1 字段约定

核心业务表默认包含：

```sql
id BIGINT PRIMARY KEY AUTO_INCREMENT,
created_at DATETIME NOT NULL,
updated_at DATETIME NOT NULL
```

状态字段统一使用 `VARCHAR(32)`。

JSON 字段只用于低频配置、快照和参数，不用于高频核心关联。

### 1.2 核心枚举

```text
space.type: PERSONAL / TEAM
space.status: ACTIVE / ARCHIVED
space_member.role: OWNER / EDITOR / VIEWER
space_member.status: ACTIVE / REMOVED

document_upload.status: INIT / UPLOADING / MERGED / PROCESSING / INDEXED / FAILED / CANCELLED / DELETED
document.status: PENDING_PROCESS / PROCESSING / INDEXED / FAILED / DELETED
task.status: PENDING / RUNNING / SUCCESS / FAILED / CANCELLED

chat_session.session_type: TEAM_CHAT / PERSONAL_RESEARCH_CHAT / ARTIFACT_CHAT
chat_session.session_kind: FORMAL / DRAFT
chat_session.runtime_status: IDLE / RUNNING / STOPPED / FAILED

artifact.status: GENERATING / READY / FAILED / ARCHIVED / PUBLISHED_TO_WIKI
```

---

## 2. 数据库表结构

### 2.1 用户与空间

#### users

```sql
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    email VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(64),
    avatar_url VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email),
    INDEX idx_users_status (status)
);
```

#### space

```sql
CREATE TABLE space (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    type VARCHAR(32) NOT NULL,
    owner_id BIGINT NOT NULL,
    visibility VARCHAR(32) NOT NULL DEFAULT 'PRIVATE',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_space_owner (owner_id),
    INDEX idx_space_type (type)
);
```

#### space_member

```sql
CREATE TABLE space_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    space_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    joined_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_space_user (space_id, user_id),
    INDEX idx_member_user (user_id),
    INDEX idx_member_space (space_id),
    INDEX idx_member_role (space_id, role)
);
```

---

### 2.2 团队知识库与文档

#### knowledge_base

```sql
CREATE TABLE knowledge_base (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    space_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_kb_space (space_id),
    INDEX idx_kb_creator (created_by)
);
```

#### document_upload

用于分片上传、断点续传和合并状态管理。

```sql
CREATE TABLE document_upload (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    space_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT,
    user_id BIGINT NOT NULL,
    file_md5 CHAR(32) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128),
    total_size BIGINT NOT NULL,
    chunk_size INT NOT NULL,
    total_chunks INT NOT NULL,
    object_key VARCHAR(512),
    status VARCHAR(32) NOT NULL DEFAULT 'INIT',
    error_message TEXT,
    expires_at DATETIME,
    cancelled_at DATETIME,
    merged_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_upload_space (space_id),
    INDEX idx_upload_kb (knowledge_base_id),
    INDEX idx_upload_user_md5 (user_id, file_md5),
    INDEX idx_upload_status (status)
);
```

#### upload_chunk

```sql
CREATE TABLE upload_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    upload_id BIGINT NOT NULL,
    file_md5 CHAR(32) NOT NULL,
    chunk_index INT NOT NULL,
    chunk_md5 CHAR(32),
    size BIGINT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_upload_chunk (upload_id, chunk_index),
    INDEX idx_chunk_file_md5 (file_md5)
);
```

#### file_object

按 Space 分区管理可复用对象存储文件，避免跨 Space 因相同 hash 共享权限边界。

```sql
CREATE TABLE file_object (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    space_id BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    size BIGINT NOT NULL,
    content_type VARCHAR(128),
    ref_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_file_object_space_hash (space_id, content_hash),
    INDEX idx_file_object_space (space_id),
    INDEX idx_file_object_status (status)
);
```

#### document

```sql
CREATE TABLE document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    space_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    file_object_id BIGINT,
    title VARCHAR(255) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    object_key VARCHAR(512),
    original_filename VARCHAR(255),
    content_hash CHAR(64),
    active_index_version INT DEFAULT 0,
    parse_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    index_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_PROCESS',
    token_count INT DEFAULT 0,
    chunk_count INT DEFAULT 0,
    error_message TEXT,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_doc_kb (knowledge_base_id),
    INDEX idx_doc_space (space_id),
    INDEX idx_doc_status (status),
    INDEX idx_doc_index_status (index_status),
    INDEX idx_doc_hash (content_hash)
);
```

#### document_chunk

```sql
CREATE TABLE document_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    space_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    index_version INT NOT NULL DEFAULT 1,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    content_hash CHAR(64),
    token_count INT DEFAULT 0,
    source_start INT,
    source_end INT,
    embedding_id VARCHAR(128),
    es_doc_id VARCHAR(128),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_doc_chunk_index (document_id, index_version, chunk_index),
    INDEX idx_chunk_doc (document_id),
    INDEX idx_chunk_doc_version (document_id, index_version),
    INDEX idx_chunk_kb (knowledge_base_id),
    INDEX idx_chunk_space (space_id)
);
```

---

### 2.3 团队轻量 Wiki

#### wiki_page

```sql
CREATE TABLE wiki_page (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    space_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    content LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    source_artifact_id BIGINT,
    published_version_id BIGINT,
    created_by BIGINT NOT NULL,
    updated_by BIGINT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_wiki_space (space_id),
    INDEX idx_wiki_status (status),
    INDEX idx_wiki_artifact (source_artifact_id)
);
```

#### wiki_page_version

```sql
CREATE TABLE wiki_page_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    wiki_page_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    content LONGTEXT NOT NULL,
    change_note VARCHAR(512),
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_wiki_version (wiki_page_id, version_no),
    INDEX idx_version_wiki (wiki_page_id)
);
```

---

### 2.4 个人研究工作台

#### research_project

```sql
CREATE TABLE research_project (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    space_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(1024),
    research_goal VARCHAR(1024),
    compile_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_project_space (space_id),
    INDEX idx_project_user (user_id),
    INDEX idx_project_status (status)
);
```

#### source

```sql
CREATE TABLE source (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    space_id BIGINT NOT NULL,
    research_project_id BIGINT,
    title VARCHAR(255) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
        url VARCHAR(1024),
        object_key VARCHAR(512),
        raw_text_object_key VARCHAR(512),
        parsed_text_object_key VARCHAR(512),
        content_hash CHAR(64),
    import_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    compile_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    token_count INT DEFAULT 0,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_source_project (research_project_id),
    INDEX idx_source_space (space_id),
    INDEX idx_source_status (compile_status),
    INDEX idx_source_hash (content_hash)
);
```

#### article_card

```sql
CREATE TABLE article_card (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    space_id BIGINT NOT NULL,
    research_project_id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary TEXT,
    key_points_json JSON,
    tags_json JSON,
    evidence_quotes_json JSON,
    card_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_article_source (source_id),
    INDEX idx_article_project (research_project_id),
    INDEX idx_article_space (space_id)
);
```

#### concept_card

```sql
CREATE TABLE concept_card (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    space_id BIGINT NOT NULL,
    research_project_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    definition TEXT,
        explanation LONGTEXT,
        use_cases_json JSON,
        common_misunderstandings_json JSON,
        source_ids_json JSON,
        evidence_quotes_json JSON,
        confidence DECIMAL(5,4) DEFAULT 0.0000,
    card_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_project_concept_name (research_project_id, normalized_name),
    INDEX idx_concept_project (research_project_id),
    INDEX idx_concept_space (space_id)
);
```

#### concept_alias

```sql
CREATE TABLE concept_alias (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    concept_card_id BIGINT NOT NULL,
    alias VARCHAR(255) NOT NULL,
    normalized_alias VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_concept_alias (concept_card_id, normalized_alias),
    INDEX idx_alias_name (normalized_alias)
);
```

#### concept_relation

```sql
CREATE TABLE concept_relation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    research_project_id BIGINT NOT NULL,
    source_concept_id BIGINT NOT NULL,
    target_concept_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    description VARCHAR(512),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_concept_relation (source_concept_id, target_concept_id, relation_type),
    INDEX idx_relation_project (research_project_id)
);
```

#### article_concept_relation

```sql
CREATE TABLE article_concept_relation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    article_card_id BIGINT NOT NULL,
    concept_card_id BIGINT NOT NULL,
    source_id BIGINT,
    evidence TEXT,
    relevance_score DECIMAL(5,4) DEFAULT 0.0000,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_article_concept (article_card_id, concept_card_id),
    INDEX idx_acr_concept (concept_card_id),
    INDEX idx_acr_article (article_card_id)
);
```

#### methodology_card

```sql
CREATE TABLE methodology_card (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    space_id BIGINT NOT NULL,
    research_project_id BIGINT,
    name VARCHAR(255) NOT NULL,
    scene VARCHAR(255),
    problem_type VARCHAR(128),
    workflow_json JSON,
    required_concepts_json JSON,
    output_structure_json JSON,
    quality_checklist_json JSON,
    card_source VARCHAR(32) NOT NULL DEFAULT 'PRESET',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_method_project (research_project_id),
    INDEX idx_method_space (space_id)
);
```

---

### 2.5 对话、会话运行态与记忆

#### chat_session

```sql
CREATE TABLE chat_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    session_type VARCHAR(32) NOT NULL,
    session_kind VARCHAR(32) NOT NULL DEFAULT 'FORMAL',
    title VARCHAR(255) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_ids_json JSON,
    summary TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    runtime_status VARCHAR(32) NOT NULL DEFAULT 'IDLE',
    latest_context_snapshot_json JSON,
    last_active_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_chat_user_space (user_id, space_id),
    INDEX idx_chat_space_type (space_id, session_type),
    INDEX idx_chat_status (status),
    INDEX idx_chat_runtime_status (runtime_status)
);
```

#### chat_message

```sql
CREATE TABLE chat_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    message_seq INT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    message_type VARCHAR(32) NOT NULL DEFAULT 'TEXT',
    artifact_id BIGINT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_message_seq (session_id, message_seq),
    INDEX idx_message_session (session_id),
    INDEX idx_message_role (session_id, role)
);
```

#### session_summary

```sql
CREATE TABLE session_summary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    topic VARCHAR(128),
    query_type VARCHAR(32),
    scope_type VARCHAR(32),
    scope_id VARCHAR(64),
    summary TEXT NOT NULL,
    resolved_entities_json JSON,
    reference_source_json JSON,
    importance_score DECIMAL(5,4),
    confidence_score DECIMAL(5,4),
    stale TINYINT NOT NULL DEFAULT 0,
    pin TINYINT NOT NULL DEFAULT 0,
    expires_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_summary_user_created (user_id, created_at),
    INDEX idx_summary_session (session_id),
    INDEX idx_summary_scope (scope_type, scope_id)
);
```

#### space_memory

```sql
CREATE TABLE space_memory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    topic VARCHAR(128),
    summary TEXT,
    focused_sources_json JSON,
    resolved_entities_json JSON,
    artifact_preferences_json JSON,
    conversation_patterns_json JSON,
    expires_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_space_memory_user_space (user_id, space_id),
    INDEX idx_space_memory_updated (updated_at)
);
```

#### user_memory

```sql
CREATE TABLE user_memory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    summary TEXT,
    preferences_json JSON,
    style_profile_json JSON,
    habit_profile_json JSON,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_user_memory_user (user_id),
    INDEX idx_user_memory_updated (updated_at)
);
```

---

### 2.6 Artifact、Task 与 Skill

#### artifact

```sql
CREATE TABLE artifact (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    research_project_id BIGINT,
    created_from_session_id BIGINT,
    created_from_message_id BIGINT,
    task_id BIGINT,
    artifact_type VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content LONGTEXT,
    source_scope_type VARCHAR(32),
    status VARCHAR(32) NOT NULL DEFAULT 'GENERATING',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_artifact_space (space_id),
    INDEX idx_artifact_project (research_project_id),
    INDEX idx_artifact_session (created_from_session_id),
    INDEX idx_artifact_type (artifact_type),
    INDEX idx_artifact_status (status)
);
```

#### artifact_version

```sql
CREATE TABLE artifact_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    artifact_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    task_id BIGINT,
    title VARCHAR(255) NOT NULL,
    content LONGTEXT,
    change_note VARCHAR(512),
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_artifact_version_no (artifact_id, version_no),
    INDEX idx_artifact_version_task (task_id)
);
```

#### session_artifact

```sql
CREATE TABLE session_artifact (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    artifact_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_session_artifact_relation (session_id, artifact_id, relation_type),
    INDEX idx_sa_artifact (artifact_id)
);
```

#### artifact_source

```sql
CREATE TABLE artifact_source (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    artifact_id BIGINT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_artifact_source (artifact_id, source_type, source_id),
    INDEX idx_artifact_source_ref (source_type, source_id)
);
```

#### task

```sql
CREATE TABLE task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    research_project_id BIGINT,
    task_type VARCHAR(64) NOT NULL,
    target_type VARCHAR(64),
    target_id BIGINT,
    task_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(255),
    input_json JSON,
        output_json JSON,
        error_message TEXT,
        cancel_requested TINYINT NOT NULL DEFAULT 0,
        retry_count INT NOT NULL DEFAULT 0,
    max_retry_count INT NOT NULL DEFAULT 3,
    result_ref_type VARCHAR(64),
    result_ref_id BIGINT,
    started_at DATETIME,
    finished_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_task_idempotency (idempotency_key),
    INDEX idx_task_space (space_id),
    INDEX idx_task_project (research_project_id),
    INDEX idx_task_target (target_type, target_id),
    INDEX idx_task_status (task_status),
    INDEX idx_task_type (task_type)
);
```

#### task_attempt

```sql
CREATE TABLE task_attempt (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    worker_id VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    started_at DATETIME,
    finished_at DATETIME,
    error_code VARCHAR(64),
    error_message TEXT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_task_attempt_no (task_id, attempt_no),
    INDEX idx_attempt_task (task_id),
    INDEX idx_attempt_status (status)
);
```

#### task_outbox

```sql
CREATE TABLE task_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME,
    sent_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_task_outbox_idem (idempotency_key),
    INDEX idx_outbox_status_retry (status, next_retry_at),
    INDEX idx_outbox_task (task_id)
);
```

#### skill_execution_log

```sql
CREATE TABLE skill_execution_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    artifact_id BIGINT,
    artifact_version_id BIGINT,
    skill_name VARCHAR(128) NOT NULL,
    input_json JSON,
    output_json JSON,
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    latency_ms BIGINT,
    model_name VARCHAR(128),
    prompt_version VARCHAR(64),
    input_tokens INT,
    output_tokens INT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_skill_task (task_id),
    INDEX idx_skill_name (skill_name)
);
```

---

### 2.7 Citation

#### citation

```sql
CREATE TABLE citation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    space_id BIGINT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    chunk_id BIGINT,
    title VARCHAR(255),
    quote_text TEXT,
    location_info VARCHAR(255),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_citation_space (space_id),
    INDEX idx_citation_source (source_type, source_id),
    INDEX idx_citation_chunk (chunk_id)
);
```

#### message_citation

```sql
CREATE TABLE message_citation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id BIGINT NOT NULL,
    citation_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_message_citation (message_id, citation_id),
    INDEX idx_mc_citation (citation_id)
);
```

#### artifact_citation

```sql
CREATE TABLE artifact_citation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    artifact_id BIGINT NOT NULL,
    citation_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_artifact_citation (artifact_id, citation_id),
    INDEX idx_ac_citation (citation_id)
);
```

---

### 2.8 Quiz 扩展

#### quiz

```sql
CREATE TABLE quiz (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    artifact_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    difficulty VARCHAR(32),
    question_count INT DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_quiz_artifact (artifact_id),
    INDEX idx_quiz_space (space_id)
);
```

#### quiz_question

```sql
CREATE TABLE quiz_question (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    quiz_id BIGINT NOT NULL,
    question_type VARCHAR(32) NOT NULL,
    question_text TEXT NOT NULL,
    options_json JSON,
    answer_json JSON,
    explanation TEXT,
    concept_card_id BIGINT,
    difficulty VARCHAR(32),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_question_quiz (quiz_id),
    INDEX idx_question_concept (concept_card_id)
);
```

#### quiz_answer_record

```sql
CREATE TABLE quiz_answer_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    quiz_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    user_answer_json JSON,
    is_correct TINYINT,
    answered_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_answer_quiz_user (quiz_id, user_id),
    INDEX idx_answer_question (question_id)
);
```

---

## 3. API 蓝图

### 3.1 通用约定

接口统一前缀：

```http
/api/v1
```

鉴权方式：

```text
Authorization: Bearer <token>
```

统一响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "success",
  "data": {}
}
```

分页响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "success",
  "data": {
    "items": [],
    "page": 1,
    "pageSize": 20,
    "total": 100
  }
}
```

实现约定：

```text
ApiResponse<T>
PageResponse<T>
```

所有列表接口返回 `ApiResponse<PageResponse<T>>`，分页参数统一为 `page`、`pageSize`、`sort`，筛选参数进入 Query DTO。

---

### 3.2 用户与认证

```http
POST /api/v1/auth/register
POST /api/v1/auth/login
GET  /api/v1/users/me
```

注册请求：

```json
{
  "username": "alice",
  "email": "alice@example.com",
  "password": "Password123!",
  "displayName": "Alice"
}
```

登录请求：

```json
{
  "usernameOrEmail": "alice@example.com",
  "password": "Password123!"
}
```

说明：注册成功后自动创建 PERSONAL Space，并写入 `space_member` OWNER。

---

### 3.3 Space 与成员

```http
POST   /api/v1/spaces
GET    /api/v1/spaces
GET    /api/v1/spaces/{spaceId}
PUT    /api/v1/spaces/{spaceId}
POST   /api/v1/spaces/{spaceId}/members
GET    /api/v1/spaces/{spaceId}/members
PUT    /api/v1/spaces/{spaceId}/members/{memberId}/role
DELETE /api/v1/spaces/{spaceId}/members/{memberId}
```

创建团队空间：

```json
{
  "name": "AI Project Team",
  "description": "团队知识空间"
}
```

添加成员：

```json
{
  "email": "bob@example.com",
  "role": "EDITOR"
}
```

---

### 3.4 团队 KnowledgeBase

```http
POST   /api/v1/team/spaces/{spaceId}/knowledge-bases
GET    /api/v1/team/spaces/{spaceId}/knowledge-bases
GET    /api/v1/team/knowledge-bases/{kbId}
PUT    /api/v1/team/knowledge-bases/{kbId}
DELETE /api/v1/team/knowledge-bases/{kbId}
```

---

### 3.5 团队 Document 上传与处理

分片上传链路以 `file_upload_async_pipeline.md` 为准。

```http
POST /api/v1/team/knowledge-bases/{knowledgeBaseId}/documents/uploads/init
POST /api/v1/team/document-uploads/{uploadId}/chunks
GET  /api/v1/team/document-uploads/{uploadId}/status
POST /api/v1/team/document-uploads/{uploadId}/merge
GET  /api/v1/team/knowledge-bases/{knowledgeBaseId}/documents
GET  /api/v1/team/documents/{documentId}
GET  /api/v1/team/documents/{documentId}/chunks
DELETE /api/v1/team/documents/{documentId}
POST /api/v1/team/documents/{documentId}/reindex
```

上传初始化请求：

```json
{
  "fileMd5": "string",
  "fileName": "design.pdf",
  "contentType": "application/pdf",
  "totalSize": 123456,
  "chunkSize": 5242880,
  "totalChunks": 12
}
```

---

### 3.6 Chat / RAG 问答

普通 HTTP 会话管理：

```http
POST /api/v1/chat/sessions
GET  /api/v1/spaces/{spaceId}/chat-sessions
GET  /api/v1/chat/sessions/{sessionId}
GET  /api/v1/chat/sessions/{sessionId}/messages
POST /api/v1/chat/sessions/{sessionId}/messages
POST /api/v1/chat/sessions/{sessionId}/archive
```

WebSocket 流式执行：

```http
POST /api/v1/chat/ws-ticket
WebSocket /ws/chat/{ticket}
```

创建会话请求：

```json
{
  "spaceId": 10,
  "sessionType": "TEAM_CHAT",
  "sessionKind": "FORMAL",
  "title": "部署流程问答",
  "scopeType": "KNOWLEDGE_BASE",
  "scopeIds": [1001]
}
```

---

### 3.7 团队 Wiki

```http
POST /api/v1/team/spaces/{spaceId}/wiki-pages
GET  /api/v1/team/spaces/{spaceId}/wiki-pages
GET  /api/v1/team/wiki-pages/{pageId}
PUT  /api/v1/team/wiki-pages/{pageId}
POST /api/v1/team/wiki-pages/{pageId}/publish
POST /api/v1/artifacts/{artifactId}/publish-to-wiki
```

---

### 3.8 个人 ResearchProject

```http
POST   /api/v1/personal/research-projects
GET    /api/v1/personal/research-projects
GET    /api/v1/personal/research-projects/{projectId}
PUT    /api/v1/personal/research-projects/{projectId}
DELETE /api/v1/personal/research-projects/{projectId}
```

---

### 3.9 个人 Source

```http
POST   /api/v1/personal/research-projects/{projectId}/sources/upload
POST   /api/v1/personal/research-projects/{projectId}/sources/url
GET    /api/v1/personal/research-projects/{projectId}/sources
GET    /api/v1/personal/sources/{sourceId}
POST   /api/v1/personal/sources/{sourceId}/compile
DELETE /api/v1/personal/sources/{sourceId}
```

---

### 3.10 个人 Card

```http
GET  /api/v1/personal/research-projects/{projectId}/article-cards
GET  /api/v1/personal/article-cards/{cardId}
GET  /api/v1/personal/research-projects/{projectId}/concept-cards
GET  /api/v1/personal/concept-cards/{cardId}
PUT  /api/v1/personal/concept-cards/{cardId}
POST /api/v1/personal/concept-cards/merge
GET  /api/v1/personal/research-projects/{projectId}/methodology-cards
```

---

### 3.11 Studio / Task

```http
POST /api/v1/studio/tasks
GET  /api/v1/studio/tasks/{taskId}
POST /api/v1/studio/tasks/{taskId}/cancel
POST /api/v1/studio/tasks/{taskId}/retry
GET  /api/v1/tasks
GET  /api/v1/tasks/{taskId}
GET  /api/v1/tasks/{taskId}/skill-logs
```

创建生成任务示例：

```json
{
  "spaceId": 1,
  "researchProjectId": 100,
  "taskType": "REPORT_GENERATION",
  "sourceScopeType": "RESEARCH_PROJECT",
  "sourceIds": [100],
  "params": {
    "topic": "RAG 技术调研报告",
    "length": "MEDIUM",
    "includeCitations": true
  }
}
```

---

### 3.12 Artifact

```http
GET  /api/v1/spaces/{spaceId}/artifacts
GET  /api/v1/chat/sessions/{sessionId}/artifacts
GET  /api/v1/artifacts/{artifactId}
PUT  /api/v1/artifacts/{artifactId}
POST /api/v1/artifacts/{artifactId}/regenerate
POST /api/v1/artifacts/{artifactId}/generate
GET  /api/v1/artifacts/{artifactId}/export
```

---

### 3.13 Quiz

```http
GET  /api/v1/quizzes/{quizId}
POST /api/v1/quizzes/{quizId}/answers
GET  /api/v1/quizzes/{quizId}/answer-records
```

---

## 4. 权限规则

基础规则：

```text
PERSONAL Space：只有 owner 可访问。
TEAM Space：根据 space_member.role 判断权限。
```

团队侧权限：

| 操作 | OWNER | EDITOR | VIEWER |
|---|---:|---:|---:|
| 查看空间 | Y | Y | Y |
| 团队问答 | Y | Y | Y |
| 上传文档 | Y | Y | N |
| 创建 Wiki 草稿 | Y | Y | N |
| 发布 Wiki | Y | 可选 | N |
| 管理成员 | Y | N | N |

个人侧权限：

```text
ResearchProject、Source、Card、Artifact 默认仅 owner 可访问。
```

---

## 5. 执行优先级

第一阶段只实现：

```text
users
space
space_member

POST /api/v1/auth/register
POST /api/v1/auth/login
GET  /api/v1/users/me
POST /api/v1/spaces
GET  /api/v1/spaces
GET  /api/v1/spaces/{spaceId}
POST /api/v1/spaces/{spaceId}/members
GET  /api/v1/spaces/{spaceId}/members
PUT  /api/v1/spaces/{spaceId}/members/{memberId}/role
DELETE /api/v1/spaces/{spaceId}/members/{memberId}
```

后续阶段按 `implementation_breakdown.md` 和对应 feature 文档推进。
