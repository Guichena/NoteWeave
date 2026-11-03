package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 第七期：局部补查与扩展研究。
 *
 * <p>在已完成的研究上直接重开指定单元格、追加行/列，保留旧表格与旧报告，只让重开的格子
 * 重新进入 GAP/STALE，由既有 coordinator 重新下发。输入变更采用保守范围：把所有已核实格
 * 标记 STALE 并清空证据引用，强制重新资格检查。</p>
 */
@Service
public class ResearchIncrementalService {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_CELL_KEY = 160;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ResearchIncrementalService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Receipt reopen(String workspaceId, String runId, Request request) {
        if (request == null || (request.cellKeys().isEmpty()
                && request.addRows().isEmpty() && request.addColumns().isEmpty()
                && !request.inputChanged())) {
            throw new BusinessException("RESEARCH_INCREMENTAL_EMPTY",
                    "至少需要指定重开单元格、追加行列或输入变更", HttpStatus.BAD_REQUEST);
        }
        RunRow run = jdbcTemplate.query("""
                select status, agent_execution_mode from research_run
                where id = ? and workspace_id = ? for update
                """, rs -> rs.next() ? new RunRow(rs.getString(1), rs.getString(2)) : null,
                runId, workspaceId);
        if (run == null) {
            throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究运行不存在", HttpStatus.NOT_FOUND);
        }
        if (!"COMPLETED".equals(run.status()) && !"COMPLETED_VERIFIED".equals(run.status())) {
            throw new BusinessException("RESEARCH_INCREMENTAL_NOT_COMPLETED",
                    "只有已完成的研究可以局部补查", HttpStatus.CONFLICT);
        }
        if (!"INCREMENTAL_V1".equals(run.executionMode())) {
            throw new BusinessException("RESEARCH_INCREMENTAL_MODE_UNSUPPORTED",
                    "该研究不是增量执行模式", HttpStatus.CONFLICT);
        }
        Integer activeTasks = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task
                where research_run_id = ? and status in ('READY', 'SUBMITTED', 'CLAIMED', 'RUNNING')
                """, Integer.class, runId);
        if (activeTasks != null && activeTasks > 0) {
            throw new BusinessException("RESEARCH_INCREMENTAL_ACTIVE_TASKS",
                    "仍有未结束的研究任务", HttpStatus.CONFLICT);
        }

        int planRevision = maxInt("select coalesce(max(plan_revision), 0) from research_cell where research_run_id = ?", runId);
        int entitySetVersion = maxInt("select coalesce(max(entity_set_version), 0) from research_cell where research_run_id = ?", runId);
        int addedRows = 0;
        int addedColumns = 0;
        for (NewRow row : request.addRows()) {
            String rowKey = requireKey(row.rowKey(), "RESEARCH_INCREMENTAL_ROW_KEY_INVALID");
            if (exists("select count(*) from research_row where research_run_id = ? and row_key = ?", runId, rowKey)) {
                throw new BusinessException("RESEARCH_INCREMENTAL_DUPLICATE_ROW", "行已存在: " + rowKey, HttpStatus.CONFLICT);
            }
            String rowId = Ids.newId();
            jdbcTemplate.update("""
                    insert into research_row(id, research_run_id, row_key, source_title, row_status,
                        relation_type, support_level, verification_status)
                    values (?, ?, ?, ?, 'ACTIVE', 'PRIMARY', 'UNVERIFIED', 'PENDING')
                    """, rowId, runId, rowKey, row.title());
            for (String columnKey : jdbcTemplate.queryForList(
                    "select column_key from research_column where research_run_id = ?", String.class, runId)) {
                insertCell(runId, rowId, rowKey + ":" + columnKey, columnKey, planRevision, entitySetVersion);
            }
            addedRows++;
        }

        int nextPlanRevision = planRevision + 1;
        for (NewColumn column : request.addColumns()) {
            String columnKey = requireKey(column.columnKey(), "RESEARCH_INCREMENTAL_COLUMN_KEY_INVALID");
            if (exists("select count(*) from research_column where research_run_id = ? and column_key = ?", runId, columnKey)) {
                throw new BusinessException("RESEARCH_INCREMENTAL_DUPLICATE_COLUMN",
                        "列已存在: " + columnKey, HttpStatus.CONFLICT);
            }
            jdbcTemplate.update("""
                    insert into research_column(id, research_run_id, column_key, label, description,
                        answer_format, high_risk, required_provenance_level, plan_revision)
                    values (?, ?, ?, ?, ?, ?, 0, 'QUALIFIED_SOURCE', ?)
                    """, Ids.newId(), runId, columnKey, column.label(),
                    column.description() == null ? "" : column.description(),
                    column.answerFormat() == null || column.answerFormat().isBlank()
                            ? "SHORT_TEXT" : column.answerFormat(), nextPlanRevision);
            for (RowRef row : jdbcTemplate.query(
                    "select id, row_key from research_row where research_run_id = ?", (rs, n) ->
                            new RowRef(rs.getString(1), rs.getString(2)), runId)) {
                insertCell(runId, row.id(), row.key() + ":" + columnKey, columnKey,
                        nextPlanRevision, entitySetVersion);
            }
            addedColumns++;
        }

        int reopened = 0;
        for (String cellKey : request.cellKeys()) {
            List<String> cellIds = jdbcTemplate.queryForList("""
                    select id from research_cell where research_run_id = ? and cell_key = ?
                    """, String.class, runId, cellKey);
            if (cellIds.isEmpty()) {
                throw new BusinessException("RESEARCH_INCREMENTAL_CELL_NOT_FOUND",
                        "单元格不存在: " + cellKey, HttpStatus.NOT_FOUND);
            }
            jdbcTemplate.update("""
                    update research_cell
                    set cell_status = 'GAP', candidate_value = null, confidence_score = null,
                        confidence_score_ppm = null, evidence_refs_json = null,
                        last_verifier_decision = null, repair_count = 0,
                        cell_version = cell_version + 1, active_task_id = null,
                        updated_at = current_timestamp
                    where research_run_id = ? and cell_key = ?
                    """, runId, cellKey);
            jdbcTemplate.update("delete from research_cell_evidence where research_run_id = ? and research_cell_id = ?",
                    runId, cellIds.get(0));
            reopened++;
        }

        int stale = 0;
        if (request.inputChanged()) {
            stale = jdbcTemplate.update("""
                    update research_cell
                    set cell_status = 'STALE', evidence_refs_json = null, confidence_score = null,
                        confidence_score_ppm = null, last_verifier_decision = null,
                        cell_version = cell_version + 1, active_task_id = null,
                        updated_at = current_timestamp
                    where research_run_id = ? and cell_status = 'VERIFIED'
                    """, runId);
            jdbcTemplate.update("delete from research_cell_evidence where research_run_id = ?", runId);
        }

        jdbcTemplate.update("""
                update research_run set status = 'RUNNING', updated_at = current_timestamp
                where id = ? and status in ('COMPLETED', 'COMPLETED_VERIFIED')
                """, runId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reopened_cells", reopened);
        payload.put("added_rows", addedRows);
        payload.put("added_columns", addedColumns);
        payload.put("stale_cells", stale);
        payload.put("input_changed", request.inputChanged());
        payload.put("reason", request.reason() == null ? "" : request.reason());
        payload.put("report_version_relation", "NEW_REPORT_APPENDED_TO_SAME_RUN");
        jdbcTemplate.update("""
                insert into research_trace(id, research_run_id, trace_type, trace_message, payload_json)
                values (?, ?, 'INCREMENTAL_REOPEN', '增量研究重开指定格', ?)
                """, Ids.newId(), runId, Json.write(objectMapper, payload));
        int totalCells = jdbcTemplate.queryForObject(
                "select count(*) from research_cell where research_run_id = ?", Integer.class, runId);
        return new Receipt(runId, reopened, addedRows, addedColumns, stale, totalCells,
                "NEW_REPORT_APPENDED_TO_SAME_RUN");
    }

    private void insertCell(String runId, String rowId, String cellKey, String columnKey,
                            int planRevision, int entitySetVersion) {
        if (cellKey.length() > MAX_CELL_KEY) {
            throw new BusinessException("RESEARCH_INCREMENTAL_CELL_KEY_TOO_LONG",
                    "单元格键过长: " + cellKey, HttpStatus.BAD_REQUEST);
        }
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key,
                    cell_status, cell_version, plan_revision, entity_set_version)
                values (?, ?, ?, ?, ?, 'GAP', 1, ?, ?)
                """, Ids.newId(), runId, rowId, cellKey, columnKey, planRevision, entitySetVersion);
    }

    private String requireKey(String value, String code) {
        String key = value == null ? "" : value.strip();
        if (!KEY.matcher(key).matches()) {
            throw new BusinessException(code, "键只能包含字母、数字、点、下划线或连字符", HttpStatus.BAD_REQUEST);
        }
        return key;
    }

    private int maxInt(String sql, String runId) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, runId);
        return value == null ? 0 : value;
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private record RunRow(String status, String executionMode) { }

    private record RowRef(String id, String key) { }

    public record NewRow(String rowKey, String title) { }

    public record NewColumn(String columnKey, String label, String description, String answerFormat) { }

    public record Request(List<String> cellKeys, List<NewRow> addRows, List<NewColumn> addColumns,
                          boolean inputChanged, String reason) {
        public Request {
            cellKeys = cellKeys == null ? List.of() : List.copyOf(cellKeys);
            addRows = addRows == null ? List.of() : List.copyOf(addRows);
            addColumns = addColumns == null ? List.of() : List.copyOf(addColumns);
        }
    }

    public record Receipt(String researchRunId, int reopenedCells, int addedRows, int addedColumns,
                          int staleCells, int totalCells, String reportVersionRelation) { }
}
