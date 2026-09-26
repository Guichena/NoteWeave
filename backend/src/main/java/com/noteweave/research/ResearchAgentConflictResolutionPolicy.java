package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * DR-304: pure, deterministic <em>resolution</em> predicate for a global conflict.
 *
 * <p>DR-303 answers "is there a live disagreement between independent sources?". This policy answers
 * the follow-up question DR-303 deliberately left open: "has the disagreement been adjudicated?" A
 * conflict is resolved when either</p>
 *
 * <ol>
 *   <li>the DR-303 predicate no longer fires over the cell's current evidence (the dissenting
 *       evidence was rejected / unbound), or</li>
 *   <li><b>corroboration</b>: <em>within the disputed fact domain</em>, one value is asserted by
 *       strictly more independent source identities than any competing value ("a new
 *       independent-source evidence agrees with one side"). A 1-vs-1 tie is <b>not</b> resolved.</li>
 * </ol>
 *
 * <p><b>The voting domain is the conflict's own fact domain, never the whole cell.</b> The anchor is
 * derived from the actual conflicting pair — its date context and its subject skeleton — so two
 * corroborating sources that talk about a <em>different</em> subject can never out-vote a live
 * disagreement (they merely agree about something else). There is exactly <b>one</b> anchor
 * implementation: this policy calls
 * {@link ResearchGlobalConflictDetector#sameDateContext(java.util.Set, java.util.Set)} and
 * {@link ResearchGlobalConflictDetector#skeleton(String)}, the very primitives DR-303 uses to decide
 * whether two claims are even comparable, so the detector and the resolver can never silently
 * disagree about what a fact domain is.</p>
 *
 * <p><b>Relation-type conflicts are never resolved by voting.</b>
 * {@code CONFLICTS_RELATION_WITH_SUPPORT} names a disagreement about the relation itself, which has
 * no comparable value to count, so its only resolution path is the dissenting evidence being
 * rejected / unbound. Such a conflict therefore normally runs through one bounded counterfactual
 * attempt and then becomes an explicit limitation — that is intended: a majority vote must not be
 * used to adjudicate a relation dispute.</p>
 *
 * <p>Like the detector this is a pure function: no database, no clock, no randomness, no LLM.</p>
 */
@Component
class ResearchAgentConflictResolutionPolicy {

    private final ResearchTypedClaimValidator typedClaimValidator;
    private final ResearchGlobalConflictDetector detector;

    ResearchAgentConflictResolutionPolicy(
            ResearchTypedClaimValidator typedClaimValidator,
            ResearchGlobalConflictDetector detector
    ) {
        this.typedClaimValidator = typedClaimValidator;
        this.detector = detector;
    }

    /** True when the conflict no longer exists or has been out-voted by corroborating sources. */
    boolean isResolved(
            ResearchGlobalConflictDetector.CellConflictFacts cell,
            ResearchGlobalConflictDetector.ConflictFinding finding
    ) {
        if (finding == null) return true;
        List<ResearchGlobalConflictDetector.EvidenceFact> identified = cell.evidence().stream()
                .filter(ResearchGlobalConflictDetector.EvidenceFact::evidenceLevelValid)
                .filter(ResearchGlobalConflictDetector.EvidenceFact::hasCompleteSourceIdentity)
                .toList();
        if (identified.isEmpty()) return false;
        Map<String, ResearchGlobalConflictDetector.EvidenceFact> byKey = new LinkedHashMap<>();
        identified.forEach(fact -> byKey.put(fact.evidenceKey(), fact));

        List<String> disputed = finding.evidenceKeys();
        for (int left = 0; left < disputed.size(); left++) {
            for (int right = left + 1; right < disputed.size(); right++) {
                ResearchGlobalConflictDetector.EvidenceFact first = byKey.get(disputed.get(left));
                ResearchGlobalConflictDetector.EvidenceFact second = byKey.get(disputed.get(right));
                if (first == null || second == null) continue;
                List<String> codes = detector.mutuallyExclusiveCodes(first.claimText(), second.claimText());
                if (codes.isEmpty()) continue;
                // Anchor the vote to the disputed fact domain (date context + subject skeleton),
                // reusing the detector's own anchor primitives so the "can these be compared"
                // definition has exactly one implementation.
                Set<String> anchorDates = facts(first.claimText()).dates();
                String anchorSkeleton = detector.skeleton(first.claimText());
                List<ResearchGlobalConflictDetector.EvidenceFact> domain = identified.stream()
                        .filter(fact -> detector.sameDateContext(facts(fact.claimText()).dates(), anchorDates)
                                && detector.skeleton(fact.claimText()).equals(anchorSkeleton))
                        .toList();
                if (codes.contains(ResearchGlobalConflictDetector.REASON_NUMBER_VALUE_CONFLICT)
                        && strictMajority(domain, fact -> numberSignature(fact.claimText()))) {
                    return true;
                }
                if (codes.contains(ResearchGlobalConflictDetector.REASON_COMPARISON_DIRECTION_CONFLICT)
                        && strictMajority(domain, fact -> comparisonSignature(fact.claimText()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * One value group holds strictly more independent sources than every other group. Evidence that
     * carries no fact of this kind is ignored, and fewer than two value groups is never a majority
     * (there is nothing being out-voted).
     */
    private boolean strictMajority(
            List<ResearchGlobalConflictDetector.EvidenceFact> domain,
            Function<ResearchGlobalConflictDetector.EvidenceFact, String> signatureOf
    ) {
        Map<String, List<ResearchGlobalConflictDetector.EvidenceFact>> groups = new TreeMap<>();
        for (ResearchGlobalConflictDetector.EvidenceFact fact : domain) {
            String signature = signatureOf.apply(fact);
            if (signature.isEmpty()) continue;
            groups.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(fact);
        }
        if (groups.size() < 2) return false;
        List<Integer> counts = groups.values().stream()
                .map(this::independentSourceCount)
                .sorted(Comparator.reverseOrder())
                .toList();
        return counts.get(0) > counts.get(1);
    }

    /**
     * Greedy non-overlapping identity count; two evidence belong to one source when their registrable
     * domain or lineage overlaps.
     *
     * <p><b>Coupling:</b> this is a <em>second copy</em> of
     * {@code ResearchEvidenceQualificationService#independentSourceCount} (the original is an instance
     * method over a different evidence type, so it is not directly reusable). If that method's notion
     * of source independence ever changes, <b>this copy must change with it</b> — otherwise the
     * corroboration vote here would count sources differently from the qualification gate, and a value
     * could be declared corroborated by sources the gate does not consider independent.</p>
     */
    private int independentSourceCount(List<ResearchGlobalConflictDetector.EvidenceFact> evidence) {
        List<String[]> accepted = new ArrayList<>();
        for (ResearchGlobalConflictDetector.EvidenceFact fact : evidence.stream()
                .sorted(Comparator.comparing(ResearchGlobalConflictDetector.EvidenceFact::evidenceKey))
                .toList()) {
            String domain = fact.registeredDomain();
            String lineage = fact.lineageDigest();
            boolean overlaps = accepted.stream().anyMatch(existing ->
                    existing[0].equals(domain) || existing[1].equals(lineage));
            if (!overlaps) accepted.add(new String[]{domain, lineage});
        }
        return accepted.size();
    }

    private ResearchTypedClaimValidator.Facts facts(String claim) {
        try {
            return typedClaimValidator.facts(claim);
        } catch (RuntimeException exception) {
            throw new BusinessException("RESEARCH_AGENT_CONFLICT_FACTS_INVALID", "Claim typed facts are not readable");
        }
    }

    private String numberSignature(String claim) {
        return facts(claim).numbers().stream()
                .map(number -> number.value() + ":" + number.unit())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private String comparisonSignature(String claim) {
        return facts(claim).comparisons().stream()
                .sorted()
                .collect(Collectors.joining(","));
    }
}
