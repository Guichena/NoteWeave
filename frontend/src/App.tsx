import { useEffect, useState } from "react";
import { useRef } from "react";
import { lazy, Suspense } from "react";
import { routes, type AnswerMode } from "./routes";
import {
  consumeSse,
  ConversationStreamClient
} from "./shared/event-stream";
import { workspaceApi } from "./features/workspace/api";
import { type Workspace } from "./features/workspace/model";
import { sourcesApi } from "./features/sources/api";
import { type SourceAsset } from "./features/sources/model";
import { conversationsApi } from "./features/conversations/api";
import { type Conversation } from "./features/conversations/model";
import { answersApi } from "./features/answers/api";
import { type AnswerRunState } from "./features/answers/model";
import {
  AnswerRunStore,
  createAnswerRunState,
  reduceAnswerRunState
} from "./features/answers/store";
import { knowledgeApi } from "./features/knowledge/api";
import { useKnowledgeState } from "./features/knowledge/useKnowledgeState";
import {
  type KnowledgeCitation,
  type WikiGraphMode,
  type WikiIndexSource,
  type WikiIssue,
  type WikiPage,
  type WikiTaskSummary
} from "./features/knowledge/model";
import { MemoryReviewWorkbench } from "./features/memory/MemoryReviewWorkbench";
import { useExecutionRegistry } from "./features/executions/useExecutionRegistry";
import {
  type ExecutionEvent as StreamEvent,
  type ExecutionTask as TaskStatus
} from "./features/executions/model";
import { artifactsApi } from "./features/artifacts/api";
import { useArtifactState } from "./features/artifacts/useArtifactState";
import { researchApi } from "./features/research/api";
import {
  type ResearchRunSummary,
  type ResearchIntentCompletionContract,
  type ResearchRecoveryTargets,
  type ResearchRowSummary,
  type ResearchFinalAnswer,
  type ResearchReportStructure,
  type ResearchCounterfactualSummary,
  type ResearchRunSummarySnapshot,
  type ResearchHistoryFilter,
  type SignalTone,
  type SignalChip,
  type ResearchTimelineMilestone,
  type ResearchTimelinePathSummary,
  type SaveResearchReportSource
} from "./features/research/model";
import { useResearchState } from "./features/research/useResearchState";

import {
  buildArtifactJobInputs,
  buildArtifactStudioSkill,
  buildInitialArtifactFormValues,
  isArtifactFormReady,
  readArtifactLanguage,
  readArtifactUrl,
  type ArtifactSkillSummary,
  type ArtifactStudioField,
  type ArtifactStudioSkill
} from "./artifactStudio";
import { type ArtifactHistoryItem } from "./artifactHistory";
import { buildArtifactSidebarState } from "./artifactSidebar";
import { type ArtifactRuntimeTrace } from "./artifactRuntimeTrace";
import {
  buildResearchReportExportArtifact,
  buildResearchReportExportStatusMessage
} from "./researchReportDelivery";
import {
  buildWaitContextDetailLines,
  buildWaitContextNarrative,
  buildWaitContextSignalChips,
  summarizeRunStatus,
} from "./runStatus";
import { shouldReuseLatestArtifactVersion } from "./artifactHistorySelection";

const LazyResearchSidebar = lazy(() => import("./features/research/ResearchSidebar").then((module) => ({
  default: module.ResearchSidebar
})));
const LazyArtifactRail = lazy(() => import("./features/artifacts/ArtifactRail").then((module) => ({
  default: module.ArtifactRail
})));
const LazyResearchReportPanel = lazy(() => import("./features/research/ResearchReportPanel").then((module) => ({
  default: module.ResearchReportPanel
})));

type Message = {
  role: "user" | "assistant" | "system";
  content: string;
  answerMode?: AnswerMode;
  citations?: string[];
  answerRunId?: string;
  answerStatus?: string;
  answerError?: string;
};

const DEFAULT_ARTIFACT_STUDIO_SKILL_SUMMARIES: ArtifactSkillSummary[] = [
  {
    skill_key: "resume_highlight",
    display_name: "简历亮点描述",
    description: "把当前工作台资料整理成适合写进简历的项目亮点和影响表述。",
    status: "ACTIVE",
    input_schema: {
      type: "object",
      properties: {
        language: {
          type: "string",
          default: "zh-CN",
          oneOf: [
            { const: "zh-CN", title: "中文（简体）" },
            { const: "en", title: "English" },
            { const: "zh-EN", title: "中英双语" }
          ]
        }
      }
    },
    default_input_hints: ["强调架构设计", "强调工程复杂度", "适合校招简历"]
  },
  {
    skill_key: "study_guide",
    display_name: "学习指南",
    description: "按知识点、关键概念和练习建议生成结构化学习材料。",
    status: "ACTIVE",
    input_schema: {
      type: "object",
      properties: {
        language: {
          type: "string",
          default: "zh-CN",
          oneOf: [
            { const: "zh-CN", title: "中文（简体）" },
            { const: "en", title: "English" },
            { const: "zh-EN", title: "中英双语" }
          ]
        }
      }
    },
    default_input_hints: ["突出关键概念", "加入练习路径", "适合新人上手"]
  },
  {
    skill_key: "quiz_pack",
    display_name: "测验题集",
    description: "围绕当前资料生成题目、答案解析和评分要点。",
    status: "ACTIVE",
    input_schema: {
      type: "object",
      properties: {
        language: {
          type: "string",
          default: "zh-CN",
          oneOf: [
            { const: "zh-CN", title: "中文（简体）" },
            { const: "en", title: "English" },
            { const: "zh-EN", title: "中英双语" }
          ]
        }
      }
    },
    default_input_hints: ["区分题型难度", "附标准答案", "保留评分要点"]
  },
  {
    skill_key: "wiki_page",
    display_name: "Wiki 页面",
    description: "沉淀成定义、机制、引用和相关页面齐全的知识页草稿。",
    status: "ACTIVE",
    input_schema: {
      type: "object",
      properties: {
        language: {
          type: "string",
          default: "zh-CN",
          oneOf: [
            { const: "zh-CN", title: "中文（简体）" },
            { const: "en", title: "English" },
            { const: "zh-EN", title: "中英双语" }
          ]
        }
      }
    },
    default_input_hints: ["定义先行", "补充关键机制", "保留相关页面建议"]
  },
  {
    skill_key: "bilibili_course_note_pdf",
    display_name: "B站讲义 PDF",
    description: "面向 B 站视频链接生成图文讲义与 PDF 编译请求。",
    status: "ACTIVE",
    input_schema: {
      type: "object",
      properties: {
        language: {
          type: "string",
          default: "zh-CN",
          oneOf: [
            { const: "zh-CN", title: "中文（简体）" },
            { const: "en", title: "English" },
            { const: "zh-EN", title: "中英双语" }
          ]
        },
        url: { type: "string" }
      },
      required: ["url"]
    },
    default_input_hints: ["填写 B 站视频链接", "保留章节结构", "输出讲义 PDF"]
  }
];

const ARTIFACT_STUDIO_PRESENTATION_ENTRIES = [
    {
      key: "resume_highlight",
      title: "简历亮点描述",
      summary: "把当前工作台资料整理成适合写进简历的项目亮点和影响表述。",
      artifactType: "Resume Highlights",
      sourceHint: "当前工作台资料 / 最新回答",
      runtimeHint: "快速生成",
      badges: ["推荐", "聊天生成"],
      styleHint: "结果导向、动词开头、突出指标与复杂度。",
      promptFocus: "输出适合简历使用的项目亮点描述",
      tone: "blue"
    },
    {
      key: "study_guide",
      title: "学习指南",
      summary: "按知识点、关键概念和练习建议生成结构化学习材料。",
      artifactType: "Study Guide",
      sourceHint: "当前工作台资料",
      runtimeHint: "可扩展为异步",
      badges: ["常用", "结构化"],
      styleHint: "教学口吻、层次清晰、包含复习路径。",
      promptFocus: "输出带章节结构的学习指南",
      tone: "gold"
    },
    {
      key: "quiz_pack",
      title: "测验题集",
      summary: "围绕当前资料生成题目、答案解析和评分要点。",
      artifactType: "Quiz",
      sourceHint: "当前工作台资料 / 选中资料",
      runtimeHint: "可扩展为异步",
      badges: ["练习", "结构化"],
      styleHint: "区分难度，附带标准答案和解析。",
      promptFocus: "输出可直接使用的测验题集",
      tone: "green"
    },
    {
      key: "wiki_page",
      title: "Wiki 页面",
      summary: "沉淀成定义、机制、引用和相关页面齐全的知识页草稿。",
      artifactType: "Wiki Page",
      sourceHint: "当前工作台资料 / 已保存产物",
      runtimeHint: "建议校验后入库",
      badges: ["知识沉淀", "需校验"],
      styleHint: "定义明确、结构稳定、保留相关页面建议。",
      promptFocus: "输出适合 Wiki 的知识页草稿",
      tone: "violet"
    },
    {
      key: "bilibili_course_note_pdf",
      title: "B站讲义 PDF",
      summary: "面向 B 站视频链接生成图文讲义与 PDF 编译请求。",
      artifactType: "Course Note PDF",
      sourceHint: "B站链接 / 内置能力",
      runtimeHint: "异步 + System MCP",
      badges: ["System MCP", "异步"],
      styleHint: "专业讲义体，保留章节、图示和总结。",
      promptFocus: "输出 B 站讲义 PDF 生成请求",
      tone: "rose"
    }
  ] satisfies Array<{ key: string } & Partial<ArtifactStudioSkill>>;

const ARTIFACT_STUDIO_PRESENTATION: Record<string, Partial<ArtifactStudioSkill>> = Object.fromEntries(
  ARTIFACT_STUDIO_PRESENTATION_ENTRIES.map((skill) => [skill.key, skill])
);

const DEFAULT_ARTIFACT_STUDIO_SKILLS: ArtifactStudioSkill[] = DEFAULT_ARTIFACT_STUDIO_SKILL_SUMMARIES.map((skill) =>
  buildArtifactStudioSkill(skill, ARTIFACT_STUDIO_PRESENTATION[skill.skill_key])
);

function sortArtifactStudioSkills(skills: ArtifactStudioSkill[]): ArtifactStudioSkill[] {
  const order = new Map(DEFAULT_ARTIFACT_STUDIO_SKILLS.map((skill, index) => [skill.key, index]));
  return skills
    .slice()
    .sort((left, right) => {
      const leftOrder = order.get(left.key) ?? Number.MAX_SAFE_INTEGER;
      const rightOrder = order.get(right.key) ?? Number.MAX_SAFE_INTEGER;
      if (leftOrder !== rightOrder) {
        return leftOrder - rightOrder;
      }
      return left.title.localeCompare(right.title, "zh-CN");
    });
}

function resolveArtifactSkillTitle(skillKey: string, skills: ArtifactStudioSkill[]): string {
  return skills.find((skill) => skill.key === skillKey)?.title || skillKey;
}

