package com.noteweave.retrieval;

import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.RetrievalPlan;
import java.util.Map;

/**
 * Immutable, versioned QA retrieval strategy tuple.
 *
 * <p>New QA runs use the single V2 tuple. Online retrieval resolves that tuple from the persisted
 * plan rather than reading mutable workspace settings.</p>
 */
public enum QaRetrievalStrategyProfile {
    V2(
            "qa-weknora-hybrid-v1",
            "qa-weknora-hybrid-v1",
            QaEvidenceRelevancePolicy.POLICY_VERSION_V2,
            QaEvidenceSelectionPolicy.POLICY_VERSION,
            QaEvidenceSelectionPolicy.DEFAULT_BUNDLE_EVIDENCE_LIMIT,
            QaEvidenceSelectionPolicy.DEFAULT_BUNDLE_CHARACTER_LIMIT,
            0,
            QaRetrievalStrategyProfile.CANDIDATE_LIMIT,
            true
    ),
    /** 新建问答使用 V3；V2 只用于解析和重放历史计划。 */
    V3(
            "qa-hybrid-mmr-token-v3",
            "qa-weknora-hybrid-v1",
            QaEvidenceRelevancePolicy.POLICY_VERSION_V2,
            QaEvidenceSelectionPolicy.POLICY_VERSION_V3,
            QaEvidenceSelectionPolicy.V3_MAX_EVIDENCE,
            QaEvidenceSelectionPolicy.V3_BUNDLE_CHARACTER_CEILING,
            QaEvidenceSelectionPolicy.V3_EVIDENCE_TOKEN_BUDGET,
            QaEvidenceSelectionPolicy.V3_MAX_EVIDENCE,
            true
    );

    public static final QaRetrievalStrategyProfile CURRENT = V3;

    public static final String CHANNEL = "QA_PASSAGE";
    public static final int CANDIDATE_LIMIT = 12;
    public static final int RECALL_LIMIT = 60;
    public static final int FUSION_LIMIT = 100;
    public static final int RERANK_LIMIT = 40;
    public static final int RRF_K = 60;
    public static final double VECTOR_WEIGHT = 0.7d;
    public static final double KEYWORD_WEIGHT = 0.3d;
    public static final double VECTOR_THRESHOLD = 0.0d;
    public static final double KEYWORD_THRESHOLD = 0.0d;
    public static final String QUERY_REWRITE_VERSION = "qa-conversation-rewrite-v1";
    public static final String QUERY_EXPANSION_POLICY = "qa-low-recall-expansion-v1";
    public static final double STEP_WEIGHT = 1.0d;
    public static final String FILTER_PROFILE = "strategy_profile";
    public static final String FILTER_RELEVANCE_POLICY = "relevance_policy";
    public static final String FILTER_SELECTION_POLICY = "selection_policy";
    public static final String FILTER_V2_ENABLED = "strategy_v2_enabled";
    public static final String FILTER_QUERY_REWRITE_VERSION = "query_rewrite_version";
    public static final String FILTER_QUERY_EXPANSION_POLICY = "query_expansion_policy";
    public static final String FILTER_ORIGINAL_QUERY = "original_query";
    public static final String FILTER_RETRIEVAL_QUERIES = "retrieval_queries";
    public static final String FILTER_MUST_TERMS = "must_terms";
    public static final String FILTER_PREFERRED_TERMS = "preferred_terms";
    public static final String FILTER_LANGUAGE = "language";
    public static final String FILTER_QUESTION_TYPE = "question_type";
    public static final String FILTER_VECTOR_OVER_RECALL = "vector_over_recall";
    public static final String FILTER_KEYWORD_OVER_RECALL = "keyword_over_recall";
    public static final String FILTER_FUSION_LIMIT = "fusion_candidate_ceiling";
    public static final String FILTER_RERANK_TOP_N = "rerank_top_n";
    public static final String FILTER_RRF_K = "rrf_k";
    public static final String FILTER_VECTOR_WEIGHT = "vector_weight";
    public static final String FILTER_KEYWORD_WEIGHT = "keyword_weight";
    public static final String FILTER_VECTOR_THRESHOLD = "vector_threshold";
    public static final String FILTER_KEYWORD_THRESHOLD = "keyword_threshold";
    public static final String FILTER_EVIDENCE_TOKEN_BUDGET = "evidence_token_budget";

