insert into workspace(id, owner_id, name, status)
values ('00000000-0000-0000-0000-00000000f001', 'local-user', 'MA4F isolated canary', 'ACTIVE');

insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
values ('00000000-0000-0000-0000-00000000f003', '00000000-0000-0000-0000-00000000f001',
        'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', '00000000-0000-0000-0000-00000000f002');

insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type)
values ('00000000-0000-0000-0000-00000000f004', '00000000-0000-0000-0000-00000000f001',
        'ma4f/source.txt', repeat('a', 64), 25, 'text/plain');

insert into source(id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status)
values ('00000000-0000-0000-0000-00000000f005', '00000000-0000-0000-0000-00000000f001',
        '00000000-0000-0000-0000-00000000f004', 'MA4F trusted source', 'TEXT', 'READY', 'READY', 'READY');

insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status)
values ('00000000-0000-0000-0000-00000000f006', '00000000-0000-0000-0000-00000000f005',
        '00000000-0000-0000-0000-00000000f004', 1, 'ma4f/source.txt', repeat('b', 64), 'READY', 'READY');

insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no, content, token_estimate)
values ('00000000-0000-0000-0000-00000000f007', '00000000-0000-0000-0000-00000000f001',
        '00000000-0000-0000-0000-00000000f005', '00000000-0000-0000-0000-00000000f006',
        1, 'The method is documented.', 5);

insert into source_window(id, source_chunk_id, window_no, content)
values ('00000000-0000-0000-0000-00000000f008', '00000000-0000-0000-0000-00000000f007',
        1, 'The method is documented.');

insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json, status, agent_execution_mode)
values ('00000000-0000-0000-0000-00000000f002', '00000000-0000-0000-0000-00000000f001',
        '00000000-0000-0000-0000-00000000f003', 'How does the method work?', 'DEFAULT',
        '["00000000-0000-0000-0000-00000000f005"]', 'RUNNING', 'INCREMENTAL_V1');

insert into research_row(id, research_run_id, row_key, row_status)
values ('00000000-0000-0000-0000-00000000f009', '00000000-0000-0000-0000-00000000f002',
        'entity-1', 'CANDIDATE_READY');

insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key, candidate_value,
                          cell_status, repair_count, cell_version, plan_revision, entity_set_version)
values ('00000000-0000-0000-0000-00000000f00a', '00000000-0000-0000-0000-00000000f002',
        '00000000-0000-0000-0000-00000000f009', 'entity-1:method', 'method', '',
        'CANDIDATE_READY', 0, 0, 1, 1);