export function App() {
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const {
    getExecution,
    loadExecution
  } = useExecutionRegistry(workspace?.workspace_id ?? "");
  const [conversation, setConversation] = useState<Conversation | null>(null);
  const [conversationStreamConnected, setConversationStreamConnected] = useState(false);
  const answerRunStoreRef = useRef(new AnswerRunStore());
  const [mode, setMode] = useState<AnswerMode>("qa");
  const [sourceText, setSourceText] = useState("NoteWeave 支持在同一个研究工作台里使用问答 RAG、Marginalia 式 Note 检索链路和 WebKonra / WeKnora 式 Wiki 检索链路。");
  const [question, setQuestion] = useState("Note 和 Wiki 两种检索方式有什么区别？");
  const [messages, setMessages] = useState<Message[]>([
    {
      role: "assistant",
      content: "先创建工作台并上传一段资料，然后就可以在同一个聊天框里切换问答、Note、Wiki 三种链路。"
    }
  ]);
  const [view, setView] = useState<"chat" | "wiki" | "memory" | "research">("chat");
  const [lastAssistantMessageId, setLastAssistantMessageId] = useState("");
  const [noteTitle, setNoteTitle] = useState("工作台整理笔记");
  const [artifactStudioSkills, setArtifactStudioSkills] = useState<ArtifactStudioSkill[]>(DEFAULT_ARTIFACT_STUDIO_SKILLS);
  const {
    artifactJobs,
    latestArtifactVersion,
    selectedArtifactHistoryVersion,
    selectedArtifactHistoryKey,
    artifactHistoryLoadingKey,
    artifactSavedSourceByVersionId,
    artifactWritebackByVersionId,
    clear: clearArtifactState,
    refreshJobs: refreshArtifactJobs,
    selectHistoryVersion,
    recordSavedSource,
    recordWriteback,
    applyRollback
  } = useArtifactState(workspace?.workspace_id ?? "");
  const [selectedArtifactSkillKey, setSelectedArtifactSkillKey] = useState("resume_highlight");
  const [artifactComposerOpen, setArtifactComposerOpen] = useState(false);
  const [artifactFormValues, setArtifactFormValues] = useState<Record<string, string>>(
    buildInitialArtifactFormValues(DEFAULT_ARTIFACT_STUDIO_SKILLS[0])
  );
  const [artifactCustomInstruction, setArtifactCustomInstruction] = useState("");
  const [wikiTitle, setWikiTitle] = useState("工作台知识页");
  const [wikiDraft, setWikiDraft] = useState("");
  const [wikiAppendDraft, setWikiAppendDraft] = useState("");
  const [wikiSearch, setWikiSearch] = useState("");
  const [wikiRenameTitle, setWikiRenameTitle] = useState("");
  const [wikiGraphMode, setWikiGraphMode] = useState<WikiGraphMode>("overview");
  const [wikiKindFilter, setWikiKindFilter] = useState<string>("ALL");
  const [wikiGraphKindFilters, setWikiGraphKindFilters] = useState<string[]>([]);
  const [wikiGraphSearch, setWikiGraphSearch] = useState("");
  const [wikiIssueTypeFilter, setWikiIssueTypeFilter] = useState<string>("ALL");
  const [wikiIssueScopeFilter, setWikiIssueScopeFilter] = useState<"ALL" | "AUTO" | "MANUAL">("ALL");
  const [wikiIssueSeverityFilter, setWikiIssueSeverityFilter] = useState<string>("ALL");
  const [wikiIssuePageFilter, setWikiIssuePageFilter] = useState<"ALL" | "CURRENT">("ALL");
  const {
    wikiHome,
    wikiIndex,
    wikiEnabled,
    wikiStats,
    wikiIssues,
    wikiLog,
    wikiGraph,
    wikiRebuildAdvice,
    selectedWikiItemId,
    selectedWikiDetail,
    selectedWikiVersions,
    selectedWikiVersionDetail,
    selectedWikiLog,
    wikiSearchResults,
    filteredWikiIssues,
    refreshHome: refreshWikiHome,
    refreshFromServer: refreshWikiFromServer,
    clearSelection: clearWikiSelection,
    selectPage: selectKnowledgePage,
    refreshGraph: refreshWikiGraph,
    loadVersion: loadKnowledgeVersion,
    restoreLatestVersion: restoreLatestKnowledgeVersion,
    findFirstIssue: findFirstWikiIssue,
    updateEnabled: updateWikiEnabled
  } = useKnowledgeState({
    workspaceId: workspace?.workspace_id ?? "",
    search: wikiSearch,
    issueType: wikiIssueTypeFilter,
    issueSeverity: wikiIssueSeverityFilter,
    issueScope: wikiIssueScopeFilter,
    issuePage: wikiIssuePageFilter
  });
  useEffect(() => {
    setWikiRenameTitle(selectedWikiDetail?.title ?? "");
  }, [selectedWikiDetail?.item_id, selectedWikiDetail?.title]);
  const [sources, setSources] = useState<SourceAsset[]>([]);
  const [selectedQaSourceIds, setSelectedQaSourceIds] = useState<string[]>([]);
  const [latestTaskId, setLatestTaskId] = useState("");
  const latestTaskExecution = getExecution(latestTaskId);
  const latestTask = latestTaskExecution?.task ?? null;
  const taskEvents = latestTaskExecution?.events ?? [];
  const [researchQuestion, setResearchQuestion] = useState("请围绕当前主题开展 Deep Research，并明确给出已验证结论、冲突点和后续恢复建议。");
  const [researchProfile, setResearchProfile] = useState("default");
  const [researchGoal, setResearchGoal] = useState("沉淀一份可验证、可恢复的研究结论摘要。");
  const [researchDeliverableFormat, setResearchDeliverableFormat] = useState("Evidence-backed research report");
  const [researchConstraintsText, setResearchConstraintsText] = useState("必须显式区分已验证结论、冲突点与后续恢复动作。");
  const [researchTimeRange, setResearchTimeRange] = useState("");
  const [researchDepth, setResearchDepth] = useState("STANDARD");
  const [researchType, setResearchType] = useState("AUTO");
  const [selectedResearchSourceIds, setSelectedResearchSourceIds] = useState<string[]>([]);
  const [latestResearchTaskId, setLatestResearchTaskId] = useState("");
  const latestResearchTaskExecution = getExecution(latestResearchTaskId);
  const latestResearchTask = latestResearchTaskExecution?.task ?? null;
  const researchTaskEvents = latestResearchTaskExecution?.events ?? [];
  const {
    researchRuns,
    currentResearchRunId,
    currentResearchRun,
    clear: resetResearchState,
    prepareRun: prepareResearchRun,
    loadHistory: fetchResearchRunHistory,
    loadRunDetail: fetchResearchRunDetail
  } = useResearchState({
    workspaceId: workspace?.workspace_id ?? ""
  });
  const [researchHistoryFilter, setResearchHistoryFilter] = useState<ResearchHistoryFilter>("ALL");
  const [focusedResearchSourceId, setFocusedResearchSourceId] = useState("");
  const [researchDetailOpen, setResearchDetailOpen] = useState(false);
  const sourceById = new Map(sources.map((source) => [source.source_id, source] as const));
  const [isBusy, setIsBusy] = useState(false);
  const [status, setStatus] = useState("准备就绪");
  const selectedArtifactSkill =
    artifactStudioSkills.find((skill) => skill.key === selectedArtifactSkillKey)
    ?? artifactStudioSkills[0]
    ?? DEFAULT_ARTIFACT_STUDIO_SKILLS[0];

  useEffect(() => {
    if (!workspace || !conversation) {
      setConversationStreamConnected(false);
      return;
    }
    const streamPath = `/api/v2/workspaces/${workspace.workspace_id}`
      + `/conversations/${conversation.conversation_id}/events`;
    const client = new ConversationStreamClient(streamPath, {
      onConnectionChange: (connected) => {
        if (!connected) {
          setConversationStreamConnected(false);
        }
      },
      onError: () => setStatus("会话流正在重连，回答状态将从快照恢复"),
      onEvent: (event) => {
        try {
          const updates = answerRunStoreRef.current.applyConversationEvent(event);
          if (event.event === "conversation.snapshot") {
            setConversationStreamConnected(true);
          }
          applyAnswerRunUpdates(updates);
        } catch {
          setStatus("会话快照格式无效，将等待下一次重连恢复");
        }
      }
    });

    function applyAnswerRunUpdates(updates: AnswerRunState[]) {
      if (updates.length === 0) {
        return;
      }
      setMessages((current) => updates.reduce(
        (messages, answer) => updateAssistantMessageByRun(messages, answer.runId, answer),
        current
      ));
    }

    client.start();
    return () => {
      client.stop();
      setConversationStreamConnected(false);
      answerRunStoreRef.current.clear("会话已切换");
    };
  }, [workspace?.workspace_id, conversation?.conversation_id]);

  useEffect(() => {
    let cancelled = false;
    void artifactsApi.listSkills()
      .then((skills) => {
        if (cancelled) {
          return;
        }
        const loadedSkills = sortArtifactStudioSkills(
          skills.length > 0
            ? skills.map((skill) => buildArtifactStudioSkill(skill, ARTIFACT_STUDIO_PRESENTATION[skill.skill_key]))
            : DEFAULT_ARTIFACT_STUDIO_SKILLS
        );
        setArtifactStudioSkills(loadedSkills);
        setSelectedArtifactSkillKey((current) => (
          loadedSkills.some((skill) => skill.key === current)
            ? current
            : (loadedSkills[0]?.key ?? "resume_highlight")
        ));
      })
      .catch(() => {
        if (cancelled) {
          return;
        }
        setArtifactStudioSkills(DEFAULT_ARTIFACT_STUDIO_SKILLS);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    setArtifactFormValues((current) => buildInitialArtifactFormValues(selectedArtifactSkill, current));
  }, [selectedArtifactSkill.key]);

  useEffect(() => {
    if (!workspace) {
      clearArtifactState();
      return;
    }
    void loadArtifactJobs(workspace.workspace_id);
  }, [workspace]);

  useEffect(() => {
    if (!workspace || latestTask?.task_type !== "ARTIFACT_JOB") {
      return;
    }
    void loadArtifactJobs(workspace.workspace_id);
  }, [workspace, latestTask?.task_id, latestTask?.task_status, latestTask?.progress_phase]);

  useEffect(() => {
    setSelectedResearchSourceIds((current) => current.filter((sourceId) => sources.some((source) => source.source_id === sourceId)));
  }, [sources]);

  useEffect(() => {
    if (!focusedResearchSourceId) {
      return;
    }
    const timeoutId = window.setTimeout(() => {
      const target = document.getElementById(`research-source-scope-${focusedResearchSourceId}`);
      if (target) {
        target.scrollIntoView({ behavior: "smooth", block: "center" });
      }
    }, 0);
    return () => window.clearTimeout(timeoutId);
  }, [focusedResearchSourceId, view, sources.length]);

  async function loadArtifactJobs(_workspaceId: string) {
    try {
      return await refreshArtifactJobs();
    } catch {
      return [];
    }
  }

  async function openArtifactHistoryVersion(item: ArtifactHistoryItem) {
    if (!workspace) {
      return;
    }
    try {
      await selectHistoryVersion({
        key: item.key,
        artifactJobId: item.artifactJobId,
        versionNo: item.versionNo
      }, shouldReuseLatestArtifactVersion({
        artifactJobId: item.artifactJobId,
        versionNo: item.versionNo
      }, latestArtifactVersion));
    } catch {
      setStatus("历史产物版本详情加载失败");
    }
  }

  async function saveArtifactVersionAsSource(version: { artifact_job_id: string; version_no: number }) {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run("保存产物为资料", async () => {
      const saved = await artifactsApi.saveVersionAsSource(
        workspace.workspace_id,
        version.artifact_job_id,
        version.version_no
      );
      recordSavedSource(artifactVersionSaveKey(version), saved.source_id);
      setSources(await sourcesApi.list(workspace.workspace_id));
    });
  }

  async function writeArtifactVersionToKnowledge(
    version: { artifact_job_id: string; version_no: number; title: string },
    itemType: "NOTE" | "WIKI"
  ) {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    const label = itemType === "NOTE" ? "Note" : "Wiki";
    await run(`写回 ${label}`, async () => {
      await artifactsApi.writebackVersion(
        workspace.workspace_id,
        version.artifact_job_id,
        version.version_no,
        { item_type: itemType, title: version.title }
      );
      const key = artifactVersionSaveKey(version);
      recordWriteback(key, itemType);
    });
  }

  async function regenerateArtifactVersion(version: { artifact_job_id: string; version_no: number }) {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run("再生成产物版本", async () => {
      await artifactsApi.regenerateVersion(
        workspace.workspace_id,
        version.artifact_job_id,
        version.version_no
      );
      await loadArtifactJobs(workspace.workspace_id);
    });
  }

  async function rollbackArtifactVersion(version: { artifact_job_id: string; version_no: number }) {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run("追加式回滚产物", async () => {
      const rolledBack = await artifactsApi.rollbackVersion(
        workspace.workspace_id,
        version.artifact_job_id,
        version.version_no
      );
      applyRollback(rolledBack);
      await loadArtifactJobs(workspace.workspace_id);
    });
  }

  async function compareArtifactWithPreviousVersion(version: { artifact_job_id: string; version_no: number }) {
    if (!workspace || version.version_no <= 1) {
      setStatus("当前版本没有可比较的上一版本");
      return;
    }
    try {
      setIsBusy(true);
      setStatus("比较产物版本中...");
      const comparison = await artifactsApi.compareVersions(
        workspace.workspace_id,
        version.artifact_job_id,
        version.version_no - 1,
        version.version_no
      );
      setStatus(comparison.summary);
    } catch (error) {
      setStatus(error instanceof Error ? error.message : "比较产物版本失败");
    } finally {
      setIsBusy(false);
    }
  }

  function downloadArtifactVersionPdf(version: {
    artifact_job_id: string;
    version_no: number;
    runtime_trace?: ArtifactRuntimeTrace | null;
    files?: Array<{ file_format: string; status: string }>;
  }) {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    const exportStatus = version.runtime_trace?.export_trace?.status ?? "";
    const hasStoredPdf = Array.isArray(version.files)
      && version.files.some((file) => file.file_format === "PDF" && file.status === "READY");
    if (!hasStoredPdf && exportStatus !== "COMPILED") {
      setStatus("该版本尚未生成可下载的 PDF");
      return;
    }
    window.open(
      artifactsApi.exportPdfUrl(workspace.workspace_id, version.artifact_job_id, version.version_no),
      "_blank",
      "noopener,noreferrer"
    );
  }

  async function createWorkspace() {
    await run("创建工作台", async () => {
      const created = await workspaceApi.create({
        name: "NoteWeave 研究工作台",
        description: "用于上传资料、持续对话和维护工作台级 Wiki 的研究空间"
      });
      setWorkspace(created);
      setSources([]);
      setSelectedResearchSourceIds([]);
      setLatestTaskId("");
      setLatestResearchTaskId("");
      resetResearchState();
      const createdConversation = await conversationsApi.create(created.workspace_id, {
        title: "默认研究会话",
        conversation_type: "WORKSPACE_CHAT"
      });
      setConversation(createdConversation);
      setMessages((current) => [
        ...current,
        { role: "system", content: `已创建工作台 ${created.name}，并创建默认会话。` }
      ]);
    });
  }

  async function uploadSource() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run("上传资料并解析", async () => {
      const completed = await sourcesApi.uploadText(workspace.workspace_id, sourceText);
      const execution = await loadExecution(completed.task_id);
      const task = execution.task;
      const nextSources = await sourcesApi.list(workspace.workspace_id);
      setLatestTaskId(completed.task_id);
      await refreshWikiHome();
      setSources(nextSources);
      setMessages((current) => [
        ...current,
        {
          role: "system",
          content: `资料已上传并解析：source=${completed.source_id}，parse=${completed.parse_status}，index=${completed.index_status}，task=${task.task_status}。${wikiEnabled ? "Wiki 构建已开启，本次资料变更已进入 Wiki ingest 队列。" : "Wiki 构建未开启，本次只进入问答/Note 检索索引。"}`
        }
      ]);
    });
  }

  function toggleResearchScope(sourceId: string) {
    setSelectedResearchSourceIds((current) => (
      current.includes(sourceId)
        ? current.filter((entry) => entry !== sourceId)
        : [...current, sourceId]
    ));
  }

  function toggleQaScope(sourceId: string) {
    setSelectedQaSourceIds((current) => (
      current.includes(sourceId)
        ? current.filter((entry) => entry !== sourceId)
        : [...current, sourceId]
    ));
  }

  function addResearchSourceToScope(sourceId: string) {
    setSelectedResearchSourceIds((current) => (
      current.includes(sourceId) ? current : [...current, sourceId]
    ));
    setFocusedResearchSourceId(sourceId);
  }

  function removeResearchSourceFromScope(sourceId: string) {
    setSelectedResearchSourceIds((current) => current.filter((entry) => entry !== sourceId));
    setFocusedResearchSourceId(sourceId);
  }

  async function loadResearchRunHistory(preferredRunId?: string) {
    if (!workspace) {
      return [];
    }
    const runs = await fetchResearchRunHistory();
    const nextRunId = preferredRunId || currentResearchRunId;
    if (!nextRunId && runs.length > 0) {
      prepareResearchRun(runs[0].research_run_id);
      await fetchResearchRunDetail(runs[0].research_run_id);
    }
    return runs;
  }

  async function loadResearchRunDetail(researchRunId: string, checkpointNo?: number | null) {
    if (!workspace) {
      return null;
    }
    return fetchResearchRunDetail(researchRunId, checkpointNo);
  }

  async function refreshResearchTask(taskId: string, researchRunId: string) {
    const execution = await loadExecution(taskId);
    const task = execution.task;
    setLatestResearchTaskId(taskId);
    const runs = await loadResearchRunHistory(task.result_ref || researchRunId);
    const detail = await loadResearchRunDetail(task.result_ref || researchRunId);
    return {
      task,
      runs,
      detail
    };
  }

  async function startDeepResearch() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    const nextQuestion = researchQuestion.trim();
    if (!nextQuestion) {
      setStatus("请先填写 Deep Research 问题");
      return;
    }
    if (selectedResearchSourceIds.length === 0) {
      setStatus("请至少选择一份已解析的资料后再启动 Deep Research");
      return;
    }
    const nextProfile = researchProfile.trim() || "default";
    const nextGoal = researchGoal.trim();
    const nextDeliverableFormat = researchDeliverableFormat.trim();
    const nextConstraints = researchConstraintsText
      .split(/\r?\n/)
      .map((item) => item.trim())
      .filter(Boolean);
    const nextTimeRange = researchTimeRange.trim();
    const nextDepth = researchDepth.trim() || "STANDARD";
    const nextResearchType = researchType.trim() || "AUTO";
    await run("启动 Deep Research", async () => {
      const created = await researchApi.createRun(workspace.workspace_id, {
        question: nextQuestion,
        profile: nextProfile,
        research_goal: nextGoal,
        deliverable_format: nextDeliverableFormat,
        constraints: nextConstraints,
        time_range: nextTimeRange,
        depth: nextDepth,
        research_type: nextResearchType,
        source_scope_source_ids: selectedResearchSourceIds
      });
      prepareResearchRun(created.research_run_id);
      const refreshed = await refreshResearchTask(created.task_id, created.research_run_id);
      const createdRunSummary = refreshed.runs.find((run) => run.research_run_id === created.research_run_id) ?? null;
      const createdRunBaseline = findRunContinuityBaseline(refreshed.runs, createdRunSummary);
      const createdRunBaselineLabel = createdRunSummary
        ? buildRunContinuityBaselineLabel(createdRunBaseline, createdRunSummary)
        : "";
      const createdRunContinuityNarrative = createdRunSummary
        ? buildRunContinuityNarrative(createdRunBaseline, createdRunSummary)
        : "";
      setMessages((current) => [
        ...current,
        {
          role: "system",
          content: `已创建 Deep Research：run=${created.research_run_id}，profile=${nextProfile}，depth=${nextDepth}，显式资料范围 ${selectedResearchSourceIds.length} 份。${buildLifecycleCreationNarrative(
            nextQuestion,
            nextGoal,
            nextDepth,
            selectedResearchSourceIds.length
          )}${createdRunBaselineLabel ? ` continuity baseline=${createdRunBaselineLabel}。` : ""}${createdRunContinuityNarrative ? ` ${createdRunContinuityNarrative}` : ""}`
        }
      ]);
    });
  }

  async function refreshCurrentResearchRun() {
    if (!workspace || !currentResearchRunId) {
      setStatus("当前还没有可刷新的 Deep Research run");
      return;
    }
    await run("刷新 Deep Research", async () => {
      if (latestResearchTask?.task_id) {
        await refreshResearchTask(latestResearchTask.task_id, currentResearchRunId);
      } else {
        await loadResearchRunHistory(currentResearchRunId);
        await loadResearchRunDetail(currentResearchRunId);
      }
    });
  }

  async function openResearchRunHistoryItem(runSummary: ResearchRunSummary) {
    if (!workspace) {
      return;
    }
    await run(`打开 Research Run ${runSummary.research_run_id}`, async () => {
      prepareResearchRun(runSummary.research_run_id);
      if (runSummary.task_id) {
        await loadExecution(runSummary.task_id);
        setLatestResearchTaskId(runSummary.task_id);
      }
      await loadResearchRunDetail(runSummary.research_run_id);
    });
  }

  async function openResearchWorkbench() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    setView("research");
    window.history.pushState({}, "", "/research");
    await run("打开 Deep Research 工作台", async () => {
      const runs = await loadResearchRunHistory();
      if (!currentResearchRunId && runs.length === 0) {
        resetResearchState();
      }
    });
  }

  function openMemoryWorkbench() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    setView("memory");
    window.history.pushState({}, "", "/memory/reviews");
    setStatus("已打开 Memory 人工审核工作台");
  }

  async function saveResearchReportAsSource() {
    if (!workspace || !currentResearchRunId) {
      setStatus("当前没有可写回的 Deep Research 报告");
      return;
    }
    await run("保存 Deep Research 报告到资料池", async () => {
      const saved = await researchApi.saveReportAsSource(
        workspace.workspace_id,
        currentResearchRunId
      );
      const nextSources = await sourcesApi.list(workspace.workspace_id);
      setSources(nextSources);
      const runs = await loadResearchRunHistory(currentResearchRunId);
      const savedRunSummary = runs.find((run) => run.research_run_id === currentResearchRunId) ?? currentResearchRunSummary;
      const savedRunBaseline = findRunContinuityBaseline(runs, savedRunSummary);
      const savedRunBaselineLabel = savedRunSummary
        ? buildRunContinuityBaselineLabel(savedRunBaseline, savedRunSummary)
        : "";
      const savedRunContinuityNarrative = savedRunSummary
        ? buildRunContinuityNarrative(savedRunBaseline, savedRunSummary)
        : "";
      setMessages((current) => [
        ...current,
        {
          role: "system",
          content: `Deep Research 报告已写回资料池：source=${saved.source_id}，status=${saved.status}，index=${saved.index_status}。${
            buildArtifactRecoveryNarrative(
              currentResearchRunSummary?.recovery_mode || currentResearchRun?.report_structure?.recovery_status.active_recovery_strategy || currentResearchRun?.report_structure?.recovery_mode || "",
              currentRunSummaryRecoveryTargets
            ) || ""
          }${savedRunBaselineLabel ? ` continuity baseline=${savedRunBaselineLabel}。` : ""}${savedRunContinuityNarrative ? ` ${savedRunContinuityNarrative}` : ""}`
        }
      ]);
    });
  }

  function exportResearchReportMarkdown() {
    const exportArtifact = buildResearchReportExportArtifact({
      final_report_markdown: currentResearchRun?.final_report_markdown,
      final_report_title: currentResearchRun?.final_report_title,
      question: currentResearchRun?.question
    });
    if (!exportArtifact) {
      setStatus("当前没有可导出的 Deep Research Markdown 报告");
      return;
    }
    const blob = new Blob([exportArtifact.content], { type: exportArtifact.mimeType });
    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = objectUrl;
    link.download = exportArtifact.fileName;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(objectUrl);
    setStatus(buildResearchReportExportStatusMessage(exportArtifact.fileName));
  }

  async function deleteSource(source: SourceAsset) {
    if (!workspace) {
      return;
    }
    await run(`删除资料《${source.title}》`, async () => {
      const deleted = await sourcesApi.remove(workspace.workspace_id, source.source_id);
      const nextSources = await sourcesApi.list(workspace.workspace_id);
      setSources(nextSources);
      await refreshWikiFromServer(selectedWikiItemId, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
      if (deleted.wiki_retract_task_id) {
        await loadExecution(deleted.wiki_retract_task_id);
        setLatestTaskId(deleted.wiki_retract_task_id);
      }
      setMessages((current) => [
        ...current,
        {
          role: "system",
          content: deleted.wiki_retract_task_id
            ? `资料已删除：${source.title}。相关自动生成页面已同步清理。`
            : `资料已删除：${source.title}。当前未开启 Wiki 构建，因此没有关联页面需要同步处理。`
        }
      ]);
    });
  }

  async function toggleWikiEnabled() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run(wikiEnabled ? "关闭 Wiki 构建" : "开启 Wiki 构建", async () => {
      const next = !wikiEnabled;
      const settings = await updateWikiEnabled(next, wikiGraphMode, wikiGraphKindFilters);
      setMessages((current) => [
        ...current,
        {
          role: "system",
          content: settings.wiki_enabled
            ? "已开启工作台级 Wiki 构建。已有资料会自动回补，后续资料上传或更新会进入 Wiki ingest 队列。"
            : "已关闭工作台级 Wiki 构建。资料上传只进入普通检索索引。"
        }
      ]);
    });
  }

  async function sendConversationPrompt(content: string, runLabel = `${currentRoute().label} 提问`) {
    if (!conversation) {
      setStatus("请先创建工作台和会话");
      return;
    }
    const trimmed = content.trim();
    if (!trimmed) {
      return;
    }
    await run(runLabel, async () => {
      setMessages((current) => [...current, { role: "user", content: trimmed }]);
      let sent;
      try {
        sent = await answersApi.send(conversation.conversation_id, {
          content: trimmed,
          answer_mode: mode.toUpperCase(),
          client_request_id: `${mode}-${Date.now()}`,
          source_scope_source_ids: mode === "qa" ? selectedQaSourceIds : []
        });
      } catch (error) {
        setMessages((current) => {
          const rollbackIndex = [...current]
            .map((message, index) => ({ message, index }))
            .reverse()
            .find(({ message }) => message.role === "user" && message.content === trimmed)?.index;
          if (rollbackIndex === undefined) {
            return current;
          }
          return current.filter((_, index) => index !== rollbackIndex);
        });
        throw error;
      }
      setLastAssistantMessageId(sent.assistant_message_id);
      const buffered = answerRunStoreRef.current.get(sent.answer_run_id);
      setMessages((current) => [...current, {
        role: "assistant",
        content: buffered?.content ?? "",
        answerMode: mode,
        citations: buffered?.citations ?? [],
        answerRunId: sent.answer_run_id,
        answerStatus: buffered?.status ?? "GENERATING",
        answerError: buffered?.error ?? ""
      }]);
      if (conversationStreamConnected) {
        await answerRunStoreRef.current.waitFor(sent.answer_run_id);
        const completed = await reconcileAnswerRun(sent.answer_run_id);
        if (!completed?.content) {
          setMessages((current) => updateAssistantMessageByRun(current, sent.answer_run_id, {
            ...(completed ?? createAnswerRunState(sent.answer_run_id)),
            content: "后端已完成回答，但没有返回 delta 内容。",
            status: "COMPLETED"
          }));
        }
        return;
      }
      let fallback = createAnswerRunState(sent.answer_run_id);
      await streamEvents(sent.answer_stream_url || sent.stream_url, (event) => {
        fallback = reduceAnswerRunState(fallback, event.event, event.data);
        if (fallback.status === "FAILED" || fallback.status === "CANCELLED") {
          setMessages((current) => updateLastAssistantMessage(current, fallback));
          throw new Error(fallback.error || "回答流失败");
        }
        setMessages((current) => updateLastAssistantMessage(
          current,
          fallback
        ));
      });
      if (!fallback.content) {
        setMessages((current) => updateLastAssistantMessage(
          current,
          {
            ...fallback,
            content: "后端已完成回答，但没有返回 delta 内容。"
          }
        ));
      }
      await reconcileAnswerRun(sent.answer_run_id, fallback);
    });
  }

  async function reconcileAnswerRun(answerRunId: string, fallback?: AnswerRunState) {
    if (!workspace) {
      return answerRunStoreRef.current.get(answerRunId) ?? fallback;
    }
    const snapshot = await answersApi.getRun(workspace.workspace_id, answerRunId);
    const current = answerRunStoreRef.current.get(answerRunId);
    if (!current && fallback) {
      answerRunStoreRef.current.reconcileRun({
        id: fallback.runId,
        status: fallback.status,
        content: fallback.content,
        error_message: fallback.error
      });
    }
    const reconciled = answerRunStoreRef.current.reconcileRun(snapshot);
    setMessages((messages) => updateAssistantMessageByRun(messages, answerRunId, reconciled));
    return reconciled;
  }

  async function sendMessage() {
    await sendConversationPrompt(question);
  }

  function updateArtifactFormValue(fieldKey: string, value: string) {
    setArtifactFormValues((current) => ({
      ...current,
      [fieldKey]: value
    }));
  }

  function appendArtifactHint(hint: string) {
    setArtifactCustomInstruction((current) => {
      const trimmedCurrent = current.trim();
      if (!trimmedCurrent) {
        return hint;
      }
      return trimmedCurrent.includes(hint)
        ? current
        : `${trimmedCurrent}\n- ${hint}`;
    });
  }

  function renderArtifactField(field: ArtifactStudioField) {
    const value = artifactFormValues[field.key] || "";
    if (field.kind === "select") {
      return (
        <label key={field.key} className="rail-field">
          <span>{field.label}</span>
          <select value={value} onChange={(event) => updateArtifactFormValue(field.key, event.target.value)}>
            {(field.options || []).map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
      );
    }

    return (
      <label key={field.key} className="rail-field">
        <span>{field.label}</span>
        <input
          value={value}
          onChange={(event) => updateArtifactFormValue(field.key, event.target.value)}
          placeholder={field.placeholder}
          type={field.kind === "url" ? "url" : "text"}
        />
      </label>
    );
  }

  function buildArtifactPrompt(skill: ArtifactStudioSkill) {
    const artifactLanguage = readArtifactLanguage(artifactFormValues);
    const artifactUrl = readArtifactUrl(skill, artifactFormValues);
    const baseHeader = [
      `请基于当前工作台资料，${skill.promptFocus}。`,
      `输出语言：${artifactLanguage}。`,
      `额外要求：${skill.styleHint}`
    ];
    const customInstruction = artifactCustomInstruction.trim();
    if (customInstruction) {
      baseHeader.push(`补充说明：${customInstruction}`);
    }

    switch (skill.key) {
      case "resume_highlight":
        return `${baseHeader.join("\n")}\n请按“项目背景 / 我的动作 / 技术复杂度 / 业务或工程结果 / 可写入简历的亮点句”输出，并尽量量化影响。`;
      case "study_guide":
        return `${baseHeader.join("\n")}\n请按“主题概览 / 关键概念 / 章节结构 / 易错点 / 练习建议 / 复习路径”输出学习指南。`;
      case "quiz_pack":
        return `${baseHeader.join("\n")}\n请给出分层难度的题目设计，并按“题目 / 标准答案 / 解析 / 评分要点”输出。`;
      case "wiki_page":
        return `${baseHeader.join("\n")}\n请生成适合沉淀为 Wiki 的页面草稿，并覆盖“概览 / 关键机制 / 证据与引用提示 / 相关页面建议”。`;
      case "bilibili_course_note_pdf":
        return `${baseHeader.join("\n")}\nB站链接：${artifactUrl || "请补充视频链接"}。\n请按独立 Artifact Worker 的受控异步链路组织任务，必要时调用 Bilibili Render PDF MCP，输出产物计划、章节结构、字幕来源策略与 PDF 讲义要求。`;
      default:
        return baseHeader.join("\n");
    }
  }

  function buildArtifactRequirement(skill: ArtifactStudioSkill) {
    const artifactLanguage = readArtifactLanguage(artifactFormValues);
    const artifactUrl = readArtifactUrl(skill, artifactFormValues);
    const requirements = [
      skill.promptFocus,
      `输出语言：${artifactLanguage}`,
      `风格要求：${skill.styleHint}`
    ];
    const customInstruction = artifactCustomInstruction.trim();
    if (customInstruction) {
      requirements.push(`补充说明：${customInstruction}`);
    }
    if (skill.key === "bilibili_course_note_pdf") {
      requirements.push(`B站链接：${artifactUrl || "请补充视频链接"}`);
      requirements.push("需要按讲义章节、字幕获取策略与 PDF 产物要求组织异步任务");
    }
    return requirements.join("\n");
  }

  function prepareArtifactPrompt(skill: ArtifactStudioSkill) {
    const prompt = buildArtifactPrompt(skill);
    setView("chat");
    setMode("qa");
    setQuestion(prompt);
    setArtifactComposerOpen(false);
    setStatus(`已将“${skill.title}”的生成请求填入聊天输入框。`);
    return prompt;
  }

  async function launchArtifactPrompt(skill: ArtifactStudioSkill) {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    const userRequirement = buildArtifactRequirement(skill);
    const inputs = buildArtifactJobInputs(skill, artifactFormValues);
    await run(`创建 ${skill.title} 任务`, async () => {
      const created = await artifactsApi.createJob(workspace.workspace_id, {
        skill_key: skill.key,
        user_requirement: userRequirement,
        inputs
      });
      await loadArtifactJobs(workspace.workspace_id);
      setArtifactComposerOpen(false);
      setArtifactCustomInstruction("");
      setArtifactFormValues(buildInitialArtifactFormValues(skill));
      setMessages((current) => [
        ...current,
        {
          role: "system",
          content: `已创建 ${skill.title} 任务：job=${created.artifact_job_id}，task=${created.task_id}，当前状态 ${created.status}。`
        }
      ]);
      setStatus(`已创建“${skill.title}”任务，系统会通过独立 Artifact Worker 异步生成结果。`);
    });
  }

  async function openWikiHome() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run("读取默认 Wiki 工作台入口", async () => {
      const wiki = await refreshWikiFromServer(selectedWikiItemId, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
      setView("wiki");
      window.history.pushState({}, "", wiki.wiki_url);
      setMessages((current) => [
        ...current,
        { role: "system", content: `默认 Wiki 工作台：${wiki.wiki_url}，页面数 ${wiki.pages.length}，链接数 ${wiki.links.length}` }
      ]);
    });
  }

  async function openWikiIndex() {
    if (!workspace || !wikiHome) {
      return;
    }
    setWikiGraphMode("overview");
    setWikiRenameTitle("");
    await clearWikiSelection({ mode: "overview", graphKinds: wikiGraphKindFilters });
  }

  async function saveLatestAnswerAsNote() {
    if (!lastAssistantMessageId) {
      setStatus("请先完成一次聊天回答");
      return;
    }
    await run("保存最新回答为 Note", async () => {
      const saved = await knowledgeApi.saveMessageAsNote(lastAssistantMessageId, noteTitle);
      setMessages((current) => [
        ...current,
        { role: "system", content: `已保存 Note：${saved.title}（v${saved.latest_version_no}）` }
      ]);
    });
  }

  async function createWikiPage() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    const content = wikiDraft.trim();
    if (!content) {
      setStatus("请先填写要补充的页面正文。主流程是工作台级自动 Wiki 构建，手动补充只用于缺口修正。");
      return;
    }
    await run("手动补缺 / 修正 Wiki 页面", async () => {
      const created = await knowledgeApi.createItem(workspace.workspace_id, {
        item_type: "WIKI",
        title: wikiTitle,
        content,
        source_message_id: null
      });
      const wiki = await refreshWikiFromServer(created.item_id, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
      setWikiDraft("");
      setMessages((current) => [
        ...current,
        {
          role: "system",
          content: created.latest_version_no > 1
            ? `已将《${created.title}》追加为 v${created.latest_version_no}，用于手动修正现有 Wiki 页面。`
            : `已创建补缺页《${created.title}》。`
        }
      ]);
      setView("wiki");
      window.history.pushState({}, "", wiki.wiki_url);
    });
  }

  async function appendWikiVersion() {
    if (!selectedWikiPage) {
      setStatus("请先选择一个 Wiki 页面");
      return;
    }
    const content = wikiAppendDraft.trim();
    if (!content) {
      setStatus("请先填写新的 Wiki 版本正文");
      return;
    }
    await run(`追加 Wiki 页面《${selectedWikiPage.title}》版本`, async () => {
      await knowledgeApi.appendVersion(selectedWikiPage.item_id, {
        content,
        source_message_id: null
      });
      if (workspace) {
        await refreshWikiFromServer(selectedWikiPage.item_id, {
          mode: wikiGraphMode,
          graphKinds: wikiGraphKindFilters
        });
      }
      setWikiAppendDraft("");
    });
  }

  async function renameSelectedWikiPage() {
    if (!selectedWikiPage || !workspace) {
      setStatus("请先选择一个 Wiki 页面");
      return;
    }
    const title = wikiRenameTitle.trim();
    if (!title) {
      setStatus("请先填写新的 Wiki 标题");
      return;
    }
    await run(`重命名 Wiki 页面《${selectedWikiPage.title}》`, async () => {
      const renamed = await knowledgeApi.renameItem(selectedWikiPage.item_id, title);
      await refreshWikiFromServer(renamed.item_id, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
    });
  }

  async function deleteSelectedWikiPage() {
    if (!selectedWikiPage || !workspace) {
      setStatus("请先选择一个 Wiki 页面");
      return;
    }
    await run(`删除 Wiki 页面《${selectedWikiPage.title}》`, async () => {
      await knowledgeApi.deleteItem(selectedWikiPage.item_id);
      await refreshWikiFromServer("", { mode: "overview", graphKinds: wikiGraphKindFilters });
    });
  }

  async function rebuildWikiLinks() {
    if (!workspace) {
      return;
    }
    await run("重建 Wiki 链接", async () => {
      await knowledgeApi.rebuildLinks(workspace.workspace_id);
      await refreshWikiFromServer(selectedWikiItemId, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
    });
  }

  async function rebuildWorkspaceWiki() {
    if (!workspace) {
      return;
    }
    await run("重建工作台 Wiki", async () => {
      const rebuilt = await knowledgeApi.rebuild(workspace.workspace_id);
      await refreshWikiFromServer(selectedWikiItemId, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
      setMessages((current) => [
        ...current,
        { role: "system", content: `已按当前工作台资料重建 Wiki：资料 ${rebuilt.source_count} 个，任务 ${rebuilt.task_count} 个。` }
      ]);
    });
  }

  async function autoFixWiki() {
    if (!workspace) {
      return;
    }
    await run("自动修复 Wiki", async () => {
      const fixed = await knowledgeApi.autoFix(workspace.workspace_id);
      await refreshWikiFromServer(selectedWikiItemId, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
      setMessages((current) => [
        ...current,
        {
          role: "system",
          content: `自动补缺已创建 ${fixed.created_pages} 个补缺页面，当前仍有 ${fixed.remaining_issues} 个问题待进一步处理。`
        }
      ]);
    });
  }

  function clearWikiRepairDraft() {
    setWikiTitle("工作台知识页");
    setWikiDraft("");
    setStatus("已清空手动补页草稿");
  }

  function composeMissingWikiPageDraft(targetTitle: string, sourceTitle?: string) {
    const relationLine = sourceTitle
      ? `- 当前缺口来源页：[[${sourceTitle}]]`
      : "- 当前缺口来源页：待补充";
    return `# ${targetTitle}

## 页面定位

该页面用于补齐当前工作台 Wiki 网络中的缺失页面，避免知识关系在这里中断。

## 关联关系

${relationLine}
- 与其他相关页面的关系：待补充

## 待补充内容

- 核心定义或主题说明
- 关键事实与结论
- 需要补充的来源依据
`;
  }

  function composeWikiAppendDraft(issueType: string, pageTitle: string) {
    if (issueType === "MISSING_SOURCE") {
      return `## 来源补充

- 待补充资料来源：
- 关键证据摘录：
- 引用定位：

## 页面修正说明

为《${pageTitle}》补齐来源依据，并让关键结论可以继续回溯到资料证据。`;
    }
    if (issueType === "ORPHAN_PAGE") {
      return `## 页面关系补充

- 建议补充的上游页面：[[ ]]
- 建议补充的下游页面：[[ ]]
- 本页在工作台中的定位：

## 页面修正说明

把《${pageTitle}》重新接回当前 Wiki 网络，避免它继续孤立在页面关系之外。`;
    }
    return `## 页面修正

请根据当前维护提醒补充《${pageTitle}》的正文、来源或页面关系。`;
  }

  function prefillWikiRepairDraft(title: string, draft: string) {
    setWikiTitle(title);
    setWikiDraft(draft);
    setStatus(`已为《${title}》预填手动补页草稿`);
  }

  async function prepareWikiIssueRepair(issue: WikiIssue) {
    if (issue.issue_type === "BROKEN_LINK") {
      const sourceTitle = wikiHome?.pages.find((page) => page.item_id === issue.item_id)?.title;
      prefillWikiRepairDraft(issue.title, composeMissingWikiPageDraft(issue.title, sourceTitle));
      return;
    }
    if (!issue.item_id) {
      return;
    }
    await openWikiPageById(issue.item_id);
    const pageTitle = wikiHome?.pages.find((page) => page.item_id === issue.item_id)?.title ?? issue.title;
    setWikiAppendDraft(composeWikiAppendDraft(issue.issue_type, pageTitle));
    setStatus(`已定位到《${pageTitle}》，并预填页面修正草稿`);
  }

  function prepareWikiLinkRepair(targetTitle: string, sourceTitle: string) {
    prefillWikiRepairDraft(targetTitle, composeMissingWikiPageDraft(targetTitle, sourceTitle));
  }

  async function run(label: string, action: () => Promise<void>) {
    try {
      setIsBusy(true);
      setStatus(`${label}中...`);
      await action();
      setStatus(`${label}完成`);
    } catch (error) {
      setStatus(error instanceof Error ? error.message : `${label}失败`);
    } finally {
      setIsBusy(false);
    }
  }

  function currentRoute() {
    return routes.find((route) => route.key === mode) ?? routes[0];
  }

  function backToChat() {
    setView("chat");
    window.history.pushState({}, "", "/");
  }

  async function loadWikiVersion(itemId: string, versionNo: number) {
    await loadKnowledgeVersion(itemId, versionNo);
  }

  async function selectWikiPage(page: WikiPage, nextGraphMode?: WikiGraphMode) {
    await run(`打开 Wiki 页面《${page.title}》`, async () => {
      const graphMode = nextGraphMode ?? wikiGraphMode;
      setWikiGraphMode(graphMode);
      await selectKnowledgePage(page, workspace ? {
        mode: graphMode,
        graphKinds: wikiGraphKindFilters
      } : undefined);
      setWikiAppendDraft("");
    });
  }

  function getWikiKindRank(kind: string) {
    const normalized = (kind || "TOPIC").toUpperCase();
    switch (normalized) {
      case "OVERVIEW":
        return 0;
      case "TOPIC":
        return 1;
      case "CONCEPT":
        return 2;
      case "COMPARISON":
        return 3;
      default:
        return 9;
    }
  }

  function getSeverityRank(severity: string) {
    switch ((severity || "").toUpperCase()) {
      case "HIGH":
        return 0;
      case "MEDIUM":
        return 1;
      case "LOW":
        return 2;
      default:
        return 9;
    }
  }

  function formatDateTime(value: string | null | undefined) {
    if (!value) {
      return "时间未知";
    }
    return new Date(value).toLocaleString("zh-CN");
  }

  function formatWikiRelationType(relationType: string) {
    switch ((relationType || "").toUpperCase()) {
      case "AUTO_LINK":
        return "自动互链";
      case "HYBRID_LINK":
        return "混合互链";
      case "WIKI_LINK":
        return "显式链接";
      default:
        return relationType || "页面关系";
    }
  }

  const selectedWikiPage = wikiHome?.pages.find((page) => page.item_id === selectedWikiItemId) ?? null;
  const selectedWikiIssues = selectedWikiItemId
    ? wikiIssues.filter((issue) => issue.item_id === selectedWikiItemId)
    : [];
  const baseWikiPages = wikiSearchResults ?? wikiHome?.pages ?? [];
  const availableWikiKinds = Array.from(new Set((wikiHome?.pages ?? []).map((page) => page.page_kind || "TOPIC")))
    .sort((left, right) => {
      const rankDiff = getWikiKindRank(left) - getWikiKindRank(right);
      return rankDiff !== 0 ? rankDiff : left.localeCompare(right);
    });
  const graphSearchHits = wikiGraphSearch.trim()
    ? (wikiHome?.pages ?? [])
        .filter((page) => {
          const keyword = wikiGraphSearch.trim().toLowerCase();
          return page.title.toLowerCase().includes(keyword)
            || page.summary.toLowerCase().includes(keyword)
            || (page.page_kind || "TOPIC").toLowerCase().includes(keyword);
        })
        .slice(0, 6)
    : [];
  const graphFilterLabel = wikiGraphKindFilters.length === 0 ? "全部页面类型" : wikiGraphKindFilters.join(" / ");
  const autoFixableIssues = wikiIssues.filter((issue) => issue.auto_fixable);
  const reviewRequiredIssues = wikiIssues.filter((issue) => !issue.auto_fixable);
  const wikiIssueTypes = Array.from(new Set(wikiIssues.map((issue) => issue.issue_type))).sort();
  const wikiIssueSeverities = Array.from(new Set(wikiIssues.map((issue) => issue.severity)))
    .sort((left, right) => {
      const rankDiff = getSeverityRank(left) - getSeverityRank(right);
      return rankDiff !== 0 ? rankDiff : left.localeCompare(right);
    });
  const visibleWikiPages = baseWikiPages.filter((page) => wikiKindFilter === "ALL" || (page.page_kind || "TOPIC") === wikiKindFilter);
  const groupedWikiPages = Object.fromEntries(
    Object.entries(visibleWikiPages.reduce<Record<string, WikiPage[]>>((groups, page) => {
      const kind = page.page_kind || "TOPIC";
      groups[kind] = groups[kind] ?? [];
      groups[kind].push(page);
      return groups;
    }, {})).sort(([left], [right]) => {
      const rankDiff = getWikiKindRank(left) - getWikiKindRank(right);
      return rankDiff !== 0 ? rankDiff : left.localeCompare(right);
    })
  );
  const researchScopeSources = sources.filter((source) => selectedResearchSourceIds.includes(source.source_id));
  const filteredResearchRuns = researchRuns.filter((run) => matchesResearchHistoryFilter(run, researchHistoryFilter));
  const chronologicalResearchRuns = [...researchRuns].sort(compareResearchRunsByTimeAsc);
  const researchRunById = new Map(researchRuns.map((run) => [run.research_run_id, run] as const));
  const runContinuityBaselineById = new Map<string, ResearchRunSummary | null>();
  chronologicalResearchRuns.forEach((run, index) => {
    const resumeBaseline = run.resumed_from_research_run_id
      ? researchRunById.get(run.resumed_from_research_run_id) ?? null
      : null;
    runContinuityBaselineById.set(
      run.research_run_id,
      resumeBaseline ?? (index > 0 ? chronologicalResearchRuns[index - 1] ?? null : null)
    );
  });
  const currentResearchRunSummary = researchRuns.find((run) => run.research_run_id === currentResearchRunId) ?? null;
  const researchTimelineMilestones = buildResearchTimelineMilestones(chronologicalResearchRuns);
  const researchTimelinePath = buildResearchTimelinePathSummary(
    chronologicalResearchRuns,
    researchTimelineMilestones,
    currentResearchRunSummary
  );
  const summarizedResearchQuestion = summarizeText(researchQuestion.trim(), 88) || "当前还没有填写显式研究问题。";
  const summarizedResearchGoal = summarizeText(researchGoal.trim(), 88) || "当前还没有填写显式研究目标。";
  const researchScopeCount = selectedResearchSourceIds.length;
  const currentResearchProcessSummary = currentResearchRun?.research_process_summary
    ?? currentResearchRunSummary?.research_process_summary
    ?? null;
  const researchReportStructure = currentResearchRun?.report_structure ?? null;
  const researchClosedLoopState = currentResearchRun?.closed_loop_state ?? null;
  const currentResearchSourceEvidenceSummary = currentResearchProcessSummary?.source_evidence_summary ?? null;
  const researchReportIntentContract = readIntentCompletionContract(researchReportStructure?.intent_completion_contract);
  const researchClosedLoopIntentContract = readIntentCompletionContract(researchClosedLoopState?.state_ledger?.intent_completion_contract);
  const currentRecoveryStatus = asRecord(researchReportStructure?.recovery_status);
  const currentGuardrailedRows = asResearchRows(currentRecoveryStatus["guardrailed_rows"]);
  const currentRecoveryTargets = readRecoveryTargets(
    researchClosedLoopState?.recovery_targets
    ?? researchReportStructure?.closed_loop_state?.["recovery_targets"]
    ?? researchReportStructure?.recovery_status?.["recovery_targets"]
  );
  const currentIntentCompletionContract = researchReportIntentContract ?? researchClosedLoopIntentContract;
  const latestResearchProgressEvent = [...researchTaskEvents].reverse().find((event) => event.event === "task.progress") ?? null;
  const latestWorkspaceProgressEvent = [...taskEvents].reverse().find((event) => event.event === "task.progress") ?? null;
  const currentSavedReportSource = currentResearchRun?.saved_report_source ?? null;
  const currentSavedReportSourceAsset = currentSavedReportSource
    ? sources.find((source) => source.source_id === currentSavedReportSource.source_id) ?? null
    : null;
  const currentSavedReportSourceInScope = currentSavedReportSource
    ? selectedResearchSourceIds.includes(currentSavedReportSource.source_id)
    : false;
  const currentRunSummarySnapshot = extractRunSummarySnapshot(currentResearchRunSummary);
  const currentResearchWaitContext = currentResearchRun?.wait_context ?? currentResearchRunSummary?.wait_context ?? latestResearchTask?.wait_context ?? null;
  const currentResearchWaitSignals = buildWaitContextSignalChips(currentResearchWaitContext);
  const currentResearchWaitDetails = buildWaitContextDetailLines(currentResearchWaitContext);
  const latestResearchTaskWaitSignals = buildWaitContextSignalChips(latestResearchTask?.wait_context ?? null);
  const latestResearchTaskWaitDetails = buildWaitContextDetailLines(latestResearchTask?.wait_context ?? null);
  const latestWorkspaceTaskWaitSignals = buildWaitContextSignalChips(latestTask?.wait_context ?? null);
  const latestWorkspaceTaskWaitDetails = buildWaitContextDetailLines(latestTask?.wait_context ?? null);
  const currentRunSummaryRecoveryTargets = currentRunSummarySnapshot.recoveryTargets;
  const currentVerifiedFindings = researchReportStructure?.verified_findings ?? [];
  const currentConflictReview = asRecord(researchReportStructure?.conflict_and_counterfactual_review);
  const currentConflictedRows = asResearchRows(currentConflictReview["conflicted_rows"]);
  const currentCounterfactualSummary = currentResearchRun?.counterfactual_summary ?? researchClosedLoopState?.counterfactual_summary ?? researchReportStructure?.counterfactual_summary ?? null;
  const currentFinalAnswer = readResearchFinalAnswer(
    researchReportStructure,
    currentVerifiedFindings,
    currentConflictedRows,
    currentGuardrailedRows,
    currentIntentCompletionContract
  );
  const currentExecutiveSummary = readResearchExecutiveSummary(
    researchReportStructure,
    currentVerifiedFindings,
    currentConflictedRows,
    currentGuardrailedRows,
    currentCounterfactualSummary
  );
  const currentKeyTakeaways = readResearchKeyTakeaways(
    researchReportStructure,
    currentVerifiedFindings
  );
  const resultSnapshotTitle = currentFinalAnswer.answer_status
    || currentResearchRunSummary?.final_report_title
    || "Result Snapshot";
  const resultSnapshotNarrative = [
    currentFinalAnswer.confidence_label,
    currentFinalAnswer.coverage_label,
    currentFinalAnswer.source_basis
  ]
    .filter(Boolean)
    .join(" · ") || currentExecutiveSummary[0] || "当前结论仍在等待更多证据或 verifier 放行。";
  const currentEvidenceHighlights = readResearchEvidenceHighlights(
    researchReportStructure,
    currentVerifiedFindings,
    currentConflictedRows,
    currentGuardrailedRows
  );
  const currentUncertaintyAndRisks = readResearchUncertaintyAndRisks(
    researchReportStructure,
    currentConflictedRows,
    currentGuardrailedRows,
    currentIntentCompletionContract
  );
  const artifactSidebarState = buildArtifactSidebarState({
    artifactJobs,
    latestArtifactVersion,
    selectedArtifactHistoryVersion,
    selectedArtifactHistoryKey,
    currentResearchRunSummary,
    latestTask,
    workspaceReady: Boolean(workspace),
    wikiState: workspace
      ? {
          enabled: wikiEnabled,
          pageCount: wikiIndex?.page_count ?? wikiHome?.pages.length ?? 0,
          pendingTaskCount: wikiIndex?.pending_task_count ?? 0,
          updatedAt: wikiHome?.pages[0]?.updated_at ?? ""
        }
      : null,
    sourcesCount: sources.length,
    resolveArtifactSkillTitle: (skillKey) => resolveArtifactSkillTitle(skillKey, artifactStudioSkills),
    formatRelativeTime
  });
  function restoreLatestWikiVersion() {
    restoreLatestKnowledgeVersion();
  }

  async function switchWikiGraphMode(nextMode: WikiGraphMode) {
    if (!workspace) {
      return;
    }
    setWikiGraphMode(nextMode);
    await refreshWikiGraph({
      mode: nextMode,
      selectedItemId: selectedWikiItemId,
      graphKinds: wikiGraphKindFilters
    });
  }

  async function toggleWikiGraphKind(kind: string) {
    if (!workspace) {
      return;
    }
    const nextKinds = wikiGraphKindFilters.includes(kind)
      ? wikiGraphKindFilters.filter((entry) => entry !== kind)
      : [...wikiGraphKindFilters, kind].sort((left, right) => getWikiKindRank(left) - getWikiKindRank(right));
    setWikiGraphKindFilters(nextKinds);
    await refreshWikiGraph({
      mode: wikiGraphMode,
      selectedItemId: selectedWikiItemId,
      graphKinds: nextKinds
    });
  }

  async function resetWikiGraphKinds() {
    if (!workspace) {
      return;
    }
    setWikiGraphKindFilters([]);
    await refreshWikiGraph({
      mode: wikiGraphMode,
      selectedItemId: selectedWikiItemId,
      graphKinds: []
    });
  }

  async function openWikiGraphPage(itemId: string) {
    const page = wikiHome?.pages.find((entry) => entry.item_id === itemId);
    if (!page) {
      return;
    }
    await selectWikiPage(page, "ego");
  }

  async function openWikiPageById(itemId: string) {
    const page = wikiHome?.pages.find((entry) => entry.item_id === itemId);
    if (!page) {
      return;
    }
    await selectWikiPage(page);
  }

  async function focusWikiIssue(issue: WikiIssue) {
    setWikiIssueScopeFilter(issue.auto_fixable ? "AUTO" : "MANUAL");
    setWikiIssueTypeFilter(issue.issue_type || "ALL");
    setWikiIssueSeverityFilter(issue.severity || "ALL");
    if (issue.item_id) {
      await openWikiPageById(issue.item_id);
      setWikiIssuePageFilter("CURRENT");
      return;
    }
    setWikiIssuePageFilter("ALL");
  }

  async function focusWikiIssueType(issueType: string) {
    if (!workspace) {
      return;
    }
    if (!wikiHome) {
      const wiki = await refreshWikiFromServer("", {
        mode: "overview",
        graphKinds: wikiGraphKindFilters
      });
      setView("wiki");
      window.history.pushState({}, "", wiki.wiki_url);
    } else {
      setView("wiki");
      await openWikiIndex();
    }
    const firstIssue = await findFirstWikiIssue(issueType);
    setWikiIssueScopeFilter(firstIssue?.auto_fixable ? "AUTO" : issueType === "BROKEN_LINK" ? "AUTO" : "MANUAL");
    setWikiIssueTypeFilter(issueType || "ALL");
    setWikiIssueSeverityFilter("ALL");
    if (!firstIssue) {
      setWikiIssuePageFilter("ALL");
      setStatus(`已定位到 ${issueType} 维护视图`);
      return;
    }
    await prepareWikiIssueRepair(firstIssue);
    if (firstIssue.item_id) {
      setWikiIssuePageFilter("CURRENT");
    } else {
      setWikiIssuePageFilter("ALL");
    }
    setStatus(`已定位到 ${issueType} 的首个相关对象`);
  }

  async function focusWikiAdviceTarget() {
    if (!wikiRebuildAdvice) {
      return;
    }
    const issueType = wikiRebuildAdvice.recommended_issue_type || "MISSING_SOURCE";
    const focusItemId = wikiRebuildAdvice.focus_item_id || "";
    const focusTitle = wikiRebuildAdvice.focus_title || "";
    setWikiIssueTypeFilter(issueType || "ALL");
    setWikiIssueSeverityFilter("ALL");
    if (issueType === "BROKEN_LINK" && focusTitle) {
      const sourceTitle = focusItemId
        ? wikiHome?.pages.find((page) => page.item_id === focusItemId)?.title ?? ""
        : "";
      if (focusItemId) {
        await openWikiPageById(focusItemId);
        setWikiIssuePageFilter("CURRENT");
      } else {
        setWikiIssuePageFilter("ALL");
      }
      setWikiIssueScopeFilter("AUTO");
      prefillWikiRepairDraft(focusTitle, composeMissingWikiPageDraft(focusTitle, sourceTitle));
      setStatus(`已根据建议预填《${focusTitle}》的补缺草稿`);
      return;
    }
    if (focusItemId) {
      await openWikiPageById(focusItemId);
      setWikiIssuePageFilter("CURRENT");
      setWikiIssueScopeFilter("MANUAL");
      setStatus(`已定位到《${focusTitle || focusItemId}》的修正视图`);
      return;
    }
    await focusWikiIssueType(issueType);
  }

  function getWikiAdviceAction() {
    if (!wikiRebuildAdvice) {
      return null;
    }
    switch (wikiRebuildAdvice.recommended_action) {
      case "ENABLE_WIKI":
        return {
          label: "按建议开启 Wiki 构建",
          run: () => void toggleWikiEnabled()
        };
      case "REBUILD_WIKI":
        return {
          label: "按建议重建 Wiki",
          run: () => void rebuildWorkspaceWiki()
        };
      case "AUTO_FIX_WIKI":
        return {
          label: "按建议执行自动补缺",
          run: () => void autoFixWiki()
        };
      case "FOCUS_MANUAL_REPAIR":
        return {
          label: "按建议进入人工修正",
          run: () => void focusWikiAdviceTarget()
        };
      case "OPEN_WIKI_HOME":
        return {
          label: view === "wiki" ? "查看工作台总览" : "进入 Wiki 工作台",
          run: () => void (view === "wiki" ? openWikiIndex() : openWikiHome())
        };
      default:
        return null;
    }
  }

  function getWikiIssuePrimaryAction(issue: WikiIssue) {
    switch (issue.action_code || issue.issue_type) {
      case "REBUILD_WIKI":
        if (!workspace) {
          return null;
        }
        return {
          label: "按资料重建 Wiki",
          run: () => void rebuildWorkspaceWiki()
        };
      case "PREFILL_MISSING_PAGE":
      case "BROKEN_LINK":
        return {
          label: "预填补缺页",
          run: () => void prepareWikiIssueRepair(issue)
        };
      case "FOCUS_MANUAL_REPAIR":
      case "PLACEHOLDER_CONTENT":
      case "MISSING_SOURCE":
      case "ORPHAN_PAGE":
        return {
          label: "进入修正模式",
          run: () => void prepareWikiIssueRepair(issue)
        };
      default:
        return null;
    }
  }

  const wikiAdviceAction = getWikiAdviceAction();

  function renderWikiTaskCard(task: WikiTaskSummary, key: string) {
    return (
      <div className="link-card" key={key}>
        <strong>{task.task_type} · {task.task_status}</strong>
        <span>{task.progress_phase} · {task.progress_message}</span>
        <span>{task.target_type || "WORKSPACE"} · {task.target_title || task.target_id || "当前工作台"} · {formatDateTime(task.updated_at)}</span>
        {task.related_pages.length ? (
          <div className="wiki-inline-actions">
            {task.related_pages.map((page) => (
              <button
                key={`${task.task_id}-${page.item_id}`}
                className="secondary-button"
                disabled={isBusy}
                onClick={() => void openWikiPageById(page.item_id)}
              >
                打开 {page.title}
              </button>
            ))}
          </div>
        ) : null}
      </div>
    );
  }

  function renderWikiRecentUpdateCard(page: WikiPage, key: string) {
    return (
      <div className="link-card" key={key}>
        <strong>{page.title}</strong>
        <span>
          {page.page_kind || "TOPIC"} · v{page.latest_version_no} · 出链 {page.outgoing_count} · 反链 {page.backlink_count} · 引用 {page.citation_count}
          {page.unresolved_count > 0 ? ` · 断链 ${page.unresolved_count}` : ""}
        </span>
        <span>{formatDateTime(page.updated_at)}</span>
        <div className="wiki-inline-actions">
          <button className="secondary-button" disabled={isBusy} onClick={() => void selectWikiPage(page)}>
            打开页面
          </button>
          <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiGraphPage(page.item_id)}>
            查看子图
          </button>
        </div>
      </div>
    );
  }

  function getRecentSourceAction(source: WikiIndexSource) {
    switch (source.recommended_action) {
      case "OPEN_WIKI_PAGE":
        if (!source.focus_item_id) {
          return null;
        }
        return {
          label: `打开 ${source.focus_title || "关联页面"}`,
          run: () => void openWikiPageById(source.focus_item_id)
        };
      case "ENABLE_WIKI":
        if (!workspace) {
          return null;
        }
        return {
          label: "开启 Wiki 构建",
          run: () => void toggleWikiEnabled()
        };
      case "REBUILD_WIKI":
        if (!workspace) {
          return null;
        }
        return {
          label: "按当前资料重建 Wiki",
          run: () => void rebuildWorkspaceWiki()
        };
      default:
        return null;
    }
  }

  return (
    <main className="shell">
      <header className="hero">
        <div>
          <h1>NoteWeave</h1>
          <p className="lede">研究工作台</p>
        </div>
        <span className="hero-mode">{currentRoute().label}</span>
      </header>

      <section className="workspace-card">
        <div>
          <p className="section-label">Workspace</p>
          <h2>{workspace ? workspace.name : "新建研究工作台"}</h2>
          <p>{conversation ? `当前会话：${conversation.title}` : "创建后即可添加资料并开始对话"}</p>
          <p className="status-line">{status}</p>
        </div>
        <div className="action-group">
          {view !== "chat" && (
            <button className="secondary-button" onClick={backToChat}>
              返回聊天
            </button>
          )}
          <button onClick={createWorkspace} disabled={isBusy}>
            创建工作台
          </button>
        </div>
      </section>

      {view === "wiki" && wikiHome ? (
        <section className="wiki-workbench">
          <aside className="wiki-index">
            <p className="section-label">Wiki Index</p>
            <h2>默认 Wiki 工作台</h2>
            <p className="wiki-url">{wikiHome.wiki_url}</p>
            <div className="wiki-inline-pills">
              <span className="status-chip">待处理任务 {wikiIndex?.pending_task_count ?? 0}</span>
              <span className="status-chip">可自动修复 {wikiIndex?.auto_fixable_issue_count ?? 0}</span>
              <span className="status-chip">人工确认 {wikiIndex?.manual_review_issue_count ?? 0}</span>
            </div>
            <button
              className={!selectedWikiItemId ? "active nav-button" : "nav-button"}
              disabled={isBusy}
              onClick={() => void openWikiIndex()}
            >
              <span>工作台总览</span>
              <small>自动构建 / 页面分布 / 维护提醒</small>
            </button>
            <input
              value={wikiSearch}
              onChange={(event) => setWikiSearch(event.target.value)}
              placeholder="搜索页面标题或摘要"
            />
            <div className="wiki-kind-filter">
              <button
                className={wikiKindFilter === "ALL" ? "active filter-pill" : "filter-pill"}
                disabled={isBusy}
                onClick={() => setWikiKindFilter("ALL")}
              >
                全部
              </button>
              {availableWikiKinds.map((kind) => (
                <button
                  key={kind}
                  className={wikiKindFilter === kind ? "active filter-pill" : "filter-pill"}
                  disabled={isBusy}
                  onClick={() => setWikiKindFilter(kind)}
                >
                  {kind}
                </button>
              ))}
            </div>
            <div className="wiki-page-list">
              {wikiHome.pages.length === 0 && (
                <p className="empty-state">
                  {wikiRebuildAdvice?.message ?? "还没有 Wiki 页面。建议先开启工作台级 Wiki 构建，让现有资料进入自动页面生成。"}
                </p>
              )}
              {wikiHome.pages.length > 0 && visibleWikiPages.length === 0 && <p className="empty-state">没有匹配的 Wiki 页面。</p>}
              {Object.entries(groupedWikiPages).map(([kind, pages]) => (
                <div key={`group-${kind}`} className="wiki-page-group">
                  <div className="wiki-page-group-title">
                    <strong>{kind}</strong>
                    <small>{pages.length} 页</small>
                  </div>
                  {pages.map((page) => (
                    <button
                      key={page.item_id}
                      className={page.item_id === selectedWikiPage?.item_id ? "active wiki-page-card" : "wiki-page-card"}
                      disabled={isBusy}
                      onClick={() => void selectWikiPage(page)}
                    >
                      <span>{page.title}</span>
                      <small>{page.page_kind || "TOPIC"} · v{page.latest_version_no}</small>
                      <small>
                        出链 {page.outgoing_count} · 反链 {page.backlink_count} · 引用 {page.citation_count}
                        {page.unresolved_count > 0 ? ` · 断链 ${page.unresolved_count}` : ""}
                      </small>
                    </button>
                  ))}
                </div>
              ))}
            </div>
          </aside>

          <article className="wiki-page">
            <p className="section-label">{selectedWikiPage ? "Wiki Page" : "Wiki Index"}</p>
            {selectedWikiPage ? (
              <>
                <h2>{selectedWikiPage.title}</h2>
                <div className="wiki-maintenance">
                  <strong>页面类型</strong>
                  <span>{selectedWikiDetail?.page_kind || selectedWikiPage.page_kind || "TOPIC"}</span>
                </div>
                <div className="wiki-maintenance">
                  <strong>当前查看</strong>
                  <span>
                    {selectedWikiVersionDetail && selectedWikiVersionDetail.version_no !== (selectedWikiDetail?.latest_version_no ?? selectedWikiPage.latest_version_no)
                      ? `历史版本 v${selectedWikiVersionDetail.version_no}`
                      : `最新版本 v${selectedWikiDetail?.latest_version_no ?? selectedWikiPage.latest_version_no}`}
                  </span>
                </div>
                <div className="wiki-maintenance">
                  <strong>版本来源</strong>
                  <span>
                    {selectedWikiVersionDetail?.source_message_id
                      ? `来自聊天消息 ${selectedWikiVersionDetail.source_message_id}`
                      : "来自工作台级资料 ingest 或人工维护"}
                    {" · "}
                    {formatDateTime(selectedWikiVersionDetail?.created_at)}
                  </span>
                </div>
                <div className="wiki-maintenance">
                  <strong>页面更新时间</strong>
                  <span>{formatDateTime(selectedWikiDetail?.updated_at ?? selectedWikiPage.updated_at)}</span>
                </div>
                <p className="version-pill">当前版本 v{selectedWikiDetail?.latest_version_no ?? selectedWikiPage.latest_version_no}</p>
                <div className="wiki-summary">
                  {(selectedWikiVersionDetail?.version_no === (selectedWikiDetail?.latest_version_no ?? selectedWikiPage.latest_version_no)
                    ? selectedWikiDetail?.content
                    : selectedWikiVersionDetail?.content) || selectedWikiDetail?.content || selectedWikiPage.summary || "这个页面暂时还没有正文。"}
                </div>
                <div className="wiki-citations">
                  <strong>版本历史</strong>
                  {selectedWikiVersions.length === 0 && <span>当前页面还没有版本记录。</span>}
                  {selectedWikiVersions.map((version) => (
                    <button
                      key={version.version_id}
                      className="secondary-button"
                      disabled={isBusy}
                      onClick={() => void loadWikiVersion(selectedWikiPage.item_id, version.version_no)}
                    >
                      v{version.version_no} · 引用 {version.citation_count} · {version.source_message_id ? "聊天来源" : "工作台维护"} · {version.summary || "无摘要"}
                    </button>
                  ))}
                  {selectedWikiVersionDetail && selectedWikiDetail && selectedWikiVersionDetail.version_no !== selectedWikiDetail.latest_version_no ? (
                    <button className="secondary-button" disabled={isBusy} onClick={restoreLatestWikiVersion}>
                      返回最新版本
                    </button>
                  ) : null}
                </div>
                {selectedWikiVersionDetail && selectedWikiVersionDetail.version_no !== (selectedWikiDetail?.latest_version_no ?? selectedWikiPage.latest_version_no) ? (
                  <div className="wiki-citations">
                    <strong>历史版本引用</strong>
                    {selectedWikiVersionDetail.citations.length === 0 && <span>该历史版本没有绑定引用。</span>}
                    {selectedWikiVersionDetail.citations.map((citation, index) => (
                      <span key={`history-${citation.citation_id}`}>
                        {index + 1}. {buildKnowledgeCitationLabel(citation)}：{citation.quote_text}
                      </span>
                    ))}
                  </div>
                ) : null}
                {selectedWikiDetail?.citations.length ? (
                  <div className="wiki-citations">
                    <strong>来源引用</strong>
                    {selectedWikiDetail.citations.map((citation, index) => (
                      <span key={citation.citation_id}>
                        {index + 1}. {buildKnowledgeCitationLabel(citation)}：{citation.quote_text}
                      </span>
                    ))}
                  </div>
                ) : null}
                <div className="wiki-maintenance">
                  <strong>页面出链</strong>
                  {selectedWikiDetail?.outgoing_links?.length ? (
                    <span>
                      {selectedWikiDetail.outgoing_links.map((link, index) => (
                        <button
                          key={`${link.source_item_id}-${link.target_title}-${index}-inline`}
                          className="inline-action"
                          disabled={isBusy || !link.target_item_id}
                          onClick={() => link.target_item_id ? void openWikiPageById(link.target_item_id) : undefined}
                        >
                          {link.target_title}
                        </button>
                      ))}
                    </span>
                  ) : "当前页面暂无显式出链"}
                </div>
                <div className="wiki-maintenance">
                  <strong>反向链接</strong>
                  {selectedWikiDetail?.backlinks?.length ? (
                    <span>
                      {selectedWikiDetail.backlinks.map((link, index) => (
                        <button
                          key={`${link.source_item_id}-${link.target_title}-${index}-back-inline`}
                          className="inline-action"
                          disabled={isBusy || !link.source_item_id}
                          onClick={() => link.source_item_id ? void openWikiPageById(link.source_item_id) : undefined}
                        >
                          {link.target_title}
                        </button>
                      ))}
                    </span>
                  ) : "当前页面暂无反向链接"}
                </div>
                <div className="wiki-maintenance">
                  <strong>页面维护动作</strong>
                  <span>编辑正文会生成新版本；重命名会刷新页面关系并同步改写引用页；删除采用软删除，引用它的页面关系会回退为未解析。</span>
                </div>
                <div className="wiki-citations">
                  <strong>页面健康问题</strong>
                  {selectedWikiIssues.length ? selectedWikiIssues.map((issue, index) => (
                    (() => {
                      const issueAction = getWikiIssuePrimaryAction(issue);
                      return (
                        <div className="link-card" key={`page-issue-${issue.issue_type}-${index}`}>
                          <strong>{issue.issue_type} / {issue.severity}</strong>
                          <span>{issue.message}</span>
                          <span>{issue.auto_fixable ? "可自动修复" : "需人工确认"}</span>
                          <span>{issue.suggested_action}</span>
                          <div className="wiki-inline-actions">
                            {issue.auto_fixable ? (
                              <button className="secondary-button" disabled={isBusy || !workspace} onClick={() => void autoFixWiki()}>
                                自动补缺
                              </button>
                            ) : null}
                            {issueAction ? (
                              <button className="secondary-button" disabled={isBusy} onClick={issueAction.run}>
                                {issueAction.label}
                              </button>
                            ) : null}
                            {issue.item_id && issue.item_id !== selectedWikiItemId ? (
                              <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(issue.item_id!)}>
                                打开相关页面
                              </button>
                            ) : null}
                          </div>
                        </div>
                      );
                    })()
                  )) : <span>当前页面没有单独的维护提醒。</span>}
                </div>
                <div className="wiki-citations">
                  <strong>页面最近变更</strong>
                  {selectedWikiLog.length ? selectedWikiLog.slice(0, 5).map((entry) => (
                    <div className="link-card" key={`page-log-${entry.id}`}>
                      <strong>{entry.event_type}</strong>
                      <span>{entry.message}</span>
                      <span>{formatDateTime(entry.created_at)}</span>
                    </div>
                  )) : <span>当前页面还没有可展示的变更日志。</span>}
                </div>
                <label className="input-block">
                  <span>重命名页面</span>
                  <input value={wikiRenameTitle} onChange={(event) => setWikiRenameTitle(event.target.value)} />
                </label>
                <div className="maintenance-actions">
                  <button onClick={renameSelectedWikiPage} disabled={isBusy}>
                    重命名
                  </button>
                  <button className="danger-button" onClick={deleteSelectedWikiPage} disabled={isBusy}>
                    删除页面
                  </button>
                </div>
                <label className="input-block">
                  <span>追加为新版本</span>
                  <textarea
                    value={wikiAppendDraft}
                    onChange={(event) => setWikiAppendDraft(event.target.value)}
                    placeholder="粘贴或编辑新的 Wiki 页面正文，提交后 latest_version_no 会递增。"
                    rows={6}
                  />
                </label>
                <button onClick={appendWikiVersion} disabled={isBusy}>
                  追加 Wiki 版本
                </button>
              </>
            ) : (
              <>
                <h2>工作台总览</h2>
                <div className="wiki-summary">
                  当前 Wiki 工作台默认先展示工作台级总览，再按需进入具体页面。

                  {"\n\n"}这和 WeKnora 的浏览器逻辑保持一致：Wiki 首先绑定研究工作台与资料变化，
                  页面只是这套工作台级知识网络里的阅读与维护对象，而不是聊天临时草稿。
                </div>
                <div className="wiki-maintenance">
                  <strong>构建状态</strong>
                  <span>
                    {wikiIndex?.wiki_enabled ? "工作台级 Wiki 构建已开启" : "工作台级 Wiki 构建未开启"} ·
                    READY 资料 {wikiIndex?.ready_source_count ?? 0} ·
                    页面 {wikiIndex?.page_count ?? 0} ·
                    待处理任务 {wikiIndex?.pending_task_count ?? 0}
                  </span>
                </div>
                <div className="wiki-maintenance">
                  <strong>页面构成</strong>
                  <span>
                    资料驱动页面 {wikiIndex?.source_backed_page_count ?? 0} ·
                    人工维护页面 {wikiIndex?.manual_page_count ?? 0} ·
                    链接 {wikiIndex?.link_count ?? 0} ·
                    断链 {wikiIndex?.unresolved_link_count ?? 0}
                  </span>
                </div>
                <div className="wiki-maintenance">
                  <strong>构建建议</strong>
                  <span>{wikiRebuildAdvice?.message ?? "当前工作台会在这里显示 Wiki 的自动构建建议。"}</span>
                  {wikiAdviceAction ? (
                    <button className="secondary-button" disabled={isBusy || !workspace} onClick={wikiAdviceAction.run}>
                      {wikiAdviceAction.label}
                    </button>
                  ) : null}
                </div>
                <div className="wiki-citations">
                  <strong>页面类型分布</strong>
                  <span>{wikiIndex ? Object.entries(wikiIndex.pages_by_kind).map(([kind, count]) => `${kind}:${count}`).join(" / ") || "暂无页面" : "暂无页面"}</span>
                </div>
                <div className="wiki-citations">
                  <strong>最近更新页面</strong>
                  {wikiIndex?.recent_updates?.length ? wikiIndex.recent_updates.map((page) => renderWikiRecentUpdateCard(page, `index-page-${page.item_id}`)) : <span>当前还没有已沉淀的 Wiki 页面。</span>}
                </div>
                <div className="wiki-citations">
                  <strong>最近资料变化</strong>
                  {wikiIndex?.recent_sources?.length ? wikiIndex.recent_sources.map((source) => {
                    const sourceAction = getRecentSourceAction(source);
                    return (
                      <div className="link-card" key={`index-source-${source.source_id}`}>
                        <strong>{source.title}</strong>
                        <span>{source.status} · {source.index_status} · {formatDateTime(source.updated_at)}</span>
                        {source.related_pages.length ? (
                          <div className="wiki-inline-actions">
                            {source.related_pages.map((page) => (
                              <button
                                key={`source-page-${source.source_id}-${page.item_id}`}
                                className="secondary-button"
                                disabled={isBusy}
                                onClick={() => void openWikiPageById(page.item_id)}
                              >
                                打开 {page.title}
                              </button>
                            ))}
                          </div>
                        ) : (
                          <>
                            <span>当前资料尚未关联到可打开的 Wiki 页面。</span>
                            {sourceAction ? (
                              <div className="wiki-inline-actions">
                                <button className="secondary-button" disabled={isBusy} onClick={sourceAction.run}>
                                  {sourceAction.label}
                                </button>
                              </div>
                            ) : null}
                          </>
                        )}
                      </div>
                    );
                  }) : <span>当前工作台还没有资料。</span>}
                </div>
                <details className="maintenance-drawer">
                  <summary>
                    <strong>维护提醒</strong>
                    <span>
                      待处理任务 {wikiIndex?.pending_task_count ?? 0} ·
                      自动补缺 {wikiIndex?.auto_fixable_issue_count ?? 0} ·
                      人工确认 {wikiIndex?.manual_review_issue_count ?? 0}
                    </span>
                  </summary>
                  <div className="wiki-citations">
                    <strong>最近 Wiki 任务</strong>
                    {wikiIndex?.recent_tasks?.length ? wikiIndex.recent_tasks.map((task) => renderWikiTaskCard(task, `index-task-${task.task_id}`)) : <span>当前还没有 Wiki 相关任务记录。</span>}
                  </div>
                  <div className="wiki-citations">
                    <strong>优先维护项</strong>
                    {wikiIndex?.top_issues?.length ? wikiIndex.top_issues.map((issue, index) => {
                      const issueAction = getWikiIssuePrimaryAction(issue);
                      return (
                        <div className="link-card" key={`index-issue-${issue.issue_type}-${index}`}>
                          <strong>{issue.issue_type} / {issue.severity}</strong>
                          <span>{issue.message}</span>
                          <span>{issue.auto_fixable ? "可自动补缺" : "需人工确认"}</span>
                          <span>{issue.suggested_action}</span>
                          <div className="wiki-inline-actions">
                            {issue.auto_fixable ? (
                              <button className="secondary-button" disabled={isBusy || !workspace} onClick={() => void autoFixWiki()}>
                                自动补缺
                              </button>
                            ) : null}
                            {issueAction ? (
                              <button className="secondary-button" disabled={isBusy} onClick={issueAction.run}>
                                {issueAction.label}
                              </button>
                            ) : null}
                            {issue.item_id ? (
                              <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(issue.item_id!)}>
                                打开相关页面
                              </button>
                            ) : null}
                          </div>
                        </div>
                      );
                    }) : <span>当前没有需要优先处理的维护项。</span>}
                  </div>
                </details>
              </>
            )}
          </article>

          <aside className="wiki-links">
            <p className="section-label">{selectedWikiPage ? "页面关系" : "工作台关系"}</p>
            {wikiStats && (
              <>
                <div className="wiki-maintenance">
                  <strong>Wiki 健康度</strong>
                  <span>页面 {wikiStats.page_count} · 链接 {wikiStats.link_count} · 已解析 {wikiStats.resolved_link_count} · 断链 {wikiStats.unresolved_link_count} · 引用 {wikiStats.citation_count} · 问题 {wikiStats.issue_count}</span>
                </div>
                <div className="wiki-maintenance">
                  <strong>维护提醒</strong>
                  <span>自动补缺 {wikiStats.auto_fixable_issue_count} · 人工确认 {wikiStats.manual_review_issue_count}</span>
                </div>
                <div className="wiki-maintenance">
                  <strong>工作台状态</strong>
                  <span>{wikiStats.wiki_enabled ? "Wiki 构建已开启" : "Wiki 构建未开启"} · 待处理任务 {wikiStats.pending_task_count}</span>
                </div>
                <div className="wiki-maintenance">
                  <strong>页面类型分布</strong>
                  <span>{Object.entries(wikiStats.pages_by_kind).map(([kind, count]) => `${kind}:${count}`).join(" / ") || "暂无页面"}</span>
                </div>
              </>
            )}
            {selectedWikiPage && selectedWikiDetail?.outgoing_links?.length === 0 && selectedWikiDetail?.backlinks?.length === 0 && (
              <p className="empty-state">当前页面暂无直接关系。</p>
            )}
            {selectedWikiDetail?.outgoing_links?.map((link, index) => (
              <div className="link-card" key={`${link.source_item_id}-${link.target_title}-${index}`}>
                <strong>{link.target_title}</strong>
                <span>出链 · {formatWikiRelationType(link.relation_type)} · {link.relation_status} · {link.mention_count} 次提及</span>
                <div className="wiki-inline-actions">
                  {link.target_item_id ? (
                    <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(link.target_item_id!)}>
                      打开页面
                    </button>
                  ) : (
                    <button
                      className="secondary-button"
                      disabled={isBusy || !selectedWikiPage}
                      onClick={() => selectedWikiPage ? prepareWikiLinkRepair(link.target_title, selectedWikiPage.title) : undefined}
                    >
                      预填补缺页
                    </button>
                  )}
                </div>
              </div>
            ))}
            {selectedWikiDetail?.backlinks?.map((link, index) => (
              <div className="link-card" key={`backlink-${link.source_item_id}-${link.target_title}-${index}`}>
                <strong>{link.target_title}</strong>
                <span>反链 · {formatWikiRelationType(link.relation_type)} · {link.relation_status} · {link.mention_count} 次提及</span>
                {link.source_item_id ? (
                  <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(link.source_item_id)}>
                    打开来源页
                  </button>
                ) : null}
              </div>
            ))}
            <p className="section-label">Wiki Graph</p>
            <div className="wiki-maintenance">
              <span>
                {wikiGraph?.meta.mode === "ego" ? "当前页面局部子图" : "工作台总览图"} ·
                节点 {wikiGraph?.nodes.length ?? 0} / {wikiGraph?.meta.total_nodes ?? 0} ·
                边 {wikiGraph?.edges.length ?? 0}
                {wikiGraph?.meta.truncated ? " · 已截断" : ""}
              </span>
            </div>
            <div className="wiki-maintenance">
              <strong>图谱视角</strong>
              <button className={wikiGraphMode === "overview" ? "active" : ""} onClick={() => void switchWikiGraphMode("overview")} disabled={isBusy || !workspace}>
                总览图
              </button>
              <button className={wikiGraphMode === "ego" ? "active" : ""} onClick={() => void switchWikiGraphMode("ego")} disabled={isBusy || !workspace || !selectedWikiItemId}>
                当前页面子图
              </button>
            </div>
            <div className="wiki-maintenance">
              <strong>图谱类型过滤</strong>
              <span>{graphFilterLabel}</span>
              <div className="wiki-inline-pills">
                <button
                  className={wikiGraphKindFilters.length === 0 ? "active filter-pill" : "filter-pill"}
                  disabled={isBusy || !workspace}
                  onClick={() => void resetWikiGraphKinds()}
                >
                  全部
                </button>
                {availableWikiKinds.map((kind) => (
                  <button
                    key={`graph-kind-${kind}`}
                    className={wikiGraphKindFilters.includes(kind) ? "active filter-pill" : "filter-pill"}
                    disabled={isBusy || !workspace}
                    onClick={() => void toggleWikiGraphKind(kind)}
                  >
                    {kind}
                  </button>
                ))}
              </div>
            </div>
            <div className="wiki-maintenance">
              <strong>图谱搜索</strong>
              <input
                value={wikiGraphSearch}
                onChange={(event) => setWikiGraphSearch(event.target.value)}
                placeholder="搜索页面标题、摘要或页面类型"
              />
              {graphSearchHits.length ? (
                <div className="wiki-search-hit-list">
                  {graphSearchHits.map((page) => (
                    <div className="link-card" key={`graph-search-${page.item_id}`}>
                      <strong>{page.title}</strong>
                      <span>{page.page_kind || "TOPIC"} · v{page.latest_version_no}</span>
                      <div className="wiki-inline-actions">
                        <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(page.item_id)}>
                          打开页面
                        </button>
                        <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiGraphPage(page.item_id)}>
                          查看子图
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              ) : wikiGraphSearch.trim() ? <span>没有匹配的图谱页面。</span> : null}
            </div>
            {wikiGraph?.nodes.slice(0, 6).map((node) => (
              <div className="link-card" key={`graph-node-${node.item_id}`}>
                <strong>{node.title}</strong>
                <span>
                  {node.page_kind} · degree {node.degree} · 出链 {node.outgoing_count} · 反链 {node.backlink_count} · 引用 {node.citation_count}
                  {node.unresolved_count > 0 ? ` · 断链 ${node.unresolved_count}` : ""}
                </span>
                <div className="wiki-inline-actions">
                  <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(node.item_id)}>
                    打开页面
                  </button>
                  <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiGraphPage(node.item_id)}>
                    查看子图
                  </button>
                </div>
              </div>
            ))}
            {wikiGraph?.edges.slice(0, 6).map((edge, index) => (
              <div className="link-card" key={`graph-edge-${edge.source_item_id}-${edge.target_title}-${index}`}>
                <strong>{edge.source_title} {"->"} {edge.target_title}</strong>
                <span>{edge.relation_status} · {edge.mention_count} 次提及 · {formatWikiRelationType(edge.relation_type)}</span>
                <div className="wiki-inline-actions">
                  <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(edge.source_item_id)}>
                    打开来源页
                  </button>
                  {!edge.target_item_id ? (
                    <button className="secondary-button" disabled={isBusy} onClick={() => prepareWikiLinkRepair(edge.target_title, edge.source_title)}>
                      预填补缺页
                    </button>
                  ) : null}
                  {edge.target_item_id ? (
                    <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(edge.target_item_id!)}>
                      打开目标页
                    </button>
                  ) : null}
                  {edge.target_item_id ? (
                    <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiGraphPage(edge.target_item_id!)}>
                      查看目标子图
                    </button>
                  ) : null}
                </div>
              </div>
            ))}
            <details className="maintenance-drawer">
              <summary>
                <strong>维护工具</strong>
                <span>重建链接、自动补缺、维护问题、日志与手动补页</span>
              </summary>
              <div className="wiki-maintenance">
                <strong>维护动作</strong>
                <button onClick={rebuildWikiLinks} disabled={isBusy || !workspace}>重建链接</button>
                <button onClick={autoFixWiki} disabled={isBusy || !workspace}>自动补缺</button>
              </div>
              {wikiRebuildAdvice ? (
                <div className="wiki-maintenance">
                  <strong>构建建议</strong>
                  <span>{wikiRebuildAdvice.message}</span>
                  {wikiAdviceAction ? (
                    <button className="secondary-button" disabled={isBusy || !workspace} onClick={wikiAdviceAction.run}>
                      {wikiAdviceAction.label}
                    </button>
                  ) : null}
                </div>
              ) : null}
              {reviewRequiredIssues.length ? (
                <div className="wiki-citations">
                  <strong>人工确认队列</strong>
                  {reviewRequiredIssues.slice(0, 4).map((issue, index) => {
                    const issueAction = getWikiIssuePrimaryAction(issue);
                    return (
                      <div className="link-card" key={`manual-issue-${issue.issue_type}-${index}`}>
                        <strong>{issue.issue_type} / {issue.severity}</strong>
                        <span>{issue.message}</span>
                        <span>{issue.suggested_action}</span>
                        <div className="wiki-inline-actions">
                          {issueAction ? (
                            <button className="secondary-button" disabled={isBusy} onClick={issueAction.run}>
                              {issueAction.label}
                            </button>
                          ) : null}
                          {issue.item_id ? (
                            <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(issue.item_id!)}>
                              打开相关页面
                            </button>
                          ) : null}
                          <button className="secondary-button" disabled={isBusy} onClick={() => void focusWikiIssue(issue)}>
                            打开问题列表
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              ) : null}
              <div className="wiki-maintenance">
                <strong>任务队列</strong>
                <span>
                  {wikiStats?.wiki_enabled ? "工作台级 Wiki 已开启" : "工作台级 Wiki 未开启"} ·
                  待处理 {wikiStats?.pending_task_count ?? 0} ·
                  最近任务 {(wikiStats?.recent_tasks?.length ?? 0)}
                </span>
              </div>
              {wikiStats?.recent_tasks?.length ? (
                <div className="wiki-citations">
                  <strong>最近任务详情</strong>
                  {wikiStats.recent_tasks.slice(0, 4).map((task) => renderWikiTaskCard(task, `recent-task-${task.task_id}`))}
                </div>
              ) : null}
              {wikiStats?.recent_updates?.length ? (
                <div className="wiki-citations">
                  <strong>最近更新</strong>
                  {wikiStats.recent_updates.slice(0, 4).map((page) => renderWikiRecentUpdateCard(page, `recent-update-${page.item_id}`))}
                </div>
              ) : null}
              <div className="wiki-citations">
                <strong>手动补页 / 修正文案</strong>
                <span>这里用于补缺页面或修正文案。若标题已存在，系统会直接追加新版本，而不是重复创建同名页面。</span>
                <label className="rail-field">
                  <span>页面标题</span>
                  <input value={wikiTitle} onChange={(event) => setWikiTitle(event.target.value)} />
                </label>
                <label className="rail-field">
                  <span>页面正文</span>
                  <textarea
                    value={wikiDraft}
                    onChange={(event) => setWikiDraft(event.target.value)}
                    placeholder="可以从断链关系或人工确认项预填草稿，也可以手动输入正文。"
                    rows={8}
                  />
                </label>
                <div className="wiki-inline-actions">
                  <button disabled={isBusy || !workspace} onClick={createWikiPage}>
                    提交补页 / 修正文案
                  </button>
                  <button className="secondary-button" disabled={isBusy} onClick={clearWikiRepairDraft}>
                    清空草稿
                  </button>
                </div>
                <span>如果当前是在修已有页面正文，优先使用中间区域的“追加 Wiki 版本”。</span>
              </div>
              <p className="section-label">维护问题列表</p>
              <div className="wiki-maintenance">
                <strong>问题过滤</strong>
                <div className="wiki-inline-pills">
                  <button
                    className={wikiIssuePageFilter === "ALL" ? "active filter-pill" : "filter-pill"}
                    onClick={() => setWikiIssuePageFilter("ALL")}
                    disabled={isBusy}
                  >
                    全工作台
                  </button>
                  <button
                    className={wikiIssuePageFilter === "CURRENT" ? "active filter-pill" : "filter-pill"}
                    onClick={() => setWikiIssuePageFilter("CURRENT")}
                    disabled={isBusy || !selectedWikiItemId}
                  >
                    当前页面 {selectedWikiIssues.length}
                  </button>
                </div>
                <div className="wiki-inline-pills">
                  <button
                    className={wikiIssueScopeFilter === "ALL" ? "active filter-pill" : "filter-pill"}
                    onClick={() => setWikiIssueScopeFilter("ALL")}
                    disabled={isBusy}
                  >
                    全部 {wikiIssues.length}
                  </button>
                  <button
                    className={wikiIssueScopeFilter === "AUTO" ? "active filter-pill" : "filter-pill"}
                    onClick={() => setWikiIssueScopeFilter("AUTO")}
                    disabled={isBusy}
                  >
                    自动 {autoFixableIssues.length}
                  </button>
                  <button
                    className={wikiIssueScopeFilter === "MANUAL" ? "active filter-pill" : "filter-pill"}
                    onClick={() => setWikiIssueScopeFilter("MANUAL")}
                    disabled={isBusy}
                  >
                    人工 {reviewRequiredIssues.length}
                  </button>
                </div>
                <div className="wiki-inline-pills">
                  <button
                    className={wikiIssueTypeFilter === "ALL" ? "active filter-pill" : "filter-pill"}
                    onClick={() => setWikiIssueTypeFilter("ALL")}
                    disabled={isBusy}
                  >
                    全部类型
                  </button>
                  {wikiIssueTypes.map((issueType) => (
                    <button
                      key={`issue-type-${issueType}`}
                      className={wikiIssueTypeFilter === issueType ? "active filter-pill" : "filter-pill"}
                      onClick={() => setWikiIssueTypeFilter(issueType)}
                      disabled={isBusy}
                    >
                      {issueType}
                    </button>
                  ))}
                </div>
                <div className="wiki-inline-pills">
                  <button
                    className={wikiIssueSeverityFilter === "ALL" ? "active filter-pill" : "filter-pill"}
                    onClick={() => setWikiIssueSeverityFilter("ALL")}
                    disabled={isBusy}
                  >
                    全部级别
                  </button>
                  {wikiIssueSeverities.map((severity) => (
                    <button
                      key={`issue-severity-${severity}`}
                      className={wikiIssueSeverityFilter === severity ? "active filter-pill" : "filter-pill"}
                      onClick={() => setWikiIssueSeverityFilter(severity)}
                      disabled={isBusy}
                    >
                      {severity}
                    </button>
                  ))}
                </div>
              </div>
              {filteredWikiIssues.length === 0 && <p className="empty-state">当前过滤条件下暂无维护问题。</p>}
              {filteredWikiIssues.slice(0, 8).map((issue, index) => {
                const issueAction = getWikiIssuePrimaryAction(issue);
                return (
                  <div className="link-card" key={`${issue.issue_type}-${issue.title}-${index}`}>
                    <strong>{issue.issue_type} · {issue.severity}</strong>
                    <span>{issue.message}</span>
                    <span>{issue.auto_fixable ? "可自动补缺" : "需人工确认"}</span>
                    <span>{issue.suggested_action}</span>
                    <div className="wiki-inline-actions">
                      {issue.auto_fixable ? (
                        <button className="secondary-button" disabled={isBusy || !workspace} onClick={() => void autoFixWiki()}>
                          自动补缺
                        </button>
                      ) : null}
                      {issueAction ? (
                        <button className="secondary-button" disabled={isBusy} onClick={issueAction.run}>
                          {issueAction.label}
                        </button>
                      ) : null}
                      {issue.item_id ? (
                        <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(issue.item_id)}>
                          打开相关页面
                        </button>
                      ) : null}
                    </div>
                  </div>
                );
              })}
              <p className="section-label">Wiki Log</p>
              {wikiLog.slice(0, 4).map((entry) => (
                <div className="link-card" key={entry.id}>
                  <strong>{entry.event_type}</strong>
                  <span>{entry.message}</span>
                  <span>{formatDateTime(entry.created_at)}</span>
                  <div className="wiki-inline-actions">
                    {entry.item_id ? (
                      <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(entry.item_id!)}>
                        打开相关页面
                      </button>
                    ) : (
                      <button className="secondary-button" disabled={isBusy || !workspace} onClick={() => void openWikiIndex()}>
                        返回工作台总览
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </details>
          </aside>
        </section>
      ) : view === "memory" ? (
        <MemoryReviewWorkbench workspaceId={workspace?.workspace_id ?? ""} />
      ) : view === "research" ? (
        <section className="research-workbench">
          <Suspense fallback={(
            <aside className="research-index research-sidebar-loading">
              <p className="section-label">Deep Research</p>
              <span>正在加载独立研究控制台…</span>
            </aside>
          )}>
            <LazyResearchSidebar
              {...{
                summarizedResearchQuestion,
                summarizedResearchGoal,
                researchDeliverableFormat,
                researchProfile,
                researchDepth,
                researchType,
                researchScopeCount,
                researchQuestion,
                setResearchQuestion,
                researchGoal,
                setResearchGoal,
                setResearchProfile,
                setResearchDeliverableFormat,
                researchTimeRange,
                setResearchTimeRange,
                setResearchDepth,
                setResearchType,
                researchConstraintsText,
                setResearchConstraintsText,
                startDeepResearch,
                isBusy,
                workspace,
                loadResearchRunHistory,
                sources,
                selectedResearchSourceIds,
                currentSavedReportSource,
                focusedResearchSourceId,
                toggleResearchScope,
                setFocusedResearchSourceId,
                buildSourceOriginBadge,
                researchScopeSources,
                currentSavedReportSourceInScope,
                researchRuns,
                filteredResearchRuns,
                researchHistoryFilter,
                setResearchHistoryFilter,
                researchHistoryFilterLabel,
                researchTimelineMilestones,
                runContinuityBaselineById,
                buildRunContinuityBaselineLabel,
                buildRunContinuityNarrative,
                currentResearchRunSummary,
                sameStageLabel,
                timelineStageLabel,
                currentRunStageLabelFromSummary,
                openResearchRunHistoryItem,
                summarizeText,
                buildRunCheckpointNarrative,
                formatTimestamp,
                researchTimelinePath,
                buildRunSignalChips,
                buildRunPrimaryTone,
                readRecoveryTargets,
                currentResearchRunId,
                formatRecoveryTargetLabels,
                formatRecoveryTargetColumns,
                buildCurrentRecoveryNarrative,
              }}
            />
          </Suspense>

          <Suspense fallback={(
            <article className="research-page research-report-loading">
              <p className="section-label">Research Run</p>
              <span>正在加载研究主报告…</span>
            </article>
          )}>
            <LazyResearchReportPanel
              {...{
                currentResearchRun,
                formatTimestamp,
                buildReportRecoveryNarrative,
                currentResearchRunSummary,
                researchReportStructure,
                currentRecoveryTargets,
                refreshCurrentResearchRun,
                isBusy,
                currentResearchRunId,
                exportResearchReportMarkdown,
                saveResearchReportAsSource,
                setResearchDetailOpen,
                formatResearchAnswerStatus,
                currentFinalAnswer,
                resultSnapshotTitle,
                resultSnapshotNarrative,
                currentExecutiveSummary,
                currentResearchSourceEvidenceSummary,
                currentVerifiedFindings,
                currentIntentCompletionContract,
                currentKeyTakeaways,
                currentUncertaintyAndRisks,
                buildResearchSourceLabel,
                sourceById,
                summarizeText,
                currentSavedReportSource,
                currentEvidenceHighlights,
                researchClosedLoopState,
                buildArtifactRecoveryNarrative,
                currentRunSummaryRecoveryTargets,
                currentSavedReportSourceAsset,
                currentSavedReportSourceInScope,
                removeResearchSourceFromScope,
                addResearchSourceToScope,
                setFocusedResearchSourceId,
                researchTimelinePath,
              }}
            />
          </Suspense>

          <aside className="research-side">
            <p className="section-label">Research Detail</p>
            <div className="task-card">
              <strong>研究详情</strong>
              <span>主界面只保留研究主流程；checkpoint、verifier、trace、counterfactual 等高级信息统一放到详情弹窗。</span>
              {currentResearchRunSummary ? (
                <>
                  <small>{currentResearchRunSummary.status} · {currentResearchRunSummary.profile_key || "DEFAULT"} · checkpoints={currentResearchRunSummary.checkpoint_count}</small>
                  <small>rows={currentResearchRunSummary.ledger_row_count} · verified={currentResearchRunSummary.verified_row_count} · conflicted={currentResearchRunSummary.conflicted_row_count}</small>
                  {buildWaitContextNarrative(currentResearchWaitContext) ? (
                    <small>{buildWaitContextNarrative(currentResearchWaitContext)}</small>
                  ) : null}
                  {currentResearchWaitSignals.length > 0 ? (
                    <div className="signal-chip-row artifact-wait-signal-row">
                      {currentResearchWaitSignals.map((chip, index) => (
                        <span key={`current-research-wait-signal-${index}`} className={`signal-chip tone-${chip.tone}`}>
                          {chip.label}: {chip.value}
                        </span>
                      ))}
                    </div>
                  ) : null}
                  {currentResearchWaitDetails.length > 0 ? (
                    <div className="artifact-runtime-trace">
                      {currentResearchWaitDetails.map((line, index) => (
                        <small key={`current-research-wait-detail-${index}`} className="artifact-runtime-trace-line">
                          <strong>{line.label}</strong> · {line.value}
                        </small>
                      ))}
                    </div>
                  ) : null}
                </>
              ) : (
                <small>选择一个 run 后可查看完整研究详情。</small>
              )}
              <div className="research-inline-actions">
                <button
                  className="secondary-button"
                  type="button"
                  onClick={() => {
                    setResearchDetailOpen(true);
                  }}
                  disabled={!currentResearchRun}
                >
                  打开研究详情
                </button>
              </div>
            </div>
            {latestResearchTask ? (
              <div className="task-card">
                <strong>当前任务进度</strong>
                <span>{summarizeRunStatus(latestResearchTask.task_status)} · {latestResearchTask.progress_phase}</span>
                <small>{latestResearchTask.progress_message}</small>
                {buildWaitContextNarrative(latestResearchTask.wait_context) ? (
                  <small>{buildWaitContextNarrative(latestResearchTask.wait_context)}</small>
                ) : null}
                {latestResearchTaskWaitSignals.length > 0 ? (
                  <div className="signal-chip-row artifact-wait-signal-row">
                    {latestResearchTaskWaitSignals.map((chip, index) => (
                      <span key={`latest-research-task-wait-signal-${index}`} className={`signal-chip tone-${chip.tone}`}>
                        {chip.label}: {chip.value}
                      </span>
                    ))}
                  </div>
                ) : null}
                {latestResearchTaskWaitDetails.length > 0 ? (
                  <div className="artifact-runtime-trace">
                    {latestResearchTaskWaitDetails.map((line, index) => (
                      <small key={`latest-research-task-wait-detail-${index}`} className="artifact-runtime-trace-line">
                        <strong>{line.label}</strong> · {line.value}
                      </small>
                    ))}
                  </div>
                ) : null}
                {buildResearchTaskRuntimeSnapshot(latestResearchProgressEvent, latestResearchTask) ? (
                  <small>{buildResearchTaskRuntimeSnapshot(latestResearchProgressEvent, latestResearchTask)}</small>
                ) : null}
              </div>
            ) : null}
          </aside>

          {researchDetailOpen ? (
            <div
              className="research-detail-overlay"
              role="dialog"
              aria-modal="true"
              aria-label="Deep Research 研究详情"
              onClick={() => setResearchDetailOpen(false)}
            >
              <div className="research-detail-modal research-detail-modal-simple" onClick={(event) => event.stopPropagation()}>
                <div className="research-detail-header">
                  <div>
                    <p className="section-label">Research</p>
                    <h3>{currentResearchRun?.final_report_title || currentResearchRun?.question || "Deep Research"}</h3>
                    <small>
                      {formatResearchAnswerStatus(currentFinalAnswer.answer_status)} · {currentResearchRun?.source_scope.length ?? 0} 个资料来源 · {currentResearchSourceEvidenceSummary?.citation_count ?? 0} 条引用
                    </small>
                  </div>
                  <button type="button" className="secondary-button" onClick={() => setResearchDetailOpen(false)}>
                    关闭
                  </button>
                </div>
                <div className="research-detail-content research-detail-content-simple">
                  <section className="wiki-summary">
                    <strong>研究结果</strong>
                    <p>{currentFinalAnswer.answer_text || "研究正在进行中，完成后会在这里展示结论。"}</p>
                    {currentExecutiveSummary.length > 0 ? (
                      <div className="research-evidence-grid">
                        {currentExecutiveSummary.slice(0, 3).map((item, index) => (
                          <div key={`research-detail-summary-${index}`} className="link-card research-evidence-card">
                            <strong>要点 {index + 1}</strong>
                            <span>{item}</span>
                          </div>
                        ))}
                      </div>
                    ) : null}
                  </section>

                  <section className="wiki-citations">
                    <strong>研究进度</strong>
                    <span>{currentResearchRun?.status || latestResearchTask?.task_status || "等待开始"}</span>
                    <small>{buildResearchTaskRuntimeSnapshot(latestResearchProgressEvent, latestResearchTask) || (currentResearchProcessSummary ? `已纳入 ${currentResearchProcessSummary.source_scope_count} 个资料来源进行检索和阅读。` : "系统会在检索、阅读和汇总完成后更新结果。")}</small>
                    <small>
                      已纳入 {currentResearchRun?.source_scope.length ?? 0} 个来源 · 已确认 {currentResearchSourceEvidenceSummary?.verified_finding_count ?? currentVerifiedFindings.length} 条要点
                    </small>
                  </section>

                  <section className="wiki-citations">
                    <strong>引用与来源</strong>
                    {currentEvidenceHighlights.length > 0 ? currentEvidenceHighlights.slice(0, 5).map((finding, index) => (
                      <div key={`research-detail-source-${index}`} className="link-card research-evidence-card">
                        <strong>{buildResearchSourceLabel(finding, sourceById, "未命名来源")}</strong>
                        <span>{summarizeText(finding.claim_text || "暂无可展示的引用摘要", 180)}</span>
                      </div>
                    )) : (
                      <span>暂未返回可展示的来源；研究完成后会显示支撑结论的引用。</span>
                    )}
                  </section>

                  <div className="research-inline-actions">
                    <button type="button" className="secondary-button" onClick={() => void refreshCurrentResearchRun()} disabled={isBusy || !currentResearchRunId}>
                      刷新
                    </button>
                    <button type="button" className="secondary-button" onClick={() => void saveResearchReportAsSource()} disabled={isBusy || !currentResearchRun?.final_report_markdown}>
                      保存为资料
                    </button>
                  </div>
                </div>
              </div>
            </div>
          ) : null}

        </section>
      ) : (
        <section className="layout">
        <div className="chat-panel">
          <div className="mode-tabs">
            {routes.map((route) => (
              <button
                key={route.key}
                className={route.key === mode ? "active" : ""}
                title={route.description}
                onClick={() => setMode(route.key)}
              >
                {route.label}
              </button>
            ))}
          </div>

          <details className="source-drawer">
            <summary>
              <span>资料库</span>
              <small>{sources.length} 份资料</small>
            </summary>
            <div className="source-drawer-body">
              <label className="input-block">
                <span>资料内容</span>
                <textarea value={sourceText} onChange={(event) => setSourceText(event.target.value)} rows={4} />
              </label>
              <button onClick={uploadSource} disabled={isBusy || !workspace}>
                上传并解析
              </button>
              {latestTask && (
                <div className="task-card">
                  <strong>资料处理任务</strong>
                  <span>{latestTask.task_type} · {summarizeRunStatus(latestTask.task_status)} · {latestTask.progress_phase}</span>
                  <span>{latestTask.progress_message}</span>
                  {buildWaitContextNarrative(latestTask.wait_context) ? (
                    <small>{buildWaitContextNarrative(latestTask.wait_context)}</small>
                  ) : null}
                  {latestWorkspaceTaskWaitSignals.length > 0 ? (
                    <div className="signal-chip-row artifact-wait-signal-row">
                      {latestWorkspaceTaskWaitSignals.map((chip, index) => (
                        <span key={`workspace-task-wait-signal-${index}`} className={`signal-chip tone-${chip.tone}`}>
                          {chip.label}: {chip.value}
                        </span>
                      ))}
                    </div>
                  ) : null}
                  {latestWorkspaceTaskWaitDetails.length > 0 ? (
                    <div className="artifact-runtime-trace">
                      {latestWorkspaceTaskWaitDetails.map((line, index) => (
                        <small key={`workspace-task-wait-detail-${index}`} className="artifact-runtime-trace-line">
                          <strong>{line.label}</strong> · {line.value}
                        </small>
                      ))}
                    </div>
                  ) : null}
                  {buildGenericTaskRuntimeSnapshot(latestWorkspaceProgressEvent, latestTask) ? (
                    <small>{buildGenericTaskRuntimeSnapshot(latestWorkspaceProgressEvent, latestTask)}</small>
                  ) : null}
                  {taskEvents.map((event, index) => (
                    <small key={`${event.event}-${index}`}>{buildTaskEventNarrative(event, latestTask)}</small>
                  ))}
                </div>
              )}
              {sources.length > 0 && (
                <div className="task-card">
                  <strong>工作台资料</strong>
                  {sources.map((source) => (
                    <span key={source.source_id}>
                      {source.title} · {source.status} · {source.index_status}
                      {source.generated_by === "research_agent" ? ` · ${buildSourceOriginBadge(source)}` : ""}
                      <button className="inline-action" onClick={() => void deleteSource(source)} disabled={isBusy}>
                        删除资料
                      </button>
                    </span>
                  ))}
                </div>
              )}
            </div>
          </details>

          <div className="conversation">
            {messages.map((message, index) => (
              <div key={`${message.role}-${index}`} className={`bubble ${message.role}`}>
                <MessageBubble message={message} />
              </div>
            ))}
          </div>

          <label className="input-block">
            <span>{currentRoute().label} 问题</span>
            <textarea value={question} onChange={(event) => setQuestion(event.target.value)} rows={3} />
          </label>
          {mode === "qa" ? (
            <div className="qa-scope-hint">
              当前问答范围：{sources.length > 0 ? `此 Workspace 的 ${sources.length} 份资料` : "暂未上传资料"}。
              {selectedQaSourceIds.length > 0
                ? ` 已显式限定 ${selectedQaSourceIds.length} 份资料。`
                : " 回答会附带对应来源引用。"}
              {sources.length > 0 ? (
                <details>
                  <summary>指定本次 QA 的资料范围（可选）</summary>
                  <div className="qa-scope-options">
                    {sources.filter((source) => source.status === "READY" && source.index_status === "INDEXED").map((source) => (
                      <button
                        type="button"
                        key={source.source_id}
                        className={selectedQaSourceIds.includes(source.source_id) ? "active filter-pill" : "filter-pill"}
                        onClick={() => toggleQaScope(source.source_id)}
                        disabled={isBusy}
                      >
                        {source.title}
                      </button>
                    ))}
                    {selectedQaSourceIds.length > 0 ? (
                      <button type="button" className="secondary-button" onClick={() => setSelectedQaSourceIds([])} disabled={isBusy}>
                        使用全部资料
                      </button>
                    ) : null}
                  </div>
                </details>
              ) : " 先上传资料后即可开始基于证据的问答。"}
            </div>
          ) : null}
          <div className="composer-actions">
            <button onClick={sendMessage} disabled={isBusy || !conversation}>
              发送到 {currentRoute().label}
            </button>
            <button className="secondary-button" onClick={() => setArtifactComposerOpen(true)} disabled={isBusy || !workspace}>
              打开 Artifact Studio
            </button>
          </div>
        </div>

        {artifactComposerOpen ? <Suspense fallback={(
          <aside className="artifact-rail artifact-rail-loading">
            <p className="section-label">Artifact Studio</p>
            <span>正在加载产物控制台…</span>
          </aside>
        )}>
          <LazyArtifactRail
            {...{
              artifactComposerOpen,
              isBusy,
              setArtifactComposerOpen,
              selectedArtifactSkill,
              renderArtifactField,
              artifactCustomInstruction,
              setArtifactCustomInstruction,
              appendArtifactHint,
              workspace,
              prepareArtifactPrompt,
              isArtifactFormReady,
              artifactFormValues,
              launchArtifactPrompt,
              artifactStudioSkills,
              setSelectedArtifactSkillKey,
              setArtifactFormValues,
              artifactSidebarState,
              latestArtifactVersion,
              formatRelativeTime,
              saveArtifactVersionAsSource,
              artifactSavedSourceByVersionId,
              artifactVersionSaveKey,
              writeArtifactVersionToKnowledge,
              artifactWritebackByVersionId,
              regenerateArtifactVersion,
              compareArtifactWithPreviousVersion,
              rollbackArtifactVersion,
              downloadArtifactVersionPdf,
              resolveArtifactSkillTitle,
              summarizeRunStatus,
              openArtifactHistoryVersion,
              artifactHistoryLoadingKey,
              openWikiHome,
              openResearchWorkbench,
              openMemoryWorkbench,
              toggleWikiEnabled,
              wikiEnabled,
              noteTitle,
              setNoteTitle,
              saveLatestAnswerAsNote,
              lastAssistantMessageId,
              wikiRebuildAdvice,
            }}
          />
        </Suspense> : null}
      </section>
      )}
    </main>
  );
}

function artifactVersionSaveKey(version: { artifact_job_id: string; version_no: number }): string {
  return `${version.artifact_job_id}:${version.version_no}`;
}

async function streamEvents(path: string, onEvent: (event: StreamEvent) => void) {
  await consumeSse(path, (event) => onEvent(toStreamEvent(event.event, event.data, event.id)));
}

function updateLastAssistantMessage(current: Message[], answer: AnswerRunState) {
  let targetIndex = -1;
  for (let index = current.length - 1; index >= 0; index -= 1) {
    if (current[index].role === "assistant") {
      targetIndex = index;
      break;
    }
  }
  if (targetIndex < 0) {
    return current;
  }
  return current.map((message, index) => index === targetIndex
    ? {
      ...message,
      content: answer.content,
      citations: [...answer.citations],
      answerStatus: answer.status,
      answerError: answer.error
    }
    : message);
}

function updateAssistantMessageByRun(
  current: Message[],
  answerRunId: string,
  answer: AnswerRunState
) {
  return current.map((message) => message.answerRunId === answerRunId
    ? {
      ...message,
      content: answer.content,
      citations: [...answer.citations],
      answerStatus: answer.status,
      answerError: answer.error
    }
    : message);
}

function summarizeText(text: string, limit = 140) {
  if (text.length <= limit) {
    return text;
  }
  return `${text.slice(0, limit).trimEnd()}...`;
}

function formatTimestamp(value: string) {
  if (!value) {
    return "-";
  }
  const timestamp = new Date(value);
  if (Number.isNaN(timestamp.getTime())) {
    return value;
  }
  return timestamp.toLocaleString();
}

function formatRelativeTime(value: string) {
  if (!value) {
    return "时间未知";
  }
  const timestamp = new Date(value);
  if (Number.isNaN(timestamp.getTime())) {
    return value;
  }
  const diff = Date.now() - timestamp.getTime();
  const minute = 60 * 1000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (diff < minute) {
    return "刚刚";
  }
  if (diff < hour) {
    return `${Math.max(1, Math.floor(diff / minute))} 分钟前`;
  }
  if (diff < day) {
    return `${Math.max(1, Math.floor(diff / hour))} 小时前`;
  }
  return `${Math.max(1, Math.floor(diff / day))} 天前`;
}



function numberFromUnknown(value: unknown) {
  if (typeof value === "number" && !Number.isNaN(value)) {
    return value;
  }
  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value);
    return Number.isNaN(parsed) ? 0 : parsed;
  }
  return 0;
}

