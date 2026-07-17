insert into workspace(id, owner_id, name, status)
values ('00000000-0000-0000-0000-00000000c001', 'local-user', 'MA4G V044 legacy migration seed', 'ACTIVE');

insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
values ('00000000-0000-0000-0000-00000000c003', '00000000-0000-0000-0000-00000000c001',
        'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', '00000000-0000-0000-0000-00000000c002');

insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json, status, agent_execution_mode)
values ('00000000-0000-0000-0000-00000000c002', '00000000-0000-0000-0000-00000000c001',
        '00000000-0000-0000-0000-00000000c003', 'Legacy V044 row', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1');

insert into research_row(id, research_run_id, row_key, row_status)
values ('00000000-0000-0000-0000-00000000c009', '00000000-0000-0000-0000-00000000c002',
        'legacy-entity', 'CANDIDATE_READY');

insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key, candidate_value,
                          cell_status, confidence_score, repair_count, cell_version, plan_revision, entity_set_version)
values ('00000000-0000-0000-0000-00000000c00a', '00000000-0000-0000-0000-00000000c002',
        '00000000-0000-0000-0000-00000000c009', 'legacy-entity:method', 'method', 'legacy value',
        'VERIFIED', 0.5432, 0, 1, 1, 1);

insert into source_evidence(id, research_run_id, evidence_key, window_id, source_id, source_title,
                            quote_text, claim_text, relation_type, support_score, conflict_score, snapshot_status)
values ('00000000-0000-0000-0000-00000000c010', '00000000-0000-0000-0000-00000000c002',
        'legacy-evidence', 'legacy-window', 'legacy-source', 'Legacy source', 'legacy quote', 'legacy value',
        'SUPPORTS', 0.8765, 0.0123, 'WORKSPACE');

insert into research_agent_task(id, research_run_id, task_key, idempotency_key, wave_no, role,
                                entity_id, branch_id, plan_revision, entity_set_version,
                                target_cells_json, budget_json, status)
values ('00000000-0000-0000-0000-00000000c012', '00000000-0000-0000-0000-00000000c002',
        'legacy-deep-cell', 'legacy-task-idempotency', 1, 'DEEP_CELL', 'legacy-entity', 'branch-main',
        1, 1, '["legacy-entity:method"]', '{"llm_calls":2}', 'PENDING');

insert into research_agent_candidate(id, research_run_id, task_id, execution_id, idempotency_key,
                                     cell_key, base_cell_version, plan_revision, entity_set_version,
                                     lease_epoch, fencing_token, candidate_value, evidence_ids_json, confidence_score)
values ('00000000-0000-0000-0000-00000000c011', '00000000-0000-0000-0000-00000000c002',
        '00000000-0000-0000-0000-00000000c012', 'legacy-execution', 'legacy-candidate', 'legacy-entity:method', 0, 1, 1,
        1, 1, 'legacy value', '["legacy-evidence"]', 0.4321);

insert into research_cell_merge(id, research_run_id, candidate_id, merge_key, cell_key,
                                expected_cell_version, result_cell_version, verdict, decision,
                                reason_code, accepted_evidence_ids_json)
values ('00000000-0000-0000-0000-00000000c013', '00000000-0000-0000-0000-00000000c002',
        '00000000-0000-0000-0000-00000000c011', 'legacy-merge', 'legacy-entity:method',
        0, 1, 'SUPPORTS', 'ACCEPTED', 'LEGACY_VERIFIED', '["legacy-evidence"]');

insert into research_cell_evidence(id, research_run_id, research_cell_id, source_evidence_id, evidence_key)
values ('00000000-0000-0000-0000-00000000c014', '00000000-0000-0000-0000-00000000c002',
        '00000000-0000-0000-0000-00000000c00a', '00000000-0000-0000-0000-00000000c010',
        'legacy-evidence');

insert into research_budget_reservation(id, research_run_id, research_agent_task_id, idempotency_key,
                                        reserved_json, consumed_json, released_json, state)
values ('00000000-0000-0000-0000-00000000c015', '00000000-0000-0000-0000-00000000c002',
        '00000000-0000-0000-0000-00000000c012', 'legacy-budget',
        '{"llm_calls":2}', '{"llm_calls":0}', '{"llm_calls":0}', 'RESERVED');
