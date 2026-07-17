drop trigger if exists ma4g_sleep_before_second_cell_cas;
delimiter $$
create trigger ma4g_sleep_before_second_cell_cas
before update on research_cell
for each row
begin
    declare v_lock_owner int default 0;
    if old.research_run_id = '00000000-0000-0000-0000-00000000a002'
       and old.cell_key = 'entity-1:method'
       and new.cell_version = old.cell_version + 1 then
        select get_lock('ma4g_g2_second_cell_cas', 0) into v_lock_owner;
        if v_lock_owner <> 1 then
            signal sqlstate '45000' set message_text = 'MA4G G2 fault barrier lock was unavailable';
        end if;
        do sleep(60);
        do release_lock('ma4g_g2_second_cell_cas');
    end if;
end$$
delimiter ;
