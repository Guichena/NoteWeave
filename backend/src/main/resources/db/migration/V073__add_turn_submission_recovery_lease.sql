alter table turn_submission add column recovery_lease_owner varchar(36) null;
alter table turn_submission add column recovery_lease_until timestamp null;

create index idx_turn_submission_recovery_lease
    on turn_submission(status, recovery_lease_until);
