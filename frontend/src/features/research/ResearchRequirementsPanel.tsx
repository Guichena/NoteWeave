import { researchApi } from "./api";
import { useRunResource } from "./useRunResource";

type Props = { workspaceId: string; researchRunId: string; refreshKey: string };

/** 展示服务器计算的需求覆盖和已核验事实，不从单元格数量推断完成率。 */
export function ResearchRequirementsPanel({ workspaceId, researchRunId, refreshKey }: Props) {
  const { data, unavailable, error } = useRunResource(
    () => researchApi.getRequirements(workspaceId, researchRunId),
    `${researchRunId}:${refreshKey}`
  );
  if (unavailable || (!data && !error)) return null;
  if (!data) return <div className="task-card"><small role="alert">{error}</small></div>;
  if (!data.enabled) return null;
  const scopes = [...new Set(data.requirements.map((item) => item.cell_key))];
  return (
    <section className="task-card" aria-label="研究需求与累计事实">
      <div className="research-plan-header">
        <strong>研究需求</strong>
        <small>已覆盖 {data.satisfied_count ?? 0}/{data.required_count ?? data.requirements.length} · 已保留事实 {data.distinct_fact_count ?? data.facts.length} 条</small>
      </div>
      <p>有合格证据支持的事实会在后续研究中保留；需求尚未覆盖时继续补查。</p>
      {data.requirement_answer_policy_version === 1 && <p>
        上方进度按必答项计算；补充项已覆盖 {data.optional_satisfied_count ?? 0}/{data.optional_count ?? 0}，分别保留结果。
      </p>}
      {data.shadow_mode && <p role="status">当前仅记录需求覆盖，研究终态沿用原有判定。</p>}
      {data.runtime_metrics && <details>
        <summary>运行指标 · 未解决冲突 {data.runtime_metrics.unresolved_conflicts}</summary>
        <p>停止原因：{data.runtime_metrics.stop_reason || "尚未停止"} · 每项新增满足需求的模型调用：
          {data.runtime_metrics.llm_calls_per_new_requirement?.toFixed(2) ?? "暂无可计算增益"}</p>
        {data.runtime_metrics.unsubmitted_synthesis_authorizations > 0 && <p>
          未完成成文任务已授权 {data.runtime_metrics.unsubmitted_synthesis_authorizations} 次模型尝试，其消耗继续计入预算。
        </p>}
        {data.runtime_metrics.report_feedback.map((item) => <p key={item.id}>
          报告事实缺项：{item.missing_detail} · {{ OPEN: "待补查", TASKIZED: "补查中", RESOLVED: "已补全", EXHAUSTED: "本轮未补全",
            BUDGET_EXHAUSTED: "预算不足", SCOPE_UNAVAILABLE: "研究范围不可用" }[item.status] ?? item.status}
        </p>)}
        <ul>{data.runtime_metrics.rounds.map((round) => <li key={round.completion_id}>
          {round.role} · 查询 {round.query_count} · 阅读窗口 {round.read_page_count} · 模型结算次数 {round.llm_calls}
          · 已知实际模型请求 {round.provider_call_count ?? "未知"}
          · token {round.input_tokens === null || round.output_tokens === null ? "未知" : `${round.input_tokens}/${round.output_tokens}`}
          · 提取事实 {round.extracted_fact_count} · 通过结构核验的事实支撑 {round.qualified_support_count}
          · 重复文档 {round.duplicate_document_count}/{round.document_count}
          · 已解决冲突 {round.resolved_conflict_count}
          · 耗时 {round.duration_ms === null ? "未知" : `${(round.duration_ms / 1000).toFixed(1)} 秒`}
          {Object.keys(round.rejection_counts).length > 0 && <small> · 过滤原因：{Object.entries(round.rejection_counts).map(([reason, count]) => `${reason} ${count}`).join("；")}</small>}
        </li>)}</ul>
      </details>}
      {data.explicit_count !== undefined && <p>
        用户明确要求 {data.explicit_satisfied_count ?? 0}/{data.explicit_count} · 补充研究项 {data.derived_satisfied_count ?? 0}/{data.derived_count ?? 0}
      </p>}
      {data.explicit_definitions_complete === false && <p role="status">用户明确要求尚未完成解析，当前不能判定研究已完整完成。</p>}
      {data.report_coverage && <p role="status">
        报告已完整回答 {data.report_coverage.answered_count}/{data.report_coverage.required_count} 项需求
        {!data.report_coverage.complete && "，其余需求尚未完整回答或尚未完成报告核验。"}
      </p>}
      {scopes.map((scope) => {
        const requirements = data.requirements.filter((item) => item.cell_key === scope);
        const scopedFacts = data.facts.filter((item) => item.cell_key === scope);
        const facts = [...new Set(scopedFacts.map((item) => item.equivalence_group_id ?? item.fact_id))].map((groupId) => {
          const group = scopedFacts.filter((item) => (item.equivalence_group_id ?? item.fact_id) === groupId);
          return { ...group[0], evidence_quotes: [...new Map(group.flatMap((item) => item.evidence_quotes)
            .map((quote) => [quote.evidence_key, quote])).values()], source_scopes: group.flatMap((item) => item.source_scopes ?? []) };
        });
        return (
          <details key={scope} className="task-card">
            <summary>{requirements[0].entity_label} · 已覆盖 {requirements.filter((item) => item.status === "SATISFIED").length}/{requirements.length}</summary>
            <ul>{requirements.map((item) => <li key={item.requirement_id}>
              <strong>{item.status === "SATISFIED" ? "已覆盖" : item.status === "PARTIAL" ? "部分覆盖" : "待补查"}</strong>：{item.text}
              <small> · {item.origin === "EXPLICIT" ? "用户明确要求" : "补充研究项"}
                {item.mandatory === false && " · 补充项"}
                {item.completion_mode !== "SINGLE" && ` · 已核验 ${item.actual_count}/${item.minimum_count} ${["DISTINCT_ENTITIES", "COMPARISON"].includes(item.completion_mode) ? "个研究对象" : "项不同事实"}`}
              </small>
              {item.question_quote && <div><small>原始要求：{item.question_quote}</small></div>}
              {data.report_coverage?.items.filter((answer) => answer.requirement_id === item.requirement_id && answer.reason_code
                && answer.status !== "ANSWERED").map((answer) => <div key={answer.requirement_id}><small>
                  报告核验：{{ BUDGET_EXHAUSTED: "核验预算不足", MODEL_UNAVAILABLE: "核验模型不可用",
                    INVALID_MODEL_OUTPUT: "核验结果格式不完整", PROVIDER_TIMEOUT: "模型请求超时",
                    PROVIDER_CONNECTION_ERROR: "模型连接失败", PROVIDER_ERROR: "模型请求失败",
                    MODEL_UNCERTAIN: "尚无法确认", ANSWER_INCOMPLETE: "答案尚不完整" }[answer.reason_code ?? ""] ?? "尚未完成"}
                  {answer.missing_detail && `；缺项：${answer.missing_detail}`}
                </small></div>)}
            </li>)}</ul>
            {facts.map((fact) => <div key={fact.fact_id} className="link-card">
              <span>{fact.claim_text}</span>
              {fact.source_scopes?.some((item) => item.policy?.required_versions?.length) && <div><small>
                版本范围：{[...new Set(fact.source_scopes.flatMap((item) => item.policy?.required_versions ?? []))].join("、")}
              </small></div>}
              {fact.source_scopes?.some((item) => item.evidence?.some((source) => source.freshness_status === "UNKNOWN")) &&
                <div><small>资料更新时间尚未确认</small></div>}
              {fact.evidence_quotes.map((quote) => <details key={quote.evidence_key}>
                <summary>{/^https?:\/\//i.test(quote.source_url)
                  ? <a href={quote.source_url} target="_blank" rel="noopener noreferrer">{quote.source_title}</a>
                  : quote.source_title}</summary>
                <blockquote>{quote.quote}</blockquote>
              </details>)}
            </div>)}
          </details>
        );
      })}
    </section>
  );
}
