-- DR-203: one auditable row per Research Plan Replan.
-- The architecture document ("Plan 与 Replan 的可验证合同") requires every Replan to be
-- justified by an observable deviation and to persist only the decision summary, never the
-- model's reasoning chain. This table therefore stores: the deviation type, the observable
-- facts, the affected cells, the budget delta, the Evidence/Receipt references and the
-- old/new plan digests.
-- Decision text columns are bounded varchars on purpose: a Replan audit row can never become an
-- unbounded Chain-of-Thought dump. The three payload columns are longtext, matching the existing
-- JSON-payload convention (V039 target_cells_json/budget_json, V014/V085 evidence_refs_json);
-- their serialized size is bounded by ResearchAgentReplanAuditService before insert.
-- The unique key on (research_run_id, plan_revision_to) is the idempotency key: a replayed
-- Replan for the same resulting revision cannot create a second row, and the same index
-- serves the by-run / by-run-revision audit lookups.
-- Expand-only: no existing column or row is touched.
create table research_agent_replan_audit (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    plan_revision_from int not null,
    plan_revision_to int not null,
    old_plan_digest varchar(71) not null,
    new_plan_digest varchar(71) not null,
    deviation_type varchar(48) not null,
    observed_facts varchar(512) not null,
    affected_cells_json longtext not null,
    budget_delta_json longtext not null,
    evidence_refs_json longtext null,
    selected_repair varchar(512) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_agent_replan_audit_run
        foreign key (research_run_id) references research_run(id),
    constraint ck_research_agent_replan_audit_digest
        check (old_plan_digest <> new_plan_digest)
);

create unique index uq_research_agent_replan_audit_revision
    on research_agent_replan_audit(research_run_id, plan_revision_to);