function asRecord(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return {};
  }
  return value as Record<string, unknown>;
}

function asRecordArray(value: unknown): Record<string, unknown>[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .filter((item) => item && typeof item === "object" && !Array.isArray(item))
    .map((item) => item as Record<string, unknown>);
}

function asStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .map((item) => typeof item === "string" ? item : "")
    .filter((item) => Boolean(item));
}

function readRecoveryTargets(value: unknown): ResearchRecoveryTargets | null {
  const record = asRecord(value);
  if (!Object.keys(record).length) {
    return null;
  }
  const requirementIds = asStringArray(record.requirement_ids);
  const requirementTypes = asStringArray(record.requirement_types);
  const requirementLabels = asStringArray(record.requirement_labels);
  const targetColumns = asStringArray(record.target_columns);
  const targetQueries = asStringArray(record.target_queries);
  const targetSources = asStringArray(record.target_sources);
  if (
    requirementIds.length === 0
    && requirementTypes.length === 0
    && requirementLabels.length === 0
    && targetColumns.length === 0
    && targetQueries.length === 0
    && targetSources.length === 0
  ) {
    return null;
  }
  return {
    requirement_ids: requirementIds,
    requirement_types: requirementTypes,
    requirement_labels: requirementLabels,
    target_columns: targetColumns,
    target_queries: targetQueries,
    target_sources: targetSources,
    requirement_count: numberFromUnknown(record.requirement_count) || requirementIds.length,
    query_count: numberFromUnknown(record.query_count) || targetQueries.length,
    source_count: numberFromUnknown(record.source_count) || targetSources.length,
    column_count: numberFromUnknown(record.column_count) || targetColumns.length,
  };
}

