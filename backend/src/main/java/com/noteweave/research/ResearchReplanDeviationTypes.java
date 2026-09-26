package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.util.Set;

/**
 * Auditable Replan deviation vocabulary, mirrored from the architecture document section
 * "Plan 与 Replan 的可验证合同".
 *
 * <p>A Replan is only allowed when the observed deviation maps onto one of these seven causes.
 * "The model wanted to try a different approach" is deliberately not representable: there is
 * no value for it, so it cannot be persisted.
 */
public final class ResearchReplanDeviationTypes {

    /** The user's intent was compiled into the wrong goal, constraint or deliverable. */
    public static final String INPUT_MISUNDERSTOOD = "INPUT_MISUNDERSTOOD";
    /** Frozen context, source scope or evidence horizon changed after planning. */
    public static final String CONTEXT_CHANGED = "CONTEXT_CHANGED";
    /** The plan's required findings cannot all be satisfied at the same time. */
    public static final String CONSTRAINT_CONFLICT = "CONSTRAINT_CONFLICT";
    /** Further work on the current plan yields no new information for its cost. */
    public static final String MARGINAL_GAIN_EXHAUSTED = "MARGINAL_GAIN_EXHAUSTED";
    /** A tool or provider failed and the plan must be re-routed. */
    public static final String PROVIDER_FAILURE = "PROVIDER_FAILURE";
    /** Qualified evidence contradicts itself and needs a different verification path. */
    public static final String EVIDENCE_CONFLICT = "EVIDENCE_CONFLICT";

    /**
     * An accepted Discovery proposal expanded the plan: rows, columns or cells were added on top
     * of the plan the frozen context originally compiled. This is the deviation recorded by the
     * Discovery revision trigger: {@code research_matrix_plan} gains a second, ACTIVE plan row
     * whose {@code plan_mode} is {@code DISCOVERY_REVISION} and whose {@code plan_digest} differs
     * from the plan it replaces.
     *
     * <p>It is deliberately not {@link #CONTEXT_CHANGED}. The frozen context, the source scope and
     * the evidence horizon did not change - the plan's scope did, because a server-side acceptance
     * gate took a Discovery proposal and widened the matrix. Folding a discovery-driven expansion
     * into {@code CONTEXT_CHANGED} would attribute a server-side scope decision to an input change,
     * which is not the observable deviation and would make "how often does the context move the
     * plan" unmeasurable.
     */
    public static final String SCOPE_EXPANDED = "SCOPE_EXPANDED";

    /** The complete, closed vocabulary. Any value outside this set is rejected. */
    public static final Set<String> VALUES = Set.of(
            INPUT_MISUNDERSTOOD,
            CONTEXT_CHANGED,
            CONSTRAINT_CONFLICT,
            MARGINAL_GAIN_EXHAUSTED,
            PROVIDER_FAILURE,
            EVIDENCE_CONFLICT,
            SCOPE_EXPANDED
    );

    private ResearchReplanDeviationTypes() {
    }

    /**
     * Normalizes and validates a deviation type.
     *
     * @return the stripped value when it is one of {@link #VALUES}
     * @throws BusinessException when the value is missing or outside the vocabulary; an unknown
     *         deviation type must never be silently persisted as an audit record
     */
    public static String validate(String deviationType) {
        String normalized = deviationType == null ? "" : deviationType.strip();
        if (!VALUES.contains(normalized)) {
            throw new BusinessException(
                    "RESEARCH_REPLAN_DEVIATION_TYPE_INVALID",
                    "Replan deviation type is not an auditable cause");
        }
        return normalized;
    }
}
