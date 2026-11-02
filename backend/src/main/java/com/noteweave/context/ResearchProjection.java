package com.noteweave.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 研究投影：把研究运行的有界视图固定成稳定结构，供第三期的研究输入与第四期的追问共用。
 *
 * <p>分级是硬约束，不是渲染建议：P0 只给身份、标题、状态与简短结论；P1 加执行摘要与需求覆盖；
 * P2 才给单元格结论与已通过资格检查的证据。规划过程、抓取窗口、失败候选、修复历史和完整报告
 * 不进入日常对话视图，完整报告留给研究详情。</p>
 *
 * <p>过渡期可以从既有研究记录生成有界句柄，但不得伪造完整结论卡，也不得提前宣称
 * 完整工作集已经完成。</p>
 */
public record ResearchProjection(
        String schemaVersion,
        String researchRunId,
        String workspaceId,
        String conversationId,
        String messageId,
        String status,
        String level,
        String title,
        String executiveSummary,
        List<Requirement> requirements,
        List<Cell> cells,
        List<String> seedSourceIds,
        List<String> evidenceIds,
        int pendingCellCount,
        String projectionVersion,
        int cutoffSeq,
        boolean truncated,
        List<String> degradationReasons
) {

    /** 投影结构版本。 */
    public static final String SCHEMA_VERSION = "research-projection-v1";
    /** 身份与状态层。 */
    public static final String LEVEL_P0 = "P0";
    /** 执行摘要与需求覆盖层。 */
    public static final String LEVEL_P1 = "P1";
    /** 单元格结论与证据层。 */
    public static final String LEVEL_P2 = "P2";
    /** 等级裁剪：P0 不带摘要、需求与单元格。 */
    public static final String REASON_LEVEL_RESTRICTS_P0_FIELDS = "LEVEL_RESTRICTS_P0_FIELDS";
    /** 等级裁剪：P1 不带单元格结论。 */
    public static final String REASON_LEVEL_RESTRICTS_CELLS = "LEVEL_RESTRICTS_CELLS";
    /** 上游没有执行摘要，不是等级裁剪。 */
    public static final String REASON_UPSTREAM_SUMMARY_EMPTY = "UPSTREAM_SUMMARY_EMPTY";
    /** 上游没有需求覆盖，不是等级裁剪。 */
    public static final String REASON_UPSTREAM_REQUIREMENTS_EMPTY = "UPSTREAM_REQUIREMENTS_EMPTY";
    /** 上游没有单元格结论，不是等级裁剪。 */
    public static final String REASON_UPSTREAM_CELLS_EMPTY = "UPSTREAM_CELLS_EMPTY";

    private static final Set<String> STATUSES = Set.of(
            "PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED");
    private static final Set<String> COVERAGE = Set.of(
            "COVERED", "PARTIAL", "UNVERIFIED", "MISSING");
    private static final Set<String> CELL_STATUSES = Set.of(
            "PENDING", "VERIFIED", "PARTIAL", "UNVERIFIED", "FAILED");

    public ResearchProjection {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        cells = cells == null ? List.of() : List.copyOf(cells);
        seedSourceIds = seedSourceIds == null ? List.of() : List.copyOf(seedSourceIds);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
        conversationId = conversationId == null ? "" : conversationId;
        messageId = messageId == null ? "" : messageId;
        executiveSummary = executiveSummary == null ? "" : executiveSummary;
        if (!SCHEMA_VERSION.equals(schemaVersion) || blank(researchRunId) || blank(workspaceId)
                || blank(status) || !STATUSES.contains(status)
                || !Set.of(LEVEL_P0, LEVEL_P1, LEVEL_P2).contains(level)
                || blank(projectionVersion) || cutoffSeq < 0 || pendingCellCount < 0) {
            throw new IllegalArgumentException("research projection envelope is invalid");
        }
        if (LEVEL_P0.equals(level) && (!requirements.isEmpty() || !cells.isEmpty()
                || !executiveSummary.isBlank())) {
            throw new IllegalArgumentException("P0 projection cannot carry summaries or cells");
        }
        if (LEVEL_P1.equals(level) && !cells.isEmpty()) {
            throw new IllegalArgumentException("P1 projection cannot carry cell conclusions");
        }
        Set<String> requirementCodes = new java.util.HashSet<>();
        for (Requirement requirement : requirements) {
            if (!COVERAGE.contains(requirement.coverage())
                    || !requirementCodes.add(requirement.code())) {
                throw new IllegalArgumentException("research requirement is invalid or duplicated");
            }
        }
        Set<String> cellIds = new java.util.HashSet<>();
        for (Cell cell : cells) {
            if (!CELL_STATUSES.contains(cell.status()) || !cellIds.add(cell.cellId())) {
                throw new IllegalArgumentException("research cell is invalid or duplicated");
            }
        }
        for (String reason : degradationReasons) {
            if (blank(reason)) {
                throw new IllegalArgumentException("research projection degradation must be explicit");
            }
        }
    }

    /**
     * 只保留指定级别的字段，其余字段按分级裁掉。
     *
     * <p>降级原因要能区分两种情况：等级不允许带这些字段，和上游本来就没有这些数据。
     * 等级裁剪记 LEVEL_RESTRICTS_*，上游缺失记 UPSTREAM_*_EMPTY，不把等级裁剪写成数据缺失。</p>
     */
    public ResearchProjection asLevel(String targetLevel) {
        boolean full = LEVEL_P2.equals(level);
        List<String> reasons = new ArrayList<>(degradationReasons);
        if (full) {
            // 只有完整投影才知道上游到底有没有这些数据；已经裁剪过的投影无法反推
            if (executiveSummary.isBlank()) {
                reasons.add(REASON_UPSTREAM_SUMMARY_EMPTY);
            }
            if (requirements.isEmpty()) {
                reasons.add(REASON_UPSTREAM_REQUIREMENTS_EMPTY);
            }
            if (cells.isEmpty()) {
                reasons.add(REASON_UPSTREAM_CELLS_EMPTY);
            }
        }
        if (LEVEL_P0.equals(targetLevel)) {
            if (full && (!executiveSummary.isBlank() || !requirements.isEmpty() || !cells.isEmpty())) {
                reasons.add(REASON_LEVEL_RESTRICTS_P0_FIELDS);
            }
            return new ResearchProjection(schemaVersion, researchRunId, workspaceId, conversationId,
                    messageId, status, LEVEL_P0, title, "", List.of(), List.of(), List.of(), List.of(),
                    pendingCellCount, projectionVersion, cutoffSeq, truncated, reasons);
        }
        if (LEVEL_P1.equals(targetLevel)) {
            if (full && !cells.isEmpty()) {
                reasons.add(REASON_LEVEL_RESTRICTS_CELLS);
            }
            return new ResearchProjection(schemaVersion, researchRunId, workspaceId, conversationId,
                    messageId, status, LEVEL_P1, title, executiveSummary, requirements, List.of(),
                    seedSourceIds, List.of(), pendingCellCount, projectionVersion, cutoffSeq, truncated,
                    reasons);
        }
        if (full) {
            return this;
        }
        return new ResearchProjection(schemaVersion, researchRunId, workspaceId, conversationId,
                messageId, status, level, title, executiveSummary, requirements, cells, seedSourceIds,
                evidenceIds, pendingCellCount, projectionVersion, cutoffSeq, truncated, reasons);
    }

    /** 是否有尚未完成的需求。 */
    public boolean hasUnfinishedRequirements() {
        return requirements.stream().anyMatch(requirement -> !"COVERED".equals(requirement.coverage()));
    }

    /**
     * 需求覆盖条目。
     *
     * @param code     需求编号
     * @param label    需求描述
     * @param coverage 覆盖状态
     * @param note     补充说明，可为空串
     */
    public record Requirement(String code, String label, String coverage, String note) {
        public Requirement {
            note = note == null ? "" : note;
            if (blank(code) || blank(label) || !COVERAGE.contains(coverage)) {
                throw new IllegalArgumentException("research requirement is invalid");
            }
        }
    }

    /**
     * 单元格结论。
     *
     * @param cellId        单元格身份
     * @param objectName    对象名
     * @param dimensionName 维度名
     * @param status        核实状态
     * @param conclusion    结论，未核实时为占位说明
     * @param evidenceIds   通过资格检查的证据身份
     */
    public record Cell(String cellId, String objectName, String dimensionName, String status,
                       String conclusion, List<String> evidenceIds) {
        public Cell {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            conclusion = conclusion == null ? "" : conclusion;
            if (blank(cellId) || blank(objectName) || blank(dimensionName)
                    || !CELL_STATUSES.contains(status)) {
                throw new IllegalArgumentException("research cell is invalid");
            }
            if (!"VERIFIED".equals(status) && !evidenceIds.isEmpty()) {
                throw new IllegalArgumentException("only verified cells may carry verified evidence");
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
