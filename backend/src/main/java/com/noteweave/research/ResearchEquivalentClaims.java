package com.noteweave.research;

/** 候选证据的原始结论绑定，首次提交和重放共用同一判断。 */
final class ResearchEquivalentClaims {
    private ResearchEquivalentClaims() { }

    static boolean supports(ResearchAgentCompletionEnvelope.Candidate candidate, String evidenceKey, String claim) {
        return candidate.candidateValue().equals(claim) || (candidate.equivalentClaims() != null
                && candidate.equivalentClaims().stream().anyMatch(item -> item.evidenceKey().equals(evidenceKey)
                && item.claimText().equals(claim)));
    }
}