function asResearchFinalAnswer(value: unknown): ResearchFinalAnswer | null {
  const record = asRecord(value);
  if (!Object.keys(record).length) {
    return null;
  }
  return {
    answer_text: String(record.answer_text || "").trim(),
    answer_status: String(record.answer_status || "").trim(),
    confidence_label: String(record.confidence_label || "").trim(),
    coverage_label: String(record.coverage_label || "").trim(),
    source_basis: String(record.source_basis || "").trim(),
    ledger_row_count: numberFromUnknown(record.ledger_row_count)
  };
}


function readResearchFinalAnswer(
  reportStructure: ResearchReportStructure | null,
  verifiedFindings: ResearchRowSummary[],
  conflictedRows: ResearchRowSummary[],
  guardrailedRows: ResearchRowSummary[],
  intentContract: ResearchIntentCompletionContract | null
): ResearchFinalAnswer {
  const structured = asResearchFinalAnswer(reportStructure?.final_answer);
  if (structured && structured.answer_text) {
    return structured;
  }
  const topClaims = verifiedFindings
    .map((row) => row.claim_text || "")
    .filter((item) => Boolean(item))
    .slice(0, 3);
  let answerText = "当前还没有形成稳定的最终答案。";
  if (topClaims.length > 0) {
    answerText = `基于当前 verifier 批准的证据，研究结论为：${topClaims.join("；")}`;
  } else if (conflictedRows.length > 0) {
    answerText = `当前仍有 ${conflictedRows.length} 条冲突证据阻塞稳定结论，结果需要继续纠偏。`;
  } else if (guardrailedRows.length > 0) {
    answerText = `当前可以给出受控答案，但还有 ${guardrailedRows.length} 条 guardrailed finding 需要修复后再提升置信度。`;
  }
  const answerStatus = verifiedFindings.length > 0
    ? (conflictedRows.length > 0 ? "GUARDED" : "VERIFIED")
    : "RECOVERY_NEEDED";
  return {
    answer_text: answerText,
    answer_status: answerStatus,
    confidence_label: verifiedFindings.length > 0
      ? `已形成 ${verifiedFindings.length} 条 verifier 批准 finding`
      : "当前仍在恢复与验证阶段",
    coverage_label: intentContract
      ? `${intentContract.satisfied_requirement_count}/${intentContract.total_requirement_count} 个 intent requirement 已满足`
      : `${verifiedFindings.length} 条 finding 可用于合成`,
    source_basis: summarizeResearchSourceBasis(verifiedFindings),
    ledger_row_count: verifiedFindings.length + conflictedRows.length + guardrailedRows.length
  };
}

