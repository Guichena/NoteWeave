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
            true
    );

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

    private final String profileVersion;
    private final String planVersion;
    private final String relevancePolicyVersion;
    private final String selectionPolicyVersion;
    private final int maxEvidence;
    private final int maxEvidenceCharacters;
    private final boolean v2Enabled;

    QaRetrievalStrategyProfile(
            String profileVersion,
            String planVersion,
            String relevancePolicyVersion,
            String selectionPolicyVersion,
            int maxEvidence,
            int maxEvidenceCharacters,
            boolean v2Enabled
    ) {
        this.profileVersion = profileVersion;
        this.planVersion = planVersion;
        this.relevancePolicyVersion = relevancePolicyVersion;
        this.selectionPolicyVersion = selectionPolicyVersion;
        this.maxEvidence = maxEvidence;
        this.maxEvidenceCharacters = maxEvidenceCharacters;
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
                || step.candidateLimit() != CANDIDATE_LIMIT
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
            if (profile.matches(plan, filters)) {
                return profile;
            }
        }
        throw unsupportedPlan();
    }

    private boolean matches(RetrievalPlan plan, Map<String, String> filters) {
        return planVersion.equals(plan.version())
                && profileVersion.equals(filters.get(FILTER_PROFILE))
                && relevancePolicyVersion.equals(filters.get(FILTER_RELEVANCE_POLICY))
                && selectionPolicyVersion.equals(filters.get(FILTER_SELECTION_POLICY))
                && Boolean.toString(v2Enabled).equals(filters.get(FILTER_V2_ENABLED))
                && plan.budget().maxEvidence() == maxEvidence
                && plan.budget().maxEvidenceCharacters() == maxEvidenceCharacters;
    }

    private static IllegalArgumentException unsupportedPlan() {
        return new IllegalArgumentException("Unsupported or inconsistent QA retrieval strategy tuple");
    }
}
