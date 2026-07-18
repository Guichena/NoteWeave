alter table turn_submission add column preparation_json longtext null;
alter table turn_submission add column preparation_attempt int not null default 0;
alter table turn_submission add column error_code varchar(80) null;
alter table turn_submission add column error_message varchar(1000) null;