function readResearchExecutiveSummary(
  reportStructure: ResearchReportStructure | null,
  verifiedFindings: ResearchRowSummary[],
  conflictedRows: ResearchRowSummary[],
  guardrailedRows: ResearchRowSummary[],
  counterfactualSummary: ResearchCounterfactualSummary | null
) {
  const structured = asStringArray(reportStructure?.executive_summary);
  if (structured.length > 0) {
    return structured;
  }
  const summary = [
    verifiedFindings.length > 0
      ? `本次研究已沉淀 ${verifiedFindings.length} 条可直接合成答案的 finding。`
      : "本次研究仍处于受控恢复中，尚未形成稳定答案。"
  ];
  if (conflictedRows.length > 0) {
    summary.push(`${conflictedRows.length} 条 finding 仍处于冲突状态。`);
  } else if (guardrailedRows.length > 0) {
    summary.push(`${guardrailedRows.length} 条 finding 仍带 guardrails，需要继续修复。`);
  }
  if (counterfactualSummary?.has_counterfactual_recheck) {
    summary.push(`反证分支已介入，共触发 ${counterfactualSummary.counterfactual_branch_count} 个 counterfactual branch。`);
  }
  return summary;
}

function readResearchKeyTakeaways(
  reportStructure: ResearchReportStructure | null,
  verifiedFindings: ResearchRowSummary[]
) {
  const structured = asStringArray(reportStructure?.key_takeaways);
  if (structured.length > 0) {
    return structured;
  }
  return verifiedFindings
    .map((row) => row.claim_text || "")
    .filter((item) => Boolean(item))
    .slice(0, 4);
}

