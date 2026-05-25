package com.noteweave.dev;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.admin.model.AuditAction;
import com.noteweave.admin.model.AuditLog;
import com.noteweave.admin.repository.AuditLogRepository;
import com.noteweave.artifact.model.Artifact;
import com.noteweave.artifact.model.ArtifactScopeType;
import com.noteweave.artifact.model.ArtifactStatus;
import com.noteweave.artifact.model.ArtifactType;
import com.noteweave.artifact.repository.ArtifactRepository;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatMessageRole;
import com.noteweave.chat.model.ChatMessageStatus;
import com.noteweave.chat.model.ChatMessageType;
import com.noteweave.chat.model.ChatRuntimeStatus;
import com.noteweave.chat.model.ChatScopeType;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.model.ChatSessionKind;
import com.noteweave.chat.model.ChatSessionScope;
import com.noteweave.chat.model.ChatSessionStatus;
import com.noteweave.chat.model.ChatSessionType;
import com.noteweave.chat.repository.ChatMessageRepository;
import com.noteweave.chat.repository.ChatSessionRepository;
import com.noteweave.chat.repository.ChatSessionScopeRepository;
import com.noteweave.llm.model.LlmCallLog;
import com.noteweave.llm.repository.LlmCallLogRepository;
import com.noteweave.memory.model.MemoryItem;
import com.noteweave.memory.model.MemoryType;
import com.noteweave.memory.model.SpaceMemory;
import com.noteweave.memory.model.UserMemory;
import com.noteweave.memory.repository.MemoryItemRepository;
import com.noteweave.memory.repository.SpaceMemoryRepository;
import com.noteweave.memory.repository.UserMemoryRepository;
import com.noteweave.personal.card.model.ArticleCard;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.model.PersonalCardStatus;
import com.noteweave.personal.card.model.SynthesisCard;
import com.noteweave.personal.card.repository.ArticleCardRepository;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.card.repository.SynthesisCardRepository;
import com.noteweave.personal.methodology.model.MethodologyCard;
import com.noteweave.personal.methodology.model.MethodologyCardScope;
import com.noteweave.personal.methodology.model.MethodologyCardSource;
import com.noteweave.personal.methodology.model.MethodologyCardStatus;
import com.noteweave.personal.methodology.repository.MethodologyCardRepository;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.model.ResearchProjectCompileStatus;
import com.noteweave.personal.project.model.ResearchProjectStatus;
import com.noteweave.personal.project.repository.ResearchProjectRepository;
import com.noteweave.personal.source.model.Source;
import com.noteweave.personal.source.model.SourceCompileStatus;
import com.noteweave.personal.source.model.SourceImportStatus;
import com.noteweave.personal.source.model.SourceType;
import com.noteweave.personal.source.repository.SourceRepository;
import com.noteweave.rageval.model.RagEvalCase;
import com.noteweave.rageval.model.RagEvalResult;
import com.noteweave.rageval.model.RagEvalRun;
import com.noteweave.rageval.repository.RagEvalCaseRepository;
import com.noteweave.rageval.repository.RagEvalResultRepository;
import com.noteweave.rageval.repository.RagEvalRunRepository;
import com.noteweave.search.document.EsDocumentChunk;
import com.noteweave.search.service.SearchIndexService;
import com.noteweave.space.model.Space;
import com.noteweave.space.model.SpaceMember;
import com.noteweave.space.model.SpaceMemberStatus;
import com.noteweave.space.model.SpaceRole;
import com.noteweave.space.model.SpaceStatus;
import com.noteweave.space.model.SpaceType;
import com.noteweave.space.repository.SpaceMemberRepository;
import com.noteweave.space.repository.SpaceRepository;
import com.noteweave.task.model.Task;
import com.noteweave.task.model.TaskAttempt;
import com.noteweave.task.model.TaskEvent;
import com.noteweave.task.model.TaskEventType;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.repository.TaskAttemptRepository;
import com.noteweave.task.repository.TaskEventRepository;
import com.noteweave.task.repository.TaskRepository;
import com.noteweave.team.document.model.Document;
import com.noteweave.team.document.model.DocumentChunk;
import com.noteweave.team.document.model.DocumentStatus;
import com.noteweave.team.document.model.FileObject;
import com.noteweave.team.document.model.FileObjectStatus;
import com.noteweave.team.document.repository.DocumentChunkRepository;
import com.noteweave.team.document.repository.DocumentRepository;
import com.noteweave.team.document.repository.FileObjectRepository;
import com.noteweave.team.kb.model.KnowledgeBase;
import com.noteweave.team.kb.model.KnowledgeBaseStatus;
import com.noteweave.team.kb.repository.KnowledgeBaseRepository;
import com.noteweave.team.wiki.model.WikiIndexStatus;
import com.noteweave.team.wiki.model.WikiPage;
import com.noteweave.team.wiki.model.WikiPageStatus;
import com.noteweave.team.wiki.model.WikiPageVersion;
import com.noteweave.team.wiki.repository.WikiPageRepository;
import com.noteweave.team.wiki.repository.WikiPageVersionRepository;
import com.noteweave.user.model.User;
import com.noteweave.user.model.UserStatus;
import com.noteweave.user.model.UserSystemRole;
import com.noteweave.user.repository.UserRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevDataSeeder {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SpaceRepository spaceRepository;
    private final SpaceMemberRepository spaceMemberRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileObjectRepository fileObjectRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatSessionScopeRepository chatSessionScopeRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ResearchProjectRepository researchProjectRepository;
    private final SourceRepository sourceRepository;
    private final ArticleCardRepository articleCardRepository;
    private final ConceptCardRepository conceptCardRepository;
    private final SynthesisCardRepository synthesisCardRepository;
    private final MethodologyCardRepository methodologyCardRepository;
    private final ArtifactRepository artifactRepository;
    private final WikiPageRepository wikiPageRepository;
    private final WikiPageVersionRepository wikiPageVersionRepository;
    private final MemoryItemRepository memoryItemRepository;
    private final UserMemoryRepository userMemoryRepository;
    private final SpaceMemoryRepository spaceMemoryRepository;
    private final TaskRepository taskRepository;
    private final TaskAttemptRepository taskAttemptRepository;
    private final TaskEventRepository taskEventRepository;
    private final AuditLogRepository auditLogRepository;
    private final LlmCallLogRepository llmCallLogRepository;
    private final RagEvalCaseRepository ragEvalCaseRepository;
    private final RagEvalRunRepository ragEvalRunRepository;
    private final RagEvalResultRepository ragEvalResultRepository;
    private final SearchIndexService searchIndexService;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        if (userRepository.findByUsername("admin").isEmpty()) {
            // Seed a clean dev database instead of bailing out when demo users are absent.
        }
        if (spaceRepository.findFirstByOwnerIdAndTypeAndStatusOrderByIdAsc(
                userRepository.findByUsername("alice").map(User::getId).orElse(-1L),
                SpaceType.PERSONAL,
                SpaceStatus.ACTIVE
        ).isEmpty()) {
            // Backfill missing dev spaces and relationships on partially initialized databases.
        }
        User admin = upsertUser("admin", "admin@noteweave.dev", "管理员", UserSystemRole.ADMIN);
        User alice = upsertUser("alice", "alice@noteweave.dev", "艾丽丝", UserSystemRole.USER);
        User bob = upsertUser("bob", "bob@noteweave.dev", "鲍勃", UserSystemRole.USER);

        ensurePersonalSpace(admin);
        Space alicePersonal = ensurePersonalSpace(alice);
        ensurePersonalSpace(bob);
        Space teamSpace = ensureTeamSpace(admin, "产品策略协作台", "用于产品研究、检索验证与成果评审的跨职能协作空间。");
        ensureTeamMember(teamSpace, admin, SpaceRole.OWNER);
        ensureTeamMember(teamSpace, alice, SpaceRole.EDITOR);
        ensureTeamMember(teamSpace, bob, SpaceRole.VIEWER);

        KnowledgeBase guildKb = ensureKnowledgeBase(teamSpace, admin, "AI 产品研究资料库", "收录产品文档、访谈摘要、发布说明与市场推进证据。");
        KnowledgeBase launchKb = ensureKnowledgeBase(teamSpace, admin, "发布准备台", "收录发布执行清单、评审纪要与关键运营决策。");

        Document researchDoc = ensureDocument(
                teamSpace,
                guildKb,
                admin,
                "新手引导证据简报",
                """
                用户经常在第一个工作区配置页面停住。
                在正式配置前先给出引导式示例，最能提升完成率。
                团队需要基于证据的发布叙事，而不是泛泛的新手引导建议。
                """
        );
        ensureChunk(researchDoc, teamSpace.getId(), guildKb.getId(), 0,
                "用户经常在第一个工作区配置页面停住，尤其是在配置前还看不清产品价值的时候。");
        ensureChunk(researchDoc, teamSpace.getId(), guildKb.getId(), 1,
                "当体验以引导式示例和进度感开始，而不是立刻要求配置时，完成率会明显提升。");

        Document launchDoc = ensureDocument(
                teamSpace,
                launchKb,
                admin,
                "发布评审纪要",
                """
                发布评审要求每条建议都明确唯一负责人。
                在周四叙事评审前，草稿可以持续编辑。
                每一条关键建议都必须回到已记录的证据。
                """
        );
        ensureChunk(launchDoc, teamSpace.getId(), launchKb.getId(), 0,
                "发布评审要求每条建议都明确唯一负责人，并坚持先证据后观点。");

        indexDocumentChunks(List.of(researchDoc, launchDoc));

        ResearchProject projectA = ensureProject(
                alicePersonal,
                alice,
                "三季度新手引导阻力研究",
                "用于整理新手引导访谈、漏斗分析与实验思路的研究项目。",
                "综合激活流程中的阻力点，并提出有优先级的改进方案。"
        );
        ResearchProject projectB = ensureProject(
                alicePersonal,
                alice,
                "竞品表述跟踪",
                "跟踪主要竞品的定位变化，并沉淀成可复用的策略笔记。",
                "持续记录关键信息变化，并识别影响发布定位的主题。"
        );

        Source interviewNotes = ensureTextSource(
                projectA,
                alice,
                "访谈模式笔记",
                "当用户还没理解价值就被要求配置工作区时，常常会停住。多位受访者都希望在配置前先看到引导路径和示例。"
        );
        Source funnelReview = ensureTextSource(
                projectA,
                alice,
                "激活漏斗复盘",
                "第一周激活率在首个工作区配置页面后明显下滑。当清单被表达为进度推进而不是配置负担时，完成率会提升。"
        );

        ensureArticleCard(projectA, interviewNotes, "访谈模式笔记", "多数用户希望先理解价值，再进入配置，并更偏好有引导的示例。");
        ensureArticleCard(projectA, funnelReview, "激活漏斗复盘", "当配置被改造成进度推进而不是沉重设置时，激活表现会更好。");
        ensureConceptCard(projectA, "引导式激活", "一种先展示价值、再逐步引导用户完成配置的产品新手引导模式。", "先给示例，再逐步解锁配置步骤。");

        MethodologyCard methodology = ensureMethodologyCard(
                alicePersonal.getId(),
                projectA.getId(),
                alice.getId(),
                "发布建议方法卡",
                "产品建议",
                "TECHNICAL_SUMMARY"
        );

        ChatSession chatSession = ensureChatSession(admin, teamSpace, guildKb, "新手引导证据评审");
        ChatMessage assistantMessage = ensureChatMessages(chatSession);

        Artifact artifact = ensureArtifact(
                teamSpace,
                alice,
                projectA,
                chatSession,
                assistantMessage,
                "三季度新手引导建议简报",
                """
                # 三季度新手引导建议简报

                ## 核心结论
                访谈与漏斗复盘都表明，首个配置页面过早要求用户投入，发生在用户真正理解价值之前。

                ## 最高杠杆改进
                先给用户一个可直接理解价值的引导式示例空间，再把配置拆成后续逐步完成的步骤。

                ## 为什么重要
                这个改动同时符合用户访谈反馈与漏斗行为数据，让发布叙事更扎实，也更容易被团队采纳。
                """,
                ArtifactStatus.READY
        );
        ensureSynthesisCard(projectA, artifact, alice);

        WikiPage wikiPage = ensureWikiPage(
                teamSpace,
                admin,
                "研究协作原则",
                """
                ## 目的
                这页记录团队在发布建议前如何校验证据。

                ## 工作规则
                - 先证据，后观点
                - 每条建议都要有明确负责人
                - 在发布评审前，草稿保持可编辑

                ## 评审节奏
                每周二做证据评审，每周四做发布叙事评审。
                """,
                true
        );
        ensureWikiVersion(wikiPage, admin);

        ensureUserMemory(alice, "艾丽丝偏好简洁、基于证据、同时明确取舍和下一步动作的总结方式。");
        ensureSpaceMemory(admin, teamSpace, "团队发布规则", "只有当负责人确认了证据质量与叙事清晰度后，建议才允许正式发布。");
        ensureMemoryItem(alice.getId(), null, MemoryType.USER_PREFERENCE, "偏好总结风格",
                "艾丽丝偏好简洁、基于证据、同时明确取舍和下一步动作的总结方式。", true);
        ensureMemoryItem(admin.getId(), teamSpace.getId(), MemoryType.SPACE_CONTEXT, "团队发布规则",
                "只有当负责人确认了证据质量与叙事清晰度后，建议才允许正式发布。", true);

        Task successTask = ensureTask(alice, teamSpace, projectA, TaskType.ARTIFACT_GENERATE, "ARTIFACT", artifact.getId(), TaskStatus.SUCCESS,
                Map.of("topic", "三季度新手引导建议简报"), Map.of("artifactId", artifact.getId()), null, artifact.getId());
        ensureTaskAttempt(successTask, TaskStatus.SUCCESS, null);
        ensureTaskEvent(successTask, TaskEventType.TASK_CREATED, null, TaskStatus.PENDING, "工作室任务已创建");
        ensureTaskEvent(successTask, TaskEventType.TASK_STARTED, TaskStatus.PENDING, TaskStatus.RUNNING, "成果生成已开始");
        ensureTaskEvent(successTask, TaskEventType.TASK_SUCCEEDED, TaskStatus.RUNNING, TaskStatus.SUCCESS, "成果生成已完成");

        Task failedTask = ensureTask(admin, teamSpace, null, TaskType.DOCUMENT_PROCESS, "DOCUMENT", researchDoc.getId(), TaskStatus.FAILED,
                Map.of("documentId", researchDoc.getId()), Map.of(), "用于后台验收的示例失败任务", null);
        ensureTaskAttempt(failedTask, TaskStatus.FAILED, "用于后台验收的示例失败任务");
        ensureTaskEvent(failedTask, TaskEventType.TASK_FAILED, TaskStatus.RUNNING, TaskStatus.FAILED, "示例 worker 在切片解析阶段超时");

        ensureAuditLog(admin.getId(), teamSpace.getId(), AuditAction.EVAL_RUN_START, "SPACE", teamSpace.getId());
        ensureAuditLog(admin.getId(), teamSpace.getId(), AuditAction.TASK_RETRY, "TASK", failedTask.getId());

        ensureLlmCallLog(teamSpace.getId(), chatSession.getId(), assistantMessage.getId(), successTask.getId(), artifact.getId());
        RagEvalCase evalCase = ensureEvalCase(teamSpace.getId(), admin.getId());
        RagEvalRun evalRun = ensureEvalRun(teamSpace.getId(), admin.getId());
        ensureEvalResult(evalRun.getId(), evalCase.getId(), assistantMessage.getId());
    }

    private User upsertUser(String username, String email, String displayName, UserSystemRole role) {
        User user = userRepository.findByUsername(username).orElseGet(User::new);
        user.setUsername(username);
        user.setEmail(email.toLowerCase(Locale.ROOT));
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode("NoteWeave123!"));
        }
        user.setDisplayName(displayName);
        user.setSystemRole(role);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    private Space ensurePersonalSpace(User owner) {
        Space space = spaceRepository.findFirstByOwnerIdAndTypeAndStatusOrderByIdAsc(owner.getId(), SpaceType.PERSONAL, SpaceStatus.ACTIVE)
                .orElseGet(Space::new);
        space.setName(owner.getUsername() + " 的个人空间");
        space.setType(SpaceType.PERSONAL);
        space.setOwnerId(owner.getId());
        space.setDescription("个人工作空间");
        space.setStatus(SpaceStatus.ACTIVE);
        Space saved = spaceRepository.save(space);
        ensureTeamMember(saved, owner, SpaceRole.OWNER);
        return saved;
    }

    private Space ensureTeamSpace(User owner, String name, String description) {
        Space space = spaceRepository.findAll().stream()
                .filter(item -> item.getOwnerId().equals(owner.getId())
                        && item.getType() == SpaceType.TEAM
                        && matchesAny(item.getName(), name, "Product Strategy Guild"))
                .findFirst()
                .orElseGet(Space::new);
        space.setName(name);
        space.setType(SpaceType.TEAM);
        space.setOwnerId(owner.getId());
        space.setDescription(description);
        space.setStatus(SpaceStatus.ACTIVE);
        return spaceRepository.save(space);
    }

    private Space findTeamSpace() {
        return spaceRepository.findAll().stream()
                .filter(space -> matchesAny(space.getName(), "产品策略协作台", "Product Strategy Guild") && space.getType() == SpaceType.TEAM)
                .findFirst()
                .orElseThrow();
    }

    private void ensureTeamMember(Space space, User user, SpaceRole role) {
        SpaceMember member = spaceMemberRepository.findBySpaceIdAndUserId(space.getId(), user.getId()).orElseGet(SpaceMember::new);
        member.setSpaceId(space.getId());
        member.setUserId(user.getId());
        member.setRole(role);
        member.setStatus(SpaceMemberStatus.ACTIVE);
        if (member.getJoinedAt() == null) {
            member.setJoinedAt(LocalDateTime.now());
        }
        member.setRemovedAt(null);
        member.setRemovedBy(null);
        spaceMemberRepository.save(member);
    }

    private KnowledgeBase ensureKnowledgeBase(Space space, User creator, String name, String description) {
        String legacyName = "AI 产品研究资料库".equals(name) ? "AI Product Research Library" : "Launch Readiness Desk";
        KnowledgeBase kb = knowledgeBaseRepository.findBySpaceIdAndStatus(space.getId(), KnowledgeBaseStatus.ACTIVE).stream()
                .filter(item -> matchesAny(item.getName(), name, legacyName))
                .findFirst()
                .orElseGet(KnowledgeBase::new);
        kb.setSpaceId(space.getId());
        kb.setName(name);
        kb.setDescription(description);
        kb.setStatus(KnowledgeBaseStatus.ACTIVE);
        kb.setCreatedBy(creator.getId());
        return knowledgeBaseRepository.save(kb);
    }

    private Document ensureDocument(Space space, KnowledgeBase knowledgeBase, User creator, String title, String content) {
        String legacyTitle = "新手引导证据简报".equals(title) ? "Onboarding Evidence Brief" : "Launch Review Notes";
        String hash = sha256(content);
        FileObject fileObject = fileObjectRepository.findBySpaceIdAndContentHash(space.getId(), hash).orElseGet(() -> {
            FileObject object = new FileObject();
            object.setSpaceId(space.getId());
            object.setContentHash(hash);
            object.setObjectKey("dev/file-object/" + hash + ".txt");
            object.setSize((long) content.getBytes(StandardCharsets.UTF_8).length);
            object.setContentType("text/plain");
            object.setRefCount(1);
            object.setStatus(FileObjectStatus.ACTIVE);
            return fileObjectRepository.save(object);
        });

        Document document = documentRepository.findByKnowledgeBaseIdAndDeletedAtIsNullAndStatusNotOrderByCreatedAtDesc(
                        knowledgeBase.getId(),
                        DocumentStatus.DELETED
                ).stream()
                .filter(item -> matchesAny(item.getTitle(), title, legacyTitle))
                .findFirst()
                .orElseGet(Document::new);
        document.setSpaceId(space.getId());
        document.setKnowledgeBaseId(knowledgeBase.getId());
        document.setFileObjectId(fileObject.getId());
        document.setTitle(title);
        document.setSourceType("FILE");
        document.setObjectKey(fileObject.getObjectKey());
        document.setOriginalFilename(title.toLowerCase(Locale.ROOT).replace(" ", "-") + ".txt");
        document.setContentHash(hash);
        document.setActiveIndexVersion(1);
        document.setParsedTextObjectKey("dev/parsed/" + hash + ".txt");
        document.setParseStatus("READY");
        document.setIndexStatus("INDEXED");
        document.setStatus(DocumentStatus.INDEXED);
        document.setTokenCount(content.split("\\s+").length);
        document.setChunkCount(0);
        document.setCreatedBy(creator.getId());
        return documentRepository.save(document);
    }

    private void ensureChunk(Document document, Long spaceId, Long knowledgeBaseId, int chunkIndex, String content) {
        DocumentChunk chunk = documentChunkRepository.findByDocumentIdAndIndexVersionOrderByChunkIndexAsc(document.getId(), 1).stream()
                .filter(item -> item.getChunkIndex() == chunkIndex)
                .findFirst()
                .orElseGet(DocumentChunk::new);
        chunk.setSpaceId(spaceId);
        chunk.setKnowledgeBaseId(knowledgeBaseId);
        chunk.setDocumentId(document.getId());
        chunk.setIndexVersion(1);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(content);
        chunk.setContentHash(sha256(content));
        chunk.setTokenCount(content.split("\\s+").length);
        chunk.setSectionTitle("Section " + (chunkIndex + 1));
        chunk.setSourceStart(chunkIndex * 100);
        chunk.setSourceEnd(chunkIndex * 100 + content.length());
        chunk.setEmbeddingId("stub-embedding-" + document.getId() + "-" + chunkIndex);
        chunk.setEsDocId("doc-" + document.getId() + "-chunk-" + chunkIndex);
        documentChunkRepository.save(chunk);
        document.setChunkCount((int) documentChunkRepository.findByDocumentIdAndIndexVersionOrderByChunkIndexAsc(document.getId(), 1).stream().count());
        documentRepository.save(document);
    }

    private void indexDocumentChunks(List<Document> documents) {
        searchIndexService.ensureDocumentChunkIndex();
        for (Document document : documents) {
            List<DocumentChunk> chunks = documentChunkRepository.findByDocumentIdAndIndexVersionOrderByChunkIndexAsc(document.getId(), 1);
            List<EsDocumentChunk> payload = chunks.stream()
                    .map(chunk -> EsDocumentChunk.builder()
                            .esDocId(chunk.getEsDocId())
                            .spaceId(chunk.getSpaceId())
                            .knowledgeBaseId(chunk.getKnowledgeBaseId())
                            .documentId(chunk.getDocumentId())
                            .documentStatus(document.getStatus().name())
                            .indexVersion(chunk.getIndexVersion())
                            .activeIndexVersion(document.getActiveIndexVersion())
                            .chunkId(chunk.getId())
                            .chunkIndex(chunk.getChunkIndex())
                            .title(document.getTitle())
                            .content(chunk.getContent())
                            .embedding(List.of(0.11f, 0.21f, 0.31f, 0.41f, 0.51f, 0.61f, 0.71f, 0.81f))
                            .contentHash(chunk.getContentHash())
                            .sourceType(document.getSourceType())
                            .createdBy(document.getCreatedBy())
                            .lifecycleStatus("ACTIVE")
                            .createdAt(chunk.getCreatedAt())
                            .build())
                    .toList();
            searchIndexService.bulkIndexChunks(payload);
        }
    }

    private ResearchProject ensureProject(Space space, User user, String title, String description, String goal) {
        String legacyTitle = "三季度新手引导阻力研究".equals(title) ? "Q3 Onboarding Friction Study" : "Competitor Messaging Tracker";
        ResearchProject project = researchProjectRepository.findBySpaceIdAndDeletedAtIsNullAndStatusOrderByCreatedAtDesc(space.getId(), ResearchProjectStatus.ACTIVE).stream()
                .filter(item -> matchesAny(item.getTitle(), title, legacyTitle))
                .findFirst()
                .orElseGet(ResearchProject::new);
        project.setSpaceId(space.getId());
        project.setUserId(user.getId());
        project.setTitle(title);
        project.setDescription(description);
        project.setResearchGoal(goal);
        project.setCompileStatus(ResearchProjectCompileStatus.PENDING);
        project.setStatus(ResearchProjectStatus.ACTIVE);
        return researchProjectRepository.save(project);
    }

    private Source ensureTextSource(ResearchProject project, User creator, String title, String content) {
        String legacyTitle = "访谈模式笔记".equals(title) ? "Interview Pattern Notes" : "Activation Funnel Review";
        Source source = sourceRepository.findByResearchProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(project.getId()).stream()
                .filter(item -> matchesAny(item.getTitle(), title, legacyTitle))
                .findFirst()
                .orElseGet(Source::new);
        source.setSpaceId(project.getSpaceId());
        source.setResearchProjectId(project.getId());
        source.setTitle(title);
        source.setSourceType(SourceType.TEXT);
        source.setRawTextObjectKey("dev/raw-text/source/" + project.getId() + "/" + title + ".txt");
        source.setContentHash(sha256(content));
        source.setImportStatus(SourceImportStatus.READY);
        source.setCompileStatus(SourceCompileStatus.PENDING);
        source.setTokenCount(content.split("\\s+").length);
        source.setCreatedBy(creator.getId());
        return sourceRepository.save(source);
    }

    private void ensureArticleCard(ResearchProject project, Source source, String title, String summary) {
        ArticleCard card = articleCardRepository.findBySourceId(source.getId()).orElseGet(ArticleCard::new);
        card.setSpaceId(project.getSpaceId());
        card.setResearchProjectId(project.getId());
        card.setSourceId(source.getId());
        card.setTitle(title);
        card.setSummary(summary);
        card.setKeyPointsJson(writeJson(List.of("先理解价值再配置", "优先给出引导示例", "建议必须回到证据")));
        card.setTagsJson(writeJson(List.of("新手引导", "激活", "证据")));
        card.setEvidenceQuotesJson(writeJson(List.of(summary)));
        card.setCardStatus(PersonalCardStatus.READY);
        articleCardRepository.save(card);
    }

    private void ensureConceptCard(ResearchProject project, String name, String definition, String explanation) {
        ConceptCard card = conceptCardRepository.findByResearchProjectIdAndNormalizedName(project.getId(), name.toLowerCase(Locale.ROOT))
                .orElseGet(ConceptCard::new);
        card.setSpaceId(project.getSpaceId());
        card.setResearchProjectId(project.getId());
        card.setName(name);
        card.setNormalizedName(name.toLowerCase(Locale.ROOT));
        card.setDefinition(definition);
        card.setExplanation(explanation);
        card.setUseCasesJson(writeJson(List.of("示例优先的新手引导", "激活清单重设计")));
        card.setCommonMisunderstandingsJson(writeJson(List.of("不是教程堆砌", "不是把配置简单延后")));
        card.setEvidenceQuotesJson(writeJson(List.of(definition)));
        card.setConfidence(BigDecimal.valueOf(0.83d));
        card.setCardStatus(PersonalCardStatus.READY);
        conceptCardRepository.save(card);
    }

    private MethodologyCard ensureMethodologyCard(Long spaceId, Long projectId, Long createdBy, String name, String scene, String problemType) {
        MethodologyCard card = methodologyCardRepository.findBySpaceIdAndResearchProjectIdIsNullAndNameAndCardSource(spaceId, name, MethodologyCardSource.USER_CREATED)
                .orElseGet(MethodologyCard::new);
        card.setSpaceId(spaceId);
        card.setResearchProjectId(projectId);
        card.setName(name);
        card.setScene(scene);
        card.setProblemType(problemType);
        card.setWorkflowJson(writeJson(List.of(
                "重述决策问题",
                "梳理证据与阻力主题",
                "提出有优先级的干预方案",
                "明确取舍与发布风险"
        )));
        card.setRequiredConceptsJson(writeJson(List.of("激活", "引导式激活")));
        card.setOutputStructureJson(writeJson(List.of("结论", "证据", "建议", "风险")));
        card.setQualityChecklistJson(writeJson(List.of("基于证据", "易于扫读", "便于决策")));
        card.setCardSource(MethodologyCardSource.USER_CREATED);
        card.setCardScope(MethodologyCardScope.PROJECT);
        card.setStatus(MethodologyCardStatus.ACTIVE);
        card.setVersion(1);
        card.setCreatedBy(createdBy);
        return methodologyCardRepository.save(card);
    }

    private ChatSession ensureChatSession(User user, Space space, KnowledgeBase knowledgeBase, String title) {
        ChatSession session = chatSessionRepository.findBySpaceIdAndStatusOrderByUpdatedAtDesc(space.getId(), ChatSessionStatus.ACTIVE).stream()
                .filter(item -> matchesAny(item.getTitle(), title, "Onboarding evidence review"))
                .findFirst()
                .orElseGet(ChatSession::new);
        session.setUserId(user.getId());
        session.setSpaceId(space.getId());
        session.setSessionType(ChatSessionType.TEAM_CHAT);
        session.setSessionKind(ChatSessionKind.FORMAL);
        session.setTitle(title);
        session.setScopeType(ChatScopeType.KNOWLEDGE_BASE);
        session.setScopeIdsSnapshotJson(writeJson(List.of(knowledgeBase.getId())));
        session.setStatus(ChatSessionStatus.ACTIVE);
        session.setRuntimeStatus(ChatRuntimeStatus.IDLE);
        session.setLastActiveAt(LocalDateTime.now());
        ChatSession saved = chatSessionRepository.save(session);

        ChatSessionScope scope = chatSessionScopeRepository.findBySessionIdOrderByIdAsc(saved.getId()).stream()
                .findFirst()
                .orElseGet(ChatSessionScope::new);
        scope.setSessionId(saved.getId());
        scope.setScopeType(ChatScopeType.KNOWLEDGE_BASE.name());
        scope.setScopeId(knowledgeBase.getId());
        chatSessionScopeRepository.save(scope);
        return saved;
    }

    private ChatMessage ensureChatMessages(ChatSession session) {
        List<ChatMessage> existing = chatMessageRepository.findBySessionIdOrderByMessageSeqAsc(session.getId());
        ChatMessage userMessage = existing.stream()
                .filter(item -> item.getMessageSeq() == 1)
                .findFirst()
                .orElseGet(ChatMessage::new);
        userMessage.setSessionId(session.getId());
        userMessage.setMessageSeq(1);
        userMessage.setRole(ChatMessageRole.USER);
        userMessage.setContent("当前证据如何解释新手引导阻力，最高杠杆的干预点是什么？");
        userMessage.setMessageType(ChatMessageType.TEXT);
        userMessage.setStatus(ChatMessageStatus.COMPLETED);
        chatMessageRepository.save(userMessage);

        ChatMessage assistantMessage = existing.stream()
                .filter(item -> item.getMessageSeq() == 2)
                .findFirst()
                .orElseGet(ChatMessage::new);
        assistantMessage.setSessionId(session.getId());
        assistantMessage.setMessageSeq(2);
        assistantMessage.setRole(ChatMessageRole.ASSISTANT);
        assistantMessage.setContent("""
                当前证据反复指向一个问题：用户还没理解产品价值，就被要求先完成工作区配置。

                最高杠杆的改进是先给出引导式示例空间，再把配置拆成后续逐步完成的步骤。这个判断同时得到了访谈反馈和漏斗行为的支持。
                """);
        assistantMessage.setMessageType(ChatMessageType.TEXT);
        assistantMessage.setStatus(ChatMessageStatus.COMPLETED);
        return chatMessageRepository.save(assistantMessage);
    }

    private Artifact ensureArtifact(
            Space space,
            User owner,
            ResearchProject project,
            ChatSession session,
            ChatMessage message,
            String title,
            String content,
            ArtifactStatus status
    ) {
        Artifact artifact = artifactRepository.findBySpaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(space.getId()).stream()
                .filter(item -> matchesAny(item.getTitle(), title, "Q3 Onboarding Recommendation Brief"))
                .findFirst()
                .orElseGet(Artifact::new);
        artifact.setUserId(owner.getId());
        artifact.setSpaceId(space.getId());
        artifact.setResearchProjectId(project.getId());
        artifact.setCreatedFromSessionId(session.getId());
        artifact.setCreatedFromMessageId(message.getId());
        artifact.setArtifactType(ArtifactType.BRIEFING);
        artifact.setTitle(title);
        artifact.setContent(content);
        artifact.setSourceScopeType(ArtifactScopeType.RESEARCH_PROJECT);
        artifact.setStatus(status);
        return artifactRepository.save(artifact);
    }

    private void ensureSynthesisCard(ResearchProject project, Artifact artifact, User creator) {
        SynthesisCard card = synthesisCardRepository.findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(project.getId(), project.getSpaceId()).stream()
                .filter(item -> artifact.getId().equals(item.getSourceArtifactId()) || matchesAny(item.getTitle(), "新手引导干预综合结论", "Onboarding intervention synthesis"))
                .findFirst()
                .orElseGet(SynthesisCard::new);
        card.setSpaceId(project.getSpaceId());
        card.setResearchProjectId(project.getId());
        card.setSourceArtifactId(artifact.getId());
        card.setTitle("新手引导干预综合结论");
        card.setSummary("在当前证据下，先给引导示例再进入配置，是最稳妥也最有说服力的干预方向。");
        card.setInsightsJson(writeJson(List.of(
                "价值理解应先于配置要求",
                "进度提示优于空白配置表单",
                "建议必须配套明确负责人"
        )));
        card.setEvidenceQuotesJson(writeJson(List.of("引导式示例空间是当前最具杠杆的改进方案。")));
        card.setCardStatus(PersonalCardStatus.READY);
        card.setCreatedBy(creator.getId());
        synthesisCardRepository.save(card);
    }

    private WikiPage ensureWikiPage(Space space, User creator, String title, String content, boolean publish) {
        WikiPage page = wikiPageRepository.findBySpaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(space.getId()).stream()
                .filter(item -> matchesAny(item.getTitle(), title, "Research Operating Principles"))
                .findFirst()
                .orElseGet(WikiPage::new);
        page.setSpaceId(space.getId());
        page.setTitle(title);
        page.setContent(content);
        page.setStatus(publish ? WikiPageStatus.PUBLISHED : WikiPageStatus.DRAFT);
        page.setIndexStatus(publish ? WikiIndexStatus.INDEXED : WikiIndexStatus.PENDING);
        page.setCreatedBy(creator.getId());
        page.setUpdatedBy(creator.getId());
        return wikiPageRepository.save(page);
    }

    private void ensureWikiVersion(WikiPage wikiPage, User creator) {
        boolean exists = wikiPageVersionRepository.findByWikiPageIdOrderByVersionNoAsc(wikiPage.getId()).stream()
                .anyMatch(version -> version.getVersionNo() == 1);
        WikiPageVersion version = wikiPageVersionRepository.findByWikiPageIdOrderByVersionNoAsc(wikiPage.getId()).stream()
                .filter(item -> item.getVersionNo() == 1)
                .findFirst()
                .orElseGet(WikiPageVersion::new);
        version.setWikiPageId(wikiPage.getId());
        version.setVersionNo(1);
        version.setTitle(wikiPage.getTitle());
        version.setContent(wikiPage.getContent());
        version.setChangeNote("初始化开发环境示例内容");
        version.setCreatedBy(creator.getId());
        WikiPageVersion saved = wikiPageVersionRepository.save(version);
        wikiPage.setPublishedVersionId(saved.getId());
        wikiPageRepository.save(wikiPage);
    }

    private void ensureUserMemory(User user, String summary) {
        UserMemory memory = userMemoryRepository.findByUserId(user.getId()).orElseGet(UserMemory::new);
        memory.setUserId(user.getId());
        memory.setMemoryWriteEnabled(true);
        memory.setSummary(summary);
        memory.setPreferencesJson(writeJson(Map.of("preferred_synthesis_style", summary)));
        memory.setStyleProfileJson(writeJson(Map.of("tone", "concise", "evidence", "high")));
        memory.setHabitProfileJson(writeJson(Map.of("reviewRhythm", "weekly")));
        userMemoryRepository.save(memory);
    }

    private void ensureSpaceMemory(User user, Space space, String topic, String summary) {
        SpaceMemory memory = spaceMemoryRepository.findByUserIdAndSpaceId(user.getId(), space.getId()).orElseGet(SpaceMemory::new);
        memory.setUserId(user.getId());
        memory.setSpaceId(space.getId());
        memory.setTopic(topic);
        memory.setSummary(summary);
        memory.setFocusedSourcesJson(writeJson(List.of("AI 产品研究资料库", "发布准备台")));
        memory.setResolvedEntitiesJson(writeJson(List.of("新手引导", "发布评审")));
        memory.setArtifactPreferencesJson(writeJson(Map.of("defaultArtifactType", "BRIEFING")));
        memory.setConversationPatternsJson(writeJson(List.of(Map.of("topic", topic, "summary", summary))));
        memory.setExpiresAt(LocalDateTime.now().plusDays(30));
        spaceMemoryRepository.save(memory);
    }

    private void ensureMemoryItem(Long userId, Long spaceId, MemoryType type, String topic, String summary, boolean pin) {
        boolean exists = memoryItemRepository.findCandidates(userId, spaceId, type, topic).stream().findFirst().isPresent();
        if (exists) {
            return;
        }
        MemoryItem item = new MemoryItem();
        item.setUserId(userId);
        item.setSpaceId(spaceId);
        item.setMemoryType(type);
        item.setTopic(topic);
        item.setSummary(summary);
        item.setSourceType("MANUAL");
        item.setImportanceScore(BigDecimal.valueOf(0.9d));
        item.setConfidenceScore(BigDecimal.valueOf(0.95d));
        item.setPin(pin);
        memoryItemRepository.save(item);
    }

    private Task ensureTask(
            User user,
            Space space,
            ResearchProject project,
            TaskType type,
            String targetType,
            Long targetId,
            TaskStatus status,
            Object input,
            Object output,
            String errorMessage,
            Long resultRefId
    ) {
        String key = "dev-seed-" + type + "-" + targetId;
        return taskRepository.findByIdempotencyKey(key).orElseGet(() -> {
            Task task = new Task();
            task.setUserId(user.getId());
            task.setSpaceId(space.getId());
            task.setResearchProjectId(project == null ? null : project.getId());
            task.setTaskType(type);
            task.setTargetType(targetType);
            task.setTargetId(targetId);
            task.setTaskStatus(status);
            task.setIdempotencyKey(key);
            task.setInputJson(writeJson(input));
            task.setOutputJson(writeJson(output));
            task.setErrorMessage(errorMessage);
            task.setRetryCount(status == TaskStatus.FAILED ? 1 : 0);
            task.setMaxRetryCount(3);
            task.setResultRefType(resultRefId == null ? null : "ARTIFACT");
            task.setResultRefId(resultRefId);
            task.setStartedAt(LocalDateTime.now().minusMinutes(5));
            task.setFinishedAt(LocalDateTime.now().minusMinutes(4));
            return taskRepository.save(task);
        });
    }

    private void ensureTaskAttempt(Task task, TaskStatus status, String errorMessage) {
        if (taskAttemptRepository.findByTaskIdAndAttemptNo(task.getId(), 1).isPresent()) {
            return;
        }
        TaskAttempt attempt = new TaskAttempt();
        attempt.setTaskId(task.getId());
        attempt.setAttemptNo(1);
        attempt.setWorkerId("dev-seeder");
        attempt.setStatus(status);
        attempt.setStartedAt(task.getStartedAt());
        attempt.setFinishedAt(task.getFinishedAt());
        attempt.setErrorCode(status == TaskStatus.FAILED ? "DEV_SAMPLE_FAILURE" : null);
        attempt.setErrorMessage(errorMessage);
        taskAttemptRepository.save(attempt);
    }

    private void ensureTaskEvent(Task task, TaskEventType eventType, TaskStatus from, TaskStatus to, String message) {
        boolean exists = taskEventRepository.findByTaskId(task.getId(), org.springframework.data.domain.PageRequest.of(0, 20))
                .stream()
                .anyMatch(event -> event.getEventType() == eventType);
        if (exists) {
            return;
        }
        TaskEvent event = new TaskEvent();
        event.setTaskId(task.getId());
        event.setEventType(eventType);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setMessage(message);
        event.setPayloadJson(writeJson(Map.of("source", "dev-seeder")));
        event.setCreatedBy(task.getUserId());
        taskEventRepository.save(event);
    }

    private void ensureAuditLog(Long operatorId, Long spaceId, AuditAction action, String targetType, Long targetId) {
        boolean exists = auditLogRepository.findAll().stream()
                .anyMatch(log -> log.getOperatorId().equals(operatorId) && log.getAction() == action && targetId.equals(log.getTargetId()));
        if (exists) {
            return;
        }
        AuditLog log = new AuditLog();
        log.setOperatorId(operatorId);
        log.setSpaceId(spaceId);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setRequestId("dev-seed-" + action.name().toLowerCase(Locale.ROOT));
        log.setIpAddress("127.0.0.1");
        log.setUserAgent("NoteWeave Dev Seeder");
        log.setBeforeJson("{}");
        log.setAfterJson("{}");
        auditLogRepository.save(log);
    }

    private void ensureLlmCallLog(Long spaceId, Long sessionId, Long messageId, Long taskId, Long artifactId) {
        boolean exists = llmCallLogRepository.findAll().stream()
                .anyMatch(log -> taskId.equals(log.getTaskId()));
        if (exists) {
            return;
        }
        LlmCallLog log = new LlmCallLog();
        log.setUserId(28L);
        log.setSpaceId(spaceId);
        log.setSessionId(sessionId);
        log.setMessageId(messageId);
        log.setTaskId(taskId);
        log.setArtifactId(artifactId);
        log.setScene("artifact_generate");
        log.setProvider("stub");
        log.setModel("noteweave-stub-chat");
        log.setPromptHash(sha256("dev-seed-prompt"));
        log.setInputTokens(640);
        log.setOutputTokens(220);
        log.setTotalTokens(860);
        log.setLatencyMs(1240L);
        log.setSuccess(true);
        llmCallLogRepository.save(log);
    }

    private RagEvalCase ensureEvalCase(Long spaceId, Long createdBy) {
        RagEvalCase item = ragEvalCaseRepository.findBySpaceIdOrderByUpdatedAtDescIdDesc(spaceId).stream()
                .filter(candidate -> matchesAny(candidate.getName(), "新手引导证据对齐", "Onboarding evidence grounding"))
                .findFirst()
                .orElseGet(RagEvalCase::new);
        item.setSpaceId(spaceId);
        item.setName("新手引导证据对齐");
        item.setQueryText("哪些证据支持采用引导式新手引导？");
        item.setExpectedAnswer("访谈记录和漏斗复盘都支持先给引导示例、再进入配置。");
        item.setExpectedSourceJson(writeJson(List.of("新手引导证据简报")));
        item.setTagsJson(writeJson(List.of("新手引导", "证据对齐")));
        item.setEnabled(true);
        item.setCreatedBy(createdBy);
        return ragEvalCaseRepository.save(item);
    }

    private RagEvalRun ensureEvalRun(Long spaceId, Long startedBy) {
        RagEvalRun run = ragEvalRunRepository.findAll().stream()
                .filter(item -> item.getSpaceId().equals(spaceId) && matchesAny(item.getName(), "每周检索质检", "Weekly Retrieval QA"))
                .findFirst()
                .orElseGet(RagEvalRun::new);
        run.setSpaceId(spaceId);
        run.setName("每周检索质检");
        run.setStatus("SUCCESS");
        run.setCaseCount(1);
        run.setStartedBy(startedBy);
        run.setStartedAt(LocalDateTime.now().minusHours(3));
        run.setFinishedAt(LocalDateTime.now().minusHours(3).plusMinutes(4));
        run.setSummaryJson(writeJson(Map.of("recallAtK", 1.0, "mrr", 1.0, "citationCoverage", 1.0)));
        return ragEvalRunRepository.save(run);
    }

    private void ensureEvalResult(Long runId, Long caseId, Long answerMessageId) {
        boolean exists = ragEvalResultRepository.findByRunIdOrderByIdAsc(runId).stream()
                .anyMatch(result -> caseId.equals(result.getCaseId()));
        if (exists) {
            return;
        }
        RagEvalResult result = new RagEvalResult();
        result.setRunId(runId);
        result.setCaseId(caseId);
        result.setAnswerMessageId(answerMessageId);
        result.setRecallAtK(BigDecimal.ONE);
        result.setMrr(BigDecimal.ONE);
        result.setCitationCoverage(BigDecimal.ONE);
        result.setGroundednessScore(BigDecimal.valueOf(0.95d));
        result.setAnswerQualityScore(BigDecimal.valueOf(0.93d));
        result.setLatencyMs(1240L);
        result.setAnswerSnapshot("引导式示例新手引导是当前最有证据支撑的干预方案。");
        ragEvalResultRepository.save(result);
    }

    private boolean matchesAny(String actual, String... candidates) {
        if (actual == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && candidate.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize dev seed payload", ex);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute SHA-256", ex);
        }
    }
}
