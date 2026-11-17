package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 需求查询先校验工作台权限，再汇总服务端权威覆盖和运行指标。 */
@Service
class ResearchRequirementQueryService {
    private final ResearchRunService runs;
    private final ResearchRequirementCoverageService coverage;
    private final ResearchReportCoverageService reports;
    private final ResearchRuntimeMetricsService metrics;

    ResearchRequirementQueryService(ResearchRunService runs, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.runs = runs;
        this.coverage = new ResearchRequirementCoverageService(jdbc, mapper);
        this.reports = new ResearchReportCoverageService(jdbc, mapper);
        this.metrics = new ResearchRuntimeMetricsService(jdbc, mapper);
    }

    @Transactional(readOnly = true)
    public Map<String,Object> get(String workspaceId, String researchRunId) {
        runs.getRunDetail(workspaceId, researchRunId);
        Map<String,Object> progress = new LinkedHashMap<>(coverage.readModel(researchRunId));
        if (coverage.improvedCompletion(researchRunId)) {
            var report = reports.publicView(researchRunId);
            if (!report.isEmpty()) progress.put("report_coverage", report);
        }
        if (coverage.fullCompletion(researchRunId)) progress.put("runtime_metrics", metrics.view(researchRunId));
        return Map.copyOf(progress);
    }
}