function readResearchEvidenceHighlights(
  reportStructure: ResearchReportStructure | null,
  verifiedFindings: ResearchRowSummary[],
  conflictedRows: ResearchRowSummary[],
  guardrailedRows: ResearchRowSummary[]
) {
  const structured = asResearchRows(reportStructure?.evidence_highlights);
  if (structured.length > 0) {
    return structured;
  }
  if (verifiedFindings.length > 0) {
    return verifiedFindings.slice(0, 3);
  }
  return [...conflictedRows, ...guardrailedRows].slice(0, 3);
}

function readResearchUncertaintyAndRisks(
  reportStructure: ResearchReportStructure | null,
  conflictedRows: ResearchRowSummary[],
  guardrailedRows: ResearchRowSummary[],
  intentContract: ResearchIntentCompletionContract | null
) {
  const structured = asStringArray(reportStructure?.uncertainty_and_risks);
  if (structured.length > 0) {
    return structured;
  }
  const risks: string[] = [];
  if (conflictedRows.length > 0) {
    risks.push(`仍有 ${conflictedRows.length} 条冲突 finding 可能改变最终结论。`);
  }
  if (guardrailedRows.length > 0) {
    risks.push(`仍有 ${guardrailedRows.length} 条 guardrailed finding 需要修复。`);
  }
  if (intentContract && intentContract.pending_requirement_count > 0) {
    risks.push(`还有 ${intentContract.pending_requirement_count} 个 intent requirement 未闭合。`);
  }
  if (risks.length === 0) {
    risks.push("当前没有明显的阻塞性冲突，答案可直接阅读。");
  }
  return risks;
}


function summarizeResearchSourceBasis(rows: ResearchRowSummary[]) {
  const titles = [...new Set(rows.map((row) => row.source_title || "").filter((item) => Boolean(item)))];
  if (titles.length === 0) {
    return "当前还没有 verifier 批准的来源基础。";
  }
  return `${titles.length} 个来源支撑：${titles.slice(0, 3).join(" / ")}`;
}

function formatResearchAnswerStatus(status: string) {
  switch ((status || "").toUpperCase()) {
    case "VERIFIED":
      return "Verifier Approved";
    case "GUARDED":
      return "Guarded";
    case "RECOVERY_NEEDED":
      return "Recovery Needed";
    default:
      return status || "Unknown";
  }
}

function formatRecoveryTargetLabels(targets: ResearchRecoveryTargets | null) {
  if (!targets) {
    return "";
  }
  return targets.requirement_labels.slice(0, 2).join(" / ");
}

function formatRecoveryTargetColumns(targets: ResearchRecoveryTargets | null) {
  if (!targets) {
    return "";
  }
  return targets.target_columns.slice(0, 3).join(", ");
}

