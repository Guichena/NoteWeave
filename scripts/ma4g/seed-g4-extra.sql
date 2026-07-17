-- Add fifteen more two-target entities. Together with seed-g1 this produces
-- sixteen independent tasks, making multi-partition/two-member execution
-- observable without changing the production outbox message key.
drop procedure if exists ma4g_seed_g4_entities;
delimiter $$
create procedure ma4g_seed_g4_entities()
begin
    declare entity_no int default 2;
    declare row_id varchar(36);
    while entity_no <= 16 do
        set row_id = uuid();
        insert into research_row(id, research_run_id, row_key, row_status)
        values (row_id, '00000000-0000-0000-0000-00000000a002',
                concat('entity-', entity_no), 'CANDIDATE_READY');
        insert into research_cell(
            id, research_run_id, research_row_id, cell_key, column_key, candidate_value,
            cell_status, repair_count, cell_version, plan_revision, entity_set_version
        ) values
        (uuid(), '00000000-0000-0000-0000-00000000a002', row_id,
         concat('entity-', entity_no, ':limitation'), 'limitation', '',
         'CANDIDATE_READY', 0, 0, 1, 1),
        (uuid(), '00000000-0000-0000-0000-00000000a002', row_id,
         concat('entity-', entity_no, ':method'), 'method', '',
         'CANDIDATE_READY', 0, 0, 1, 1);
        set entity_no = entity_no + 1;
    end while;
end$$
delimiter ;
call ma4g_seed_g4_entities();
drop procedure ma4g_seed_g4_entities;