    private final String profileVersion;
    private final String planVersion;
    private final String relevancePolicyVersion;
    private final String selectionPolicyVersion;
    private final int maxEvidence;
    private final int maxEvidenceCharacters;
    private final int evidenceTokenBudget;
    private final int stepCandidateLimit;
    private final boolean v2Enabled;

    QaRetrievalStrategyProfile(
            String profileVersion,
            String planVersion,
            String relevancePolicyVersion,
            String selectionPolicyVersion,
            int maxEvidence,
            int maxEvidenceCharacters,
            int evidenceTokenBudget,
            int stepCandidateLimit,
            boolean v2Enabled
    ) {
        this.profileVersion = profileVersion;
        this.planVersion = planVersion;
        this.relevancePolicyVersion = relevancePolicyVersion;
        this.selectionPolicyVersion = selectionPolicyVersion;
        this.maxEvidence = maxEvidence;
        this.maxEvidenceCharacters = maxEvidenceCharacters;
        this.evidenceTokenBudget = evidenceTokenBudget;
        this.stepCandidateLimit = stepCandidateLimit;
        this.v2Enabled = v2Enabled;
    }

    public String profileVersion() {
        return profileVersion;
    }

    public String planVersion() {
        return planVersion;
    }

    public String relevancePolicyVersion() {
        return relevancePolicyVersion;
    }

    public String selectionPolicyVersion() {
        return selectionPolicyVersion;
    }

    public int maxEvidence() {
        return maxEvidence;
    }

    public int maxEvidenceCharacters() {
        return maxEvidenceCharacters;
    }

    /** 证据 token 预算；0 表示沿用 V2 的条数加字符预算。 */
    public int evidenceTokenBudget() {
        return evidenceTokenBudget;
    }

    /** 检索步交给编排器的候选上限；V2 为 12，V3 与证据条数上限一致。 */
    public int stepCandidateLimit() {
        return stepCandidateLimit;
    }

    public boolean usesTokenBudget() {
        return evidenceTokenBudget > 0;
    }

    public boolean v2Enabled() {
        return v2Enabled;
    }

    /** Resolves only an exact, internally supported QA plan tuple. */
    public static QaRetrievalStrategyProfile fromPlan(
            RetrievalPlan plan,
            RetrievalPlan.Step step
    ) {
        if (plan == null || step == null
                || plan.mode() != AnswerMode.QA
                || plan.steps().size() != 1
                || !step.equals(plan.steps().get(0))
                || !CHANNEL.equals(step.channel())
                || Double.compare(step.weight(), STEP_WEIGHT) != 0
                || plan.budget() == null
                || plan.budget().maxGraphHops() != 0
                || plan.budget().maxGraphNodes() != 0
                || plan.budget().maxGraphEdges() != 60
                || plan.budget().maxGraphCharacters() != 4_000) {
            throw unsupportedPlan();
        }
        Map<String, String> filters = step.filters();
        if (!"ACTIVE".equals(filters.get("snapshot_status"))) {
            throw unsupportedPlan();
        }
        for (QaRetrievalStrategyProfile profile : values()) {
            if (profile.matches(plan, step, filters)) {
                return profile;
            }
        }
        throw unsupportedPlan();
    }

    private boolean matches(RetrievalPlan plan, RetrievalPlan.Step step, Map<String, String> filters) {
        return planVersion.equals(plan.version())
                && step.candidateLimit() == stepCandidateLimit
                && profileVersion.equals(filters.get(FILTER_PROFILE))
                && relevancePolicyVersion.equals(filters.get(FILTER_RELEVANCE_POLICY))
                && selectionPolicyVersion.equals(filters.get(FILTER_SELECTION_POLICY))
                && Boolean.toString(v2Enabled).equals(filters.get(FILTER_V2_ENABLED))
                && plan.budget().maxEvidence() == maxEvidence
                && plan.budget().maxEvidenceCharacters() == maxEvidenceCharacters
                && (evidenceTokenBudget <= 0 || Integer.toString(evidenceTokenBudget)
                        .equals(filters.get(FILTER_EVIDENCE_TOKEN_BUDGET)));
    }

    private static IllegalArgumentException unsupportedPlan() {
        return new IllegalArgumentException("Unsupported or inconsistent QA retrieval strategy tuple");
    }
}