function formatRecoveryTargetQueries(targets: ResearchRecoveryTargets | null) {
  if (!targets) {
    return "";
  }
  return targets.target_queries.slice(0, 2).join(" / ");
}

function formatRecoveryTargetSources(targets: ResearchRecoveryTargets | null) {
  if (!targets) {
    return "";
  }
  return targets.target_sources.slice(0, 2).join(" / ");
}

function summarizeRecoveryTargetTypes(targets: ResearchRecoveryTargets | null) {
  if (!targets) {
    return "";
  }
  const normalized = [...new Set(targets.requirement_types.map((item) => {
    switch (item) {
      case "CONFLICT_FINDING":
        return "conflict";
      case "CONSTRAINT_FINDING":
        return "evidence";
      case "GOAL_FINDING":
        return "goal";
      default:
        return normalizeSignalValue(item).toLowerCase() || "target";
    }
  }))];
  return normalized.slice(0, 2).join(" / ");
}

function summarizeRecoveryTargetFocus(targets: ResearchRecoveryTargets | null) {
  if (!targets) {
    return "";
  }
  const columns = targets.target_columns.slice(0, 2).join(", ");
  if (columns) {
    return columns;
  }
  const sources = targets.target_sources.slice(0, 1).join(" / ");
  if (sources) {
    return sources;
  }
  const queries = targets.target_queries.slice(0, 1).join(" / ");
  if (queries) {
    return summarizeText(queries, 32);
  }
  return `${targets.requirement_count || 0} targets`;
}

function buildRecoveryTargetNarrativeFragment(targets: ResearchRecoveryTargets | null) {
  if (!targets) {
    return "";
  }
  const types = summarizeRecoveryTargetTypes(targets);
  const columns = formatRecoveryTargetColumns(targets);
  const queries = formatRecoveryTargetQueries(targets);
  const sources = formatRecoveryTargetSources(targets);
  if (columns) {
    return `当前纠偏靶点：${types || "target"} requirement，恢复焦点已收敛到 columns ${columns}`;
  }
  if (queries) {
    return `当前纠偏靶点：${types || "target"} requirement，恢复焦点正在围绕 queries ${summarizeText(queries, 48)}`;
  }
  if (sources) {
    return `当前纠偏靶点：${types || "target"} requirement，恢复焦点正在围绕 sources ${sources}`;
  }
  if (types) {
    return `当前纠偏靶点：${types} requirement`;
  }
  return `当前纠偏靶点：${targets.requirement_count || 0} 个 requirement`;
}

function buildCurrentRecoveryNarrative(recoveryMode: string, targets: ResearchRecoveryTargets | null) {
  const normalizedMode = normalizeSignalValue(recoveryMode);
  const targetNarrative = buildRecoveryTargetNarrativeFragment(targets);
  if (normalizedMode && targetNarrative) {
    return `当前恢复说明：strategy=${normalizedMode}；${targetNarrative}。`;
  }
  if (targetNarrative) {
    return `当前恢复说明：${targetNarrative}。`;
  }
  if (normalizedMode) {
    return `当前恢复说明：当前 closed-loop 处于 ${normalizedMode}。`;
  }
  return "";
}


function buildReportRecoveryNarrative(recoveryMode: string, targets: ResearchRecoveryTargets | null) {
  const narrative = buildCurrentRecoveryNarrative(recoveryMode, targets);
  if (!narrative) {
    return "";
  }
  return narrative.replace("当前恢复说明：", "报告区纠偏说明：");
}

function buildArtifactRecoveryNarrative(recoveryMode: string, targets: ResearchRecoveryTargets | null) {
  const narrative = buildCurrentRecoveryNarrative(recoveryMode, targets);
  if (!narrative) {
    return "";
  }
  return narrative.replace("当前恢复说明：", "产物出口纠偏说明：");
}


function buildLifecycleCreationNarrative(
  question: string,
  researchGoal: string,
  depth: string,
  sourceScopeCount: number
) {
  const goalText = researchGoal.trim() || summarizeText(question, 48) || "显式研究问题";
  const scopeText = sourceScopeCount > 0
    ? `显式资料范围 ${sourceScopeCount} 份`
    : "当前未附带显式资料范围";
  return `生命周期说明：新建 run 将围绕 ${goalText} 进入 closed-loop research，depth=${depth || "STANDARD"}，${scopeText}。`;
}















































function buildSourceOriginBadge(source: SourceAsset | SaveResearchReportSource) {
  if (source.generated_by === "research_agent") {
    return `Research Report(${source.generated_ref_id || "unknown run"})`;
  }
  return "Workspace Source";
}

function buildKnowledgeCitationLabel(citation: KnowledgeCitation) {
  if (citation.generated_by === "research_agent") {
    return `${citation.title} · Research Report(${citation.generated_ref_id || "unknown run"})`;
  }
  return citation.title;
}

function buildResearchSourceLabel(
  item: Record<string, unknown> | ResearchRowSummary | null | undefined,
  sourceById: Map<string, SourceAsset>,
  fallback: string
) {
  const record = item && typeof item === "object" ? item as Record<string, unknown> : {};
  const sourceId = String(record.source_id ?? "").trim();
  const sourceTitle = String(record.source_title ?? record.title ?? "").trim();
  const directGeneratedBy = String(record.generated_by ?? "").trim();
  const directGeneratedRefId = String(record.generated_ref_id ?? "").trim();
  const source = sourceId ? sourceById.get(sourceId) : undefined;
  const generatedBy = directGeneratedBy || source?.generated_by || "";
  const generatedRefId = directGeneratedRefId || source?.generated_ref_id || "";
  const title = sourceTitle || source?.title || fallback;
  if (generatedBy === "research_agent") {
    return `${title} · Research Report(${generatedRefId || "unknown run"})`;
  }
  return title;
}

function buildRunCheckpointNarrative(run: ResearchRunSummary) {
  const checkpointCount = numberFromUnknown(run.checkpoint_count);
  if (checkpointCount <= 0) {
    return "Checkpoint 命中：当前 run 尚未沉淀显式 checkpoint。";
  }
  const recoveryTargets = readRecoveryTargets(run.recovery_targets);
  const recoveryNarrative = buildCurrentRecoveryNarrative(run.recovery_mode || "", recoveryTargets);
  return recoveryNarrative
    ? `Checkpoint 命中：已沉淀 ${checkpointCount} 个 checkpoint；${recoveryNarrative}`
    : `Checkpoint 命中：已沉淀 ${checkpointCount} 个 checkpoint。`;
}

function branchLaneLabel(branchId: string) {
  const normalized = normalizeSignalValue(branchId);
  if (!normalized || normalized === "branch-main") {
    return "主分支";
  }
  return "反证分支";
}


function buildBranchCheckpointDeltaNarrative(
  baselineBranchId: string,
  baselineCheckpointCount: number,
  baselineCounterfactualBranchCount: number,
  currentBranchId: string,
  currentCheckpointCount: number,
  currentCounterfactualBranchCount: number
) {
  const baselineLane = branchLaneLabel(baselineBranchId);
  const currentLane = branchLaneLabel(currentBranchId);
  if (baselineCheckpointCount <= 0 && currentCheckpointCount <= 0) {
    return "分支沉淀判断：baseline 与 current 都还没有显式 checkpoint 沉淀。";
  }
  if (baselineLane !== currentLane) {
    return `分支沉淀判断：已从${baselineLane}切换到${currentLane}；baseline checkpoint=${baselineCheckpointCount}，current checkpoint=${currentCheckpointCount}。`;
  }
  if (currentCheckpointCount > baselineCheckpointCount) {
    return `分支沉淀判断：继续在${currentLane}上累积 checkpoint；baseline=${baselineCheckpointCount}，current=${currentCheckpointCount}。`;
  }
  if (currentCheckpointCount < baselineCheckpointCount) {
    return `分支沉淀判断：当前${currentLane}可见 checkpoint 少于 baseline；baseline=${baselineCheckpointCount}，current=${currentCheckpointCount}。`;
  }
  if (currentCounterfactualBranchCount !== baselineCounterfactualBranchCount) {
    return `分支沉淀判断：checkpoint 数量保持 ${currentCheckpointCount}，但 counterfactual branches 从 ${baselineCounterfactualBranchCount} 变为 ${currentCounterfactualBranchCount}。`;
  }
  return `分支沉淀判断：baseline 与 current 都停留在${currentLane}，checkpoint 沉淀规模保持 ${currentCheckpointCount}。`;
}

function buildContinuityStateNarrative(
  baselineBranchId: string,
  baselineCheckpointCount: number,
  baselineCounterfactualBranchCount: number,
  currentBranchId: string,
  currentCheckpointCount: number,
  currentCounterfactualBranchCount: number,
  baselineVerifiedCount: number,
  currentVerifiedCount: number,
  baselineConflictedCount: number,
  currentConflictedCount: number
) {
  const branchNarrative = buildBranchCheckpointDeltaNarrative(
    baselineBranchId,
    baselineCheckpointCount,
    baselineCounterfactualBranchCount,
    currentBranchId,
    currentCheckpointCount,
    currentCounterfactualBranchCount
  );
  const verifiedDelta = currentVerifiedCount - baselineVerifiedCount;
  const conflictedDelta = currentConflictedCount - baselineConflictedCount;
  let stateJudgement = "闭环状态整体保持平移。";
  if (verifiedDelta > 0 && conflictedDelta < 0) {
    stateJudgement = "闭环状态表现为收敛推进：verified 上升且 conflicted 下降。";
  } else if (verifiedDelta > 0 && conflictedDelta === 0) {
    stateJudgement = "闭环状态表现为结论增厚：verified 上升而 conflicted 持平。";
  } else if (verifiedDelta === 0 && conflictedDelta < 0) {
    stateJudgement = "闭环状态表现为冲突消解：verified 持平而 conflicted 下降。";
  } else if (verifiedDelta < 0 && conflictedDelta > 0) {
    stateJudgement = "闭环状态表现为重新失稳：verified 回落且 conflicted 上升。";
  } else if (verifiedDelta < 0) {
    stateJudgement = "闭环状态仍在重排：verified 数量较基线回落。";
  } else if (conflictedDelta > 0) {
    stateJudgement = "闭环状态仍在扩散冲突：conflicted 数量较基线增加。";
  }
  return `${branchNarrative} Table-as-State 从 verified ${baselineVerifiedCount} -> ${currentVerifiedCount}、conflicted ${baselineConflictedCount} -> ${currentConflictedCount}，${stateJudgement}`;
}

function buildRunContinuityBaselineLabel(
  baselineRun: ResearchRunSummary | null,
  currentRun: ResearchRunSummary
) {
  if (!baselineRun) {
    return "";
  }
  if (currentRun.resumed_from_research_run_id && baselineRun.research_run_id === currentRun.resumed_from_research_run_id) {
    return `resume source ${baselineRun.research_run_id} #${currentRun.resumed_from_checkpoint_no ?? "-"}`;
  }
  return `previous run ${baselineRun.research_run_id}`;
}

function buildRunContinuityNarrative(
  baselineRun: ResearchRunSummary | null,
  currentRun: ResearchRunSummary
) {
  if (!baselineRun) {
    return "";
  }
  const baselineSnapshot = extractRunSummarySnapshot(baselineRun);
  const currentSnapshot = extractRunSummarySnapshot(currentRun);
  return buildContinuityStateNarrative(
    baselineSnapshot.activeBranchId,
    baselineSnapshot.checkpointCount,
    baselineSnapshot.counterfactualBranchCount,
    currentSnapshot.activeBranchId,
    currentSnapshot.checkpointCount,
    currentSnapshot.counterfactualBranchCount,
    baselineSnapshot.verifiedRowCount,
    currentSnapshot.verifiedRowCount,
    baselineSnapshot.conflictedRowCount,
    currentSnapshot.conflictedRowCount
  );
}

function findRunContinuityBaseline(
  runs: ResearchRunSummary[],
  currentRun: ResearchRunSummary | null | undefined
) {
  if (!currentRun) {
    return null;
  }
  const chronologicalRuns = [...runs].sort(compareResearchRunsByTimeAsc);
  const currentIndex = chronologicalRuns.findIndex((run) => run.research_run_id === currentRun.research_run_id);
  if (currentRun.resumed_from_research_run_id) {
    return chronologicalRuns.find((run) => run.research_run_id === currentRun.resumed_from_research_run_id) ?? null;
  }
  if (currentIndex <= 0) {
    return null;
  }
  return chronologicalRuns[currentIndex - 1] ?? null;
}

function labelTaskType(taskType: string) {
  switch (normalizeSignalValue(taskType)) {
    case "RESEARCH_RUN":
      return "Deep Research 任务";
    case "SOURCE_PARSE":
      return "资料处理任务";
    case "WIKI_INGEST":
      return "Wiki ingest 任务";
    case "WIKI_RETRACT":
      return "Wiki retract 任务";
    case "ARTIFACT_JOB":
      return "产物任务";
    default:
      return normalizeSignalValue(taskType) || "任务";
  }
}

function readTaskEventPhase(event: StreamEvent, task: TaskStatus | null) {
  const payload = asRecord(event.payload);
  return normalizeSignalValue(payload.phase ?? task?.progress_phase);
}

function readTaskEventMetrics(event: StreamEvent) {
  const payload = asRecord(event.payload);
  return asRecord(payload.metrics);
}









function formatTaskMetric(metric: string, value: string | number | null | undefined) {
  if (value == null || value === "") {
    return "";
  }
  return `${metric} ${value}`;
}

function buildResearchTaskMetricSummary(event: StreamEvent, limit = 6) {
  const payload = asRecord(event.payload);
  const metrics = readTaskEventMetrics(event);
  const items = [
    formatTaskMetric("scope", numberFromUnknown(metrics.source_count) || undefined),
    formatTaskMetric("query", numberFromUnknown(metrics.query_count) || undefined),
    formatTaskMetric("hits", numberFromUnknown(metrics.search_hits) || undefined),
    formatTaskMetric("windows", numberFromUnknown(metrics.read_windows) || undefined),
    formatTaskMetric("evidence", numberFromUnknown(metrics.evidence_cards) || undefined),
    formatTaskMetric("rows", numberFromUnknown(metrics.ledger_rows) || undefined),
    formatTaskMetric("cells", numberFromUnknown(metrics.ledger_cells) || undefined),
    formatTaskMetric("branches", numberFromUnknown(metrics.branch_count) || undefined),
    formatTaskMetric("rounds", numberFromUnknown(metrics.loop_rounds) || undefined),
    formatTaskMetric("local", normalizeSignalValue(metrics.local_status) || undefined),
    formatTaskMetric("global", normalizeSignalValue(metrics.global_status) || undefined),
    formatTaskMetric("loop", normalizeSignalValue(metrics.loop_decision) || undefined),
    formatTaskMetric("progress", numberFromUnknown(payload.progress_percent) > 0 ? `${numberFromUnknown(payload.progress_percent)}%` : undefined),
  ].filter(Boolean);
  if (!items.length) {
    return "";
  }
  return `当前闭环计量：${items.slice(0, limit).join(" · ")}`;
}


function buildResearchTaskRuntimeSnapshot(event: StreamEvent | null, task: TaskStatus | null) {
  if (!event) {
    return "";
  }
  const phase = readTaskEventPhase(event, task);
  const metricSummary = buildResearchTaskMetricSummary(event, 8);
  if (!metricSummary) {
    return "";
  }
  switch (phase) {
    case "SEARCHING":
      return `Research Harness runtime snapshot：当前处于检索扩展段。${metricSummary}`;
    case "READING":
      return `Research Harness runtime snapshot：当前处于读窗压缩段。${metricSummary}`;
    case "EXTRACTING":
      return `Research Harness runtime snapshot：当前正在把读窗沉淀为 Table-as-State。${metricSummary}`;
    case "VERIFYING":
      return `Research Harness runtime snapshot：当前正在进入 Dual Verifier / 反证分支判断。${metricSummary}`;
    case "WRITING":
      return `Research Harness runtime snapshot：当前正在把闭环结果固化为报告产物。${metricSummary}`;
    default:
      return `Research Harness runtime snapshot：${metricSummary}`;
  }
}







function buildGenericTaskRuntimeSnapshot(event: StreamEvent | null, task: TaskStatus | null) {
  if (!event || !task) {
    return "";
  }
  const payload = asRecord(event.payload);
  const progressPercent = numberFromUnknown(payload.progress_percent);
  const phase = readTaskEventPhase(event, task);
  const metrics = readTaskEventMetrics(event);
  const base = [
    phase ? `phase ${phase}` : "",
    progressPercent > 0 ? `progress ${progressPercent}%` : "",
    Object.keys(metrics).length ? `metrics ${Object.keys(metrics).length}` : ""
  ].filter(Boolean).join(" · ");
  if (!base) {
    return "";
  }
  return `${labelTaskType(task.task_type)} runtime snapshot：${base}`;
}



function buildTaskEventNarrative(event: StreamEvent, task: TaskStatus | null) {
  const taskLabel = labelTaskType(task?.task_type || "");
  const phase = readTaskEventPhase(event, task);
  const message = summarizeText(event.message || event.data || task?.progress_message || "", 96);
  switch (event.event) {
    case "task.status":
      switch (normalizeSignalValue(task?.task_type)) {
        case "SOURCE_PARSE":
          return "资料处理链路已接管上传内容，准备解析、切片并建立检索索引。";
        case "WIKI_RETRACT":
          return "工作台已开始清理资料删除带来的 Wiki 回链影响。";
        case "WIKI_INGEST":
          return "工作台已开始按资料更新 Wiki 页面与回链。";
        case "ARTIFACT_JOB":
          return "产物任务已入队，等待版本化与资料回流。";
        default:
          return `${taskLabel}已入队。${message}`;
      }
    case "task.heartbeat": {
      const heartbeatAt = String(asRecord(event.payload).heartbeat_at ?? "").trim();
      return `${taskLabel}保活心跳：${heartbeatAt || "worker running"}`;
    }
    case "task.completed":
      return `${taskLabel}已完成：${message || "当前任务已经处理完成。"}`;
    case "task.failed":
      return `${taskLabel}失败：${message || summarizeText(task?.error_message || "任务执行中断", 96)}。`;
    case "task.progress":
    default:
      switch (normalizeSignalValue(task?.task_type)) {
        case "SOURCE_PARSE":
          if (phase === "PARSING") {
            return "资料正在解析、切片并写入本地检索索引。";
          }
          if (phase === "INDEXED") {
            return "资料已经进入检索索引，可继续用于 QA / Note / Wiki 链路。";
          }
          return `资料处理正在推进：${message || "等待下一个阶段。"}`;
        case "WIKI_RETRACT":
          return `Wiki retract 正在清理受影响页面：${message || "处理中。"}`;
        case "WIKI_INGEST":
          return `Wiki ingest 正在根据资料更新工作台页面：${message || "处理中。"}`;
        case "ARTIFACT_JOB":
          return `产物任务正在推进版本化与资料回流：${message || "处理中。"}`;
        default:
          return `${taskLabel}正在推进：${message || "处理中。"}`;
      }
  }
}

function readIntentCompletionContract(value: unknown): ResearchIntentCompletionContract | null {
  const record = asRecord(value);
  if (!Object.keys(record).length) {
    return null;
  }
  return {
    status: String(record.status ?? ""),
    reason_code: String(record.reason_code ?? ""),
    total_requirement_count: numberFromUnknown(record.total_requirement_count),
    satisfied_requirement_count: numberFromUnknown(record.satisfied_requirement_count),
    pending_requirement_count: numberFromUnknown(record.pending_requirement_count),
    missing_requirement_labels: asStringArray(record.missing_requirement_labels),
    requirements: asRecordArray(record.requirements).map((requirement) => ({
      requirement_id: String(requirement.requirement_id ?? ""),
      requirement_type: String(requirement.requirement_type ?? ""),
      label: String(requirement.label ?? ""),
      status: String(requirement.status ?? ""),
      evidence_anchor: String(requirement.evidence_anchor ?? ""),
      evidence_refs: asStringArray(requirement.evidence_refs),
      coverage_note: String(requirement.coverage_note ?? ""),
      missing_reason: String(requirement.missing_reason ?? ""),
    })),
  };
}

function asResearchRows(value: unknown): ResearchRowSummary[] {
  return asRecordArray(value) as ResearchRowSummary[];
}

function compareResearchRunsByTimeAsc(left: ResearchRunSummary, right: ResearchRunSummary) {
  const leftTime = Date.parse(left.created_at || left.updated_at || "");
  const rightTime = Date.parse(right.created_at || right.updated_at || "");
  return leftTime - rightTime;
}

function researchHistoryFilterLabel(filter: ResearchHistoryFilter) {
  switch (filter) {
    case "RECOVERY":
      return "恢复";
    case "CONFLICT":
      return "冲突";
    case "STABLE":
      return "稳定";
    case "RESUMED":
      return "续跑";
    default:
      return "全部";
  }
}

function matchesResearchHistoryFilter(run: ResearchRunSummary, filter: ResearchHistoryFilter) {
  if (filter === "ALL") {
    return true;
  }
  if (filter === "RESUMED") {
    return isResumedRun(run);
  }
  if (filter === "RECOVERY") {
    return isRecoveryRun(run);
  }
  if (filter === "CONFLICT") {
    return isConflictRun(run);
  }
  if (filter === "STABLE") {
    return isStableRun(run);
  }
  return true;
}

function buildRunPrimaryTone(run: ResearchRunSummary): SignalTone {
  const reasons = [
    run.local_verifier_reason,
    run.global_verifier_reason,
    run.final_loop_reason,
    run.recovery_mode,
    run.research_intent_alignment_reason
  ].map(normalizeSignalValue);
  const hasConflict = run.conflicted_row_count > 0 || reasons.some(isConflictSignal);
  if (hasConflict) {
    return "conflict";
  }
  const hasRecovery = Boolean(run.recovery_mode) || (run.counterfactual_summary?.counterfactual_branch_count ?? 0) > 0 || reasons.some(isRecoverySignal);
  if (hasRecovery) {
    return "recovery";
  }
  if (run.resumed_from_research_run_id) {
    return "resume";
  }
  if (reasons.some(isStableSignal) || run.global_verifier_decision === "READY_TO_WRITE") {
    return "stable";
  }
  return "neutral";
}

