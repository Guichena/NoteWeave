-- V020: research_cell 4-way verdict 字段 (P0-5 Cell-level Evidence Verification)
-- 用于持久化 CellVerifier 在 research worker 中产生的 SUPPORTS / PARTIALLY_SUPPORTS / CONTRADICTS / NOT_ENOUGH_INFO 四态判定。

ALTER TABLE research_cell ADD COLUMN verdict VARCHAR(32) NULL;
ALTER TABLE research_cell ADD COLUMN verdict_reason VARCHAR(500) NULL;
ALTER TABLE research_cell ADD COLUMN verdict_confidence DECIMAL(5, 4) NULL;
ALTER TABLE research_cell ADD COLUMN verdict_round INT NULL;
ALTER TABLE research_cell ADD COLUMN verdict_used_llm BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_research_cell_verdict ON research_cell (verdict, verdict_round);

-- research_state_ledger 视图兼容 (实际 state ledger 由 worker result_payload 写入,本迁移仅做列添加)
-- Java 端 readStateLedgerResponse / readCheckpointStateLedgerSummaryResponse
-- 需要同步增加 verdict 字段读取(后续 PR 完成)。