function buildResearchTimelineMilestones(runs: ResearchRunSummary[]): ResearchTimelineMilestone[] {
  const milestones: ResearchTimelineMilestone[] = [];
  const firstConflict = runs.find(isConflictRun);
  const firstRecovery = runs.find(isRecoveryRun);
  const firstResumed = runs.find(isResumedRun);
  const recoveryAnchorIndex = runs.findIndex((run) => isConflictRun(run) || isRecoveryRun(run) || isResumedRun(run));
  const firstStableAfterDrift = recoveryAnchorIndex >= 0
    ? runs.slice(recoveryAnchorIndex + 1).find(isStableRun)
    : null;

  pushMilestone(milestones, firstConflict, "first-conflict", "首次失稳", "首次进入 conflict / guardrail path", "conflict");
  pushMilestone(milestones, firstRecovery, "first-recovery", "首次恢复", "首次进入 recovery / counterfactual path", "recovery");
  pushMilestone(milestones, firstResumed, "first-resume", "首次续跑", "首次从 checkpoint 或 lineage 恢复任务", "resume");
  pushMilestone(milestones, firstStableAfterDrift, "first-restable", "首次回稳", "在 drift / recovery 之后第一次回到 stable path", "stable");

  return milestones;
}

function buildResearchTimelinePathSummary(
  runs: ResearchRunSummary[],
  milestones: ResearchTimelineMilestone[],
  currentRun: ResearchRunSummary | null
): ResearchTimelinePathSummary {
  if (runs.length === 0 && milestones.length === 0) {
    return {
      stageLabels: [],
      currentStageLabel: "",
      hasRestabilized: false,
      narrative: "",
      currentRunStageLabel: "",
      currentRunAlignedWithPath: false
    };
  }
  const stageLabels: string[] = [];
  const firstStableRun = runs.find(isStableRun);
  const recoveryAnchorRun = currentRun && (isConflictRun(currentRun) || isRecoveryRun(currentRun) || isResumedRun(currentRun))
    ? currentRun
    : [...runs].reverse().find((run) => isConflictRun(run) || isRecoveryRun(run) || isResumedRun(run)) ?? null;
  const recoveryNarrative = buildRecoveryTargetNarrativeFragment(
    recoveryAnchorRun ? readRecoveryTargets(recoveryAnchorRun.recovery_targets) : null
  );
  if (firstStableRun) {
    stageLabels.push("稳定");
  }
  const orderedMilestones = [...milestones].sort((left, right) => compareResearchRunsByTimeAsc(left.run, right.run));
  for (const milestone of orderedMilestones) {
    const nextLabel = timelineStageLabel(milestone);
    if (stageLabels[stageLabels.length - 1] !== nextLabel) {
      stageLabels.push(nextLabel);
    }
  }
  const currentStageLabel = stageLabels[stageLabels.length - 1] ?? "";
  const currentRunStageLabel = currentRun ? currentRunStageLabelFromSummary(currentRun) : "";
  const currentRunAlignedWithPath = Boolean(currentRunStageLabel) && currentRunStageLabel === currentStageLabel;
  const hasRestabilized = stageLabels.lastIndexOf("稳定") > stageLabels.findIndex((label) => label !== "稳定");
  const narrative = hasRestabilized
    ? recoveryNarrative
      ? `研究路径已经经历失稳与恢复，并重新回到相对稳定阶段。最近一次 closed-loop recovery 中，${recoveryNarrative}。`
      : "研究路径已经经历失稳与恢复，并重新回到相对稳定阶段。"
    : stageLabels.includes("恢复")
      ? recoveryNarrative
        ? `研究路径已经进入恢复或反证纠偏阶段，${recoveryNarrative}，尚需继续观察是否重新收敛。`
        : "研究路径已经进入恢复或反证纠偏阶段，尚需继续观察是否重新收敛。"
      : stageLabels.includes("冲突")
        ? recoveryNarrative
          ? `研究路径已经出现冲突或 guardrail 信号，${recoveryNarrative}，后续重点关注 recovery 是否启动。`
          : "研究路径已经出现冲突或 guardrail 信号，后续重点关注 recovery 是否启动。"
        : "当前历史仍以稳定路径为主，尚未出现明显 recovery 闭环。";
  return {
    stageLabels,
    currentStageLabel,
    hasRestabilized,
    narrative,
    currentRunStageLabel,
    currentRunAlignedWithPath
  };
}

function pushMilestone(
  milestones: ResearchTimelineMilestone[],
  run: ResearchRunSummary | null | undefined,
  key: string,
  label: string,
  description: string,
  tone: SignalTone
) {
  if (!run || milestones.some((entry) => entry.run.research_run_id === run.research_run_id)) {
    return;
  }
  const recoveryNarrative = buildRecoveryTargetNarrativeFragment(readRecoveryTargets(run.recovery_targets));
  milestones.push({
    key,
    label,
    description: recoveryNarrative ? `${description}；${recoveryNarrative}` : description,
    tone,
    run,
    chips: buildRunSignalChips(run)
  });
}

function timelineStageLabel(milestone: ResearchTimelineMilestone) {
  if (milestone.key === "first-resume") {
    return "续跑";
  }
  if (milestone.key === "first-restable") {
    return "回稳";
  }
  switch (milestone.tone) {
    case "conflict":
      return "冲突";
    case "recovery":
      return "恢复";
    case "stable":
      return "稳定";
    case "resume":
      return "续跑";
    default:
      return milestone.label;
  }
}

function currentRunStageLabelFromSummary(run: ResearchRunSummary) {
  if (isConflictRun(run)) {
    return "冲突";
  }
  if (isRecoveryRun(run)) {
    return "恢复";
  }
  if (isStableRun(run)) {
    return "稳定";
  }
  if (isResumedRun(run)) {
    return "续跑";
  }
  return run.status === "QUEUED" ? "排队" : "观察";
}

function sameStageLabel(left: string, right: string) {
  return normalizeStageLabel(left) === normalizeStageLabel(right);
}

function normalizeStageLabel(label: string) {
  if (label === "回稳") {
    return "稳定";
  }
  return label;
}

function buildRunSignalChips(run: ResearchRunSummary): SignalChip[] {
  const chips: SignalChip[] = [];
  const recoveryTargets = readRecoveryTargets(run.recovery_targets);
  pushSignalChip(chips, run.resumed_from_research_run_id ? {
    label: "lineage",
    value: `resume #${run.resumed_from_checkpoint_no ?? "-"}`,
    tone: "resume"
  } : null);
  pushSignalChip(chips, run.local_verifier_reason ? {
    label: "local",
    value: run.local_verifier_reason,
    tone: classifySignalTone(run.local_verifier_reason, run.recovery_mode)
  } : null);
  pushSignalChip(chips, run.global_verifier_reason ? {
    label: "global",
    value: run.global_verifier_reason,
    tone: classifySignalTone(run.global_verifier_reason, run.recovery_mode)
  } : null);
  pushSignalChip(chips, run.final_loop_reason ? {
    label: "loop",
    value: run.final_loop_reason,
    tone: classifySignalTone(run.final_loop_reason, run.recovery_mode)
  } : null);
  pushSignalChip(chips, run.recovery_mode ? {
    label: "recovery",
    value: run.recovery_mode,
    tone: "recovery"
  } : null);
  pushSignalChip(chips, recoveryTargets ? {
    label: "target",
    value: summarizeRecoveryTargetTypes(recoveryTargets),
    tone: recoveryTargets.requirement_types.includes("CONFLICT_FINDING") ? "conflict" : "recovery"
  } : null);
  pushSignalChip(chips, recoveryTargets ? {
    label: "focus",
    value: summarizeRecoveryTargetFocus(recoveryTargets),
    tone: "recovery"
  } : null);
  pushSignalChip(chips, run.research_intent_alignment_reason ? {
    label: "intent",
    value: `${run.research_intent_alignment_status || "WARN"}:${run.research_intent_alignment_reason}`,
    tone: run.research_intent_alignment_status === "PASS" ? "stable" : "recovery"
  } : null);
  return chips.slice(0, 6);
}


function pushSignalChip(chips: SignalChip[], chip: SignalChip | null) {
  if (!chip || !chip.value) {
    return;
  }
  if (chips.some((entry) => entry.label === chip.label && entry.value === chip.value)) {
    return;
  }
  chips.push(chip);
}


function normalizeSignalValue(value: unknown) {
  return typeof value === "string" ? value.trim() : "";
}

function classifySignalTone(reason: string, recoveryMode: string): SignalTone {
  if (recoveryMode) {
    return "recovery";
  }
  if (isConflictSignal(reason)) {
    return "conflict";
  }
  if (isRecoverySignal(reason)) {
    return "recovery";
  }
  if (isStableSignal(reason)) {
    return "stable";
  }
  return "neutral";
}

function isConflictSignal(reason: string) {
  return /CONFLICT|LOW_CONFIDENCE|GUARDRAIL|WRITE_WITH_GUARDRAILS/i.test(reason);
}

function isRecoverySignal(reason: string) {
  return /COUNTERFACTUAL|RECOVER|READ_MORE|EXTRACT_AGAIN|INTENT_REQUIREMENTS_PARTIAL|RESEARCH_INTENT_PARTIAL/i.test(reason);
}

function isStableSignal(reason: string) {
  return /STOP_CONTRACT_SATISFIED|CHECKPOINT_VALID|READY_TO_WRITE|VERIFIED_PATH|PASS/i.test(reason);
}

function isConflictRun(run: ResearchRunSummary) {
  return buildRunPrimaryTone(run) === "conflict";
}

function isRecoveryRun(run: ResearchRunSummary) {
  return Boolean(run.recovery_mode) || (run.counterfactual_summary?.counterfactual_branch_count ?? 0) > 0 || buildRunPrimaryTone(run) === "recovery";
}

function isStableRun(run: ResearchRunSummary) {
  return buildRunPrimaryTone(run) === "stable";
}

function isResumedRun(run: ResearchRunSummary) {
  return Boolean(run.resumed_from_research_run_id);
}


function extractRunSummarySnapshot(run: ResearchRunSummary | null): ResearchRunSummarySnapshot {
  return {
    status: run?.status ?? "",
    profileKey: run?.profile_key ?? "",
    activeBranchId: run?.active_branch_id ?? "",
    localVerifierStatus: run?.local_verifier_status ?? "",
    localVerifierReason: run?.local_verifier_reason ?? "",
    globalVerifierDecision: run?.global_verifier_decision ?? "",
    globalVerifierReason: run?.global_verifier_reason ?? "",
    finalLoopDecision: run?.final_loop_decision ?? "",
    finalLoopReason: run?.final_loop_reason ?? "",
    recoveryMode: run?.recovery_mode ?? "",
    ledgerRowCount: numberFromUnknown(run?.ledger_row_count),
    verifiedRowCount: numberFromUnknown(run?.verified_row_count),
    conflictedRowCount: numberFromUnknown(run?.conflicted_row_count),
    blockedRowCount: numberFromUnknown(run?.blocked_row_count),
    guardrailedRowCount: numberFromUnknown(run?.guardrailed_row_count),
    targetedBlockedRowCount: numberFromUnknown(run?.recovery_targeted_blocked_row_count),
    uncoveredBlockedRowCount: numberFromUnknown(run?.uncovered_blocked_row_count),
    requirementPartialBlockedRowCount: numberFromUnknown(run?.requirement_partial_blocked_row_count),
    checkpointCount: numberFromUnknown(run?.checkpoint_count),
    sourceScopeCount: numberFromUnknown(run?.source_scope_count),
    counterfactualBranchCount: run?.counterfactual_summary?.counterfactual_branch_count ?? 0,
    researchIntentAlignmentStatus: run?.research_intent_alignment_status ?? "",
    researchIntentAlignmentReason: run?.research_intent_alignment_reason ?? "",
    intentSatisfiedConstraintCount: numberFromUnknown(run?.intent_satisfied_constraint_count),
    intentConstraintCount: numberFromUnknown(run?.intent_constraint_count),
    intentSatisfiedRequirementCount: numberFromUnknown(run?.intent_satisfied_requirement_count),
    intentRequirementCount: numberFromUnknown(run?.intent_requirement_count),
    intentPendingRequirementCount: numberFromUnknown(run?.intent_pending_requirement_count),
    missingIntentRequirements: Array.isArray(run?.missing_intent_requirements) ? run?.missing_intent_requirements : [],
    recoveryTargets: readRecoveryTargets(run?.recovery_targets)
  };
}


type MessageSection = {
  title: string;
  content: string;
};

type MessageCard = {
  title: string;
  preview: string;
  content: string;
};

type MessagePresentation = {
  leadTitle: string | null;
  body: string;
  cards: MessageCard[];
};

function MessageBubble({ message }: { message: Message }) {
  if (message.role !== "assistant" || !message.answerMode) {
    return <div className="bubble-body">{message.content}</div>;
  }
  const presentation = buildAssistantPresentation(message);
  const isGenerating = message.answerStatus === "GENERATING";
  const isFailed = message.answerStatus === "FAILED" || message.answerStatus === "CANCELLED";
  const body = presentation.body
    || (isGenerating
      ? "正在检索当前工作台资料并生成回答…"
      : isFailed
        ? "本次回答未能完成。"
        : "此回答暂未返回正文。");
  return (
    <div aria-live={isGenerating ? "polite" : undefined}>
      {isGenerating ? (
        <div className="answer-run-status">
          <span className="answer-run-spinner" aria-hidden="true" />
          正在生成回答
        </div>
      ) : null}
      {isFailed ? (
        <div className="answer-run-error" role="alert">
          回答未完成：{message.answerError || "请稍后重试。"}
        </div>
      ) : null}
      {presentation.leadTitle ? <div className="bubble-label">{presentation.leadTitle}</div> : null}
      <div className="bubble-body">{body}</div>
      {presentation.cards.length > 0 ? (
        <div className="bubble-cards">
          {presentation.cards.map((card, index) => (
            <details className="message-card" key={`${card.title}-${index}`} open={card.title === "来源引用"}>
              <summary>
                <strong>{card.title}</strong>
                <span>{card.preview}</span>
              </summary>
              <div className="message-card-body">{card.content}</div>
            </details>
          ))}
        </div>
      ) : null}
    </div>
  );
}

function buildAssistantPresentation(message: Message): MessagePresentation {
  const sections = splitMarkdownSections(message.content);
  const [leadSection, ...restSections] = sections;
  const citationSectionTitles = new Set(["引用来源", "来源引用", "来源回链"]);
  const citationSection = restSections.find((section) => citationSectionTitles.has(section.title));
  const cards = message.answerMode === "note"
    ? buildNoteMessageCards(restSections, message.citations ?? [])
    : buildDefaultMessageCards(restSections, citationSectionTitles, citationSection, message.citations ?? []);
  if (!leadSection) {
    return {
      leadTitle: null,
      body: message.content.trim(),
      cards
    };
  }
  return {
    leadTitle: normalizeLeadTitle(leadSection.title),
    body: leadSection.content || leadSection.title,
    cards
  };
}

function buildDefaultMessageCards(
  restSections: MessageSection[],
  citationSectionTitles: Set<string>,
  citationSection: MessageSection | undefined,
  citations: string[]
) {
  const cards = restSections
    .filter((section) => section.content)
    .filter((section) => !(citations.length && citationSectionTitles.has(section.title)))
    .map((section) => buildMessageCard(section.title, section.content));
  if (citationSection?.content || citations.length) {
    cards.push(buildMessageCard(
      citationSection?.title || "来源引用",
      buildCitationCardContent(citationSection?.content ?? "", citations),
      citations.length
    ));
  }
  return cards;
}

function buildNoteMessageCards(restSections: MessageSection[], citations: string[]) {
  const byTitle = new Map(restSections.map((section) => [section.title.trim(), section]));
  const directLocation = byTitle.get("资料定位");
  const directReading = byTitle.get("深读窗口");
  const directEvidence = byTitle.get("摘录证据");
  const directCitation = byTitle.get("引用来源") ?? byTitle.get("来源引用") ?? byTitle.get("来源回链");
  if (directLocation || directReading || directEvidence) {
    const cards: MessageCard[] = [];
    if (directLocation?.content) {
      cards.push(buildMessageCard("资料定位", directLocation.content));
    }
    if (directReading?.content) {
      cards.push(buildMessageCard("深读窗口", directReading.content));
    }
    if (directEvidence?.content) {
      cards.push(buildMessageCard("摘录证据", directEvidence.content));
    }
    if (directCitation?.content || citations.length) {
      cards.push(buildMessageCard(
        "来源引用",
        buildCitationCardContent(directCitation?.content ?? "", citations),
        citations.length
      ));
    }
    return cards;
  }
  const cards: MessageCard[] = [];
  pushMergedNoteCard(cards, "资料定位", [
    byTitle.get("检索说明"),
    byTitle.get("Journal 信号"),
    byTitle.get("候选资料"),
    byTitle.get("关系扩展"),
    byTitle.get("验证批次")
  ]);
  pushMergedNoteCard(cards, "深读窗口", [
    byTitle.get("条目元数据"),
    byTitle.get("原文读取计划")
  ]);
  pushMergedNoteCard(cards, "摘录证据", [
    byTitle.get("关键观点"),
    byTitle.get("摘录证据")
  ]);
  const citationSection = byTitle.get("引用来源") ?? byTitle.get("来源引用") ?? byTitle.get("来源回链");
  if (citationSection?.content || citations.length) {
    cards.push(buildMessageCard(
      "来源引用",
      buildCitationCardContent(citationSection?.content ?? "", citations),
      citations.length
    ));
  }
  return cards;
}

function pushMergedNoteCard(cards: MessageCard[], title: string, sections: Array<MessageSection | undefined>) {
  const content = mergeCardSections(sections);
  if (!content) {
    return;
  }
  cards.push(buildMessageCard(title, content));
}

function mergeCardSections(sections: Array<MessageSection | undefined>) {
  const filled = sections.filter((section): section is MessageSection => Boolean(section?.content?.trim()));
  if (filled.length === 0) {
    return "";
  }
  if (filled.length === 1) {
    return filled[0].content.trim();
  }
  return filled
    .map((section) => `【${normalizeCardTitle(section.title)}】\n${section.content.trim()}`)
    .join("\n\n");
}

function splitMarkdownSections(content: string): MessageSection[] {
  const normalized = content.replaceAll("\r\n", "\n").trim();
  const matches = Array.from(normalized.matchAll(/^##\s+(.+)$/gm));
  if (matches.length === 0) {
    return normalized ? [{ title: "", content: normalized }] : [];
  }
  return matches.map((match, index) => {
    const title = (match[1] ?? "").trim();
    const start = (match.index ?? 0) + match[0].length;
    const end = index + 1 < matches.length ? (matches[index + 1].index ?? normalized.length) : normalized.length;
    return {
      title,
      content: normalized.slice(start, end).trim()
    };
  });
}

function normalizeLeadTitle(title: string) {
  const trimmed = title.trim();
  if (!trimmed || trimmed === "直接回答") {
    return null;
  }
  return trimmed;
}

function buildMessageCard(title: string, content: string, citationCount?: number): MessageCard {
  const normalizedTitle = normalizeCardTitle(title);
  return {
    title: normalizedTitle,
    preview: summarizeCard(normalizedTitle, content, citationCount),
    content
  };
}

function normalizeCardTitle(title: string) {
  switch (title.trim()) {
    case "引用来源":
    case "来源回链":
    case "来源引用":
      return "来源引用";
    case "原文读取计划":
      return "原文窗口";
    case "条目元数据":
      return "资料元信息";
    default:
      return title.trim();
  }
}

function buildCitationCardContent(sectionContent: string, citations: string[]) {
  const citationLines = citations.map((citation, index) => `${index + 1}. ${citation}`);
  if (!sectionContent.trim()) {
    return citationLines.join("\n");
  }
  if (countBulletLines(sectionContent) > 0) {
    return sectionContent.trim();
  }
  if (citationLines.length === 0) {
    return sectionContent.trim();
  }
  return `${sectionContent.trim()}\n\n${citationLines.join("\n")}`;
}

function summarizeCard(title: string, content: string, citationCount?: number) {
  const bulletCount = countBulletLines(content);
  if (title === "来源引用") {
    return `${citationCount ?? bulletCount ?? 0} 条来源`;
  }
  if (title === "资料定位") {
    const count = countSectionBullets(content, "候选资料") || bulletCount;
    return `${count} 份候选资料`;
  }
  if (title === "深读窗口") {
    const count = countSectionBullets(content, "原文窗口") || bulletCount;
    return `${count} 个阅读窗口`;
  }
  if (title === "候选资料") {
    return `${bulletCount} 份候选资料`;
  }
  if (title === "关系扩展") {
    return `${bulletCount} 条扩展关系`;
  }
  if (title === "验证批次") {
    return `${bulletCount} 项验证摘要`;
  }
  if (title === "资料元信息") {
    return `${bulletCount} 份资料元信息`;
  }
  if (title === "原文窗口") {
    return `${bulletCount} 个阅读窗口`;
  }
  if (title === "摘录证据" || title === "关键依据" || title === "关键观点") {
    return `${bulletCount} 条证据`;
  }
  if (title === "Journal 信号") {
    return `${bulletCount} 条历史整理信号`;
  }
  if (title === "检索说明") {
    return "检索方式与展示策略";
  }
  if (title === "关键页面关系" || title === "反向引用关系" || title === "页面关系") {
    return `${bulletCount} 条页面关系`;
  }
  const firstLine = content.split("\n").map((line) => line.trim()).find(Boolean);
  return firstLine ? trimText(firstLine, 32) : "点击展开";
}

function countBulletLines(content: string) {
  return content.split("\n").filter((line) => line.trim().startsWith("- ")).length;
}

function countSectionBullets(content: string, sectionTitle: string) {
  const escapedTitle = sectionTitle.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const pattern = new RegExp(`【${escapedTitle}】([\\s\\S]*?)(?=\\n\\n【|$)`);
  const match = content.match(pattern);
  return match ? countBulletLines(match[1]) : 0;
}

function trimText(value: string, limit: number) {
  return value.length <= limit ? value : `${value.slice(0, Math.max(0, limit - 1))}…`;
}

function parseStreamEventData(data: string) {
  try {
    const parsed = JSON.parse(data);
    const record = asRecord(parsed);
    return {
      message: String(record.message ?? data),
      payload: asRecord(record.payload),
      eventType: String(record.event_type ?? ""),
      createdAt: String(record.created_at ?? ""),
    };
  } catch {
    return {
      message: data,
      payload: {},
      eventType: "",
      createdAt: "",
    };
  }
}

function toStreamEvent(event: string, data: string, id = ""): StreamEvent {
  const parsed = parseStreamEventData(data);
  return {
    id: id || `${event}-${parsed.createdAt}-${data.length}`,
    event,
    data,
    message: parsed.message,
    payload: parsed.payload,
    eventType: parsed.eventType,
    createdAt: parsed.createdAt
  };
}

