package com.noteweave.research;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * DR-303: pure, deterministic <em>global</em> conflict adjudicator over already-persisted canonical
 * facts.
 *
 * <p>This is deliberately not a verifier and not an LLM call. Decision D-14 makes the Backend the
 * hard gate for the canonical ledger, and the persistent facts it consumes are the evidence rows,
 * their typed facts and their source identity — never a Worker verdict, never a model answer.</p>
 *
 * <p>A <b>global</b> conflict is only ever declared for a single {@code cell_key} when at least one
 * of the two following shapes holds:</p>
 *
 * <ol>
 *   <li>{@code TYPED_FACT_CONTRADICTION} — two evidence items bound to the same cell, coming from
 *       <em>different</em> source identities ({@code sourceDomain} / {@code lineageDigest}, reusing
 *       the exact identity vocabulary of
 *       {@link ResearchEvidenceQualificationService#independentSourceCount}), whose typed facts are
 *       <em>mutually exclusive</em> — see {@link #mutuallyExclusiveCodes}.</li>
 *   <li>{@code CONFLICTS_RELATION_WITH_SUPPORT} — a {@code relation_type=CONFLICTS} evidence and a
 *       supporting ({@code SUPPORTS} / {@code WEAK_SUPPORT}) evidence coexist on the same cell while
 *       coming from <em>different</em> source identities. A lone {@code CONFLICTS} relation, or a
 *       support/oppose pair inside one source, is <b>not</b> a conflict.</li>
 * </ol>
 *
 * <h2>Why the cross-claim predicate is defined here, not reused from the local validator</h2>
 *
 * <p>{@link ResearchTypedClaimValidator#validate(String, String)} compares a claim against <em>its
 * own</em> citation span. It is a <b>containment</b> predicate: "does this span contain all of the
 * claim's typed facts". Applying it between two <em>independent</em> claims smuggles containment in
 * as equality and is wrong in both directions, e.g. {@code "2023 revenue was 100"} vs
 * {@code "2024 revenue was 200"} (different periods, both true) or {@code "revenue was 100"} vs
 * {@code "cost was 200"} (different subjects). Reusing it would permanently demote a correct cell,
 * because a conflict is sticky and cannot yet be repaired. What <em>is</em> reused is only the fact
 * vocabulary ({@code NUMBER} / {@code DATE} / {@code COMPARISONS} / {@code OPPOSITES} /
 * {@code facts}), so "what counts as a number / date / direction" cannot drift.</p>
 *
 * <h2>Recall vs precision — division of labour between the two shapes</h2>
 *
 * <p>The typed-fact shape is deliberately a <b>high-precision backstop</b>, not the primary
 * detector: it only fires when two independent claims share the exact same subject skeleton and date
 * context yet disagree on a value, so its recall is narrow by construction. The ordinary, high-recall
 * detection surface is {@code CONFLICTS_RELATION_WITH_SUPPORT}, which relies on the relation type the
 * Worker already assigned and needs no textual coincidence. Do not expect the typed-fact path to fire
 * often; it exists to catch the case the relation label misses, and it trades recall away for
 * precision precisely because a false positive would permanently demote a correct cell.</p>
 *
 * <p>The judgement is a pure function: same facts in, same findings out, no database, no clock, no
 * randomness.</p>
 */
@Component
class ResearchGlobalConflictDetector {

    /** Cross-source typed facts on the same fact kind take mutually exclusive values. */
    static final String KIND_TYPED_FACT_CONTRADICTION = "TYPED_FACT_CONTRADICTION";
    /** A CONFLICTS relation and a supporting relation coexist for the same cell across sources. */
    static final String KIND_CONFLICTS_RELATION_WITH_SUPPORT = "CONFLICTS_RELATION_WITH_SUPPORT";

    /** Reason code: same fact domain, same unit family, different numeric value. */
    static final String REASON_NUMBER_VALUE_CONFLICT = "NUMBER_VALUE_CONFLICT";
    /** Reason code: same fact domain, opposite comparison direction. */
    static final String REASON_COMPARISON_DIRECTION_CONFLICT = "COMPARISON_DIRECTION_CONFLICT";

    /** Upper bound for the persisted rationale summary; never carries model prose or reasoning. */
    static final int MAX_RATIONALE_CHARS = 512;

    private static final Set<String> SUPPORTING_RELATIONS = Set.of("SUPPORTS", "WEAK_SUPPORT");
    private static final Set<String> KNOWN_RELATIONS = Set.of("SUPPORTS", "WEAK_SUPPORT", "CONFLICTS");

    private final ResearchTypedClaimValidator typedClaimValidator;

    ResearchGlobalConflictDetector(ResearchTypedClaimValidator typedClaimValidator) {
        this.typedClaimValidator = typedClaimValidator;
    }

    /**
     * One persisted evidence fact as bound to a cell. {@code evidenceLevelValid()} mirrors the
     * reconstructible part of {@code ResearchEvidenceQualificationService#validateEvidence} for a
     * row that was persisted: the source authority was already enforced before persistence, and a
     * known relation plus a non-blank claim and quote are the evidence-level acceptance conditions.
     */
    record EvidenceFact(
            String evidenceKey,
            String relationType,
            String claimText,
            String quoteText,
            String sourceDomain,
            String lineageDigest
    ) {
        boolean evidenceLevelValid() {
            return relationType != null && KNOWN_RELATIONS.contains(relationType)
                    && claimText != null && !claimText.isBlank()
                    && quoteText != null && !quoteText.isBlank();
        }

        String registeredDomain() {
            return ResearchEvidenceQualificationService.registeredDomain(sourceDomain);
        }

        boolean hasCompleteSourceIdentity() {
            String domain = registeredDomain();
            return !domain.isBlank() && lineageDigest != null
                    && ResearchEvidenceQualificationService.LINEAGE.matcher(lineageDigest).matches();
        }
    }

    /** Canonical facts for one cell: its current state plus every evidence fact bound to it. */
    record CellConflictFacts(String cellKey, String cellStatus, String candidateValue, List<EvidenceFact> evidence) {
        CellConflictFacts {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /**
     * A recorded global conflict: the coarse {@code conflictKind} (which shape fired), the granular
     * {@code reasonCodes} that actually proved exclusivity, the involved evidence ids and a bounded,
     * model-free summary. The trace stores one row per granular reason code, so a query can ask "how
     * many numeric-value conflicts" as well as "how many global conflicts".
     */
    record ConflictFinding(String cellKey, String conflictKind, List<String> reasonCodes,
                           List<String> evidenceKeys, String rationale) {
        ConflictFinding {
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
            evidenceKeys = evidenceKeys == null ? List.of() : List.copyOf(evidenceKeys);
        }
    }

    /** Detects at most one global conflict per cell (typed-fact contradiction wins over relation). */
    List<ConflictFinding> detect(List<CellConflictFacts> cells) {
        if (cells == null) return List.of();
        List<ConflictFinding> findings = new ArrayList<>();
        for (CellConflictFacts cell : cells) {
            ConflictFinding finding = detectCell(cell);
            if (finding != null) findings.add(finding);
        }
        return List.copyOf(findings);
    }

    ConflictFinding detectCell(CellConflictFacts cell) {
        List<EvidenceFact> valid = cell.evidence().stream()
                .filter(EvidenceFact::evidenceLevelValid)
                .sorted(Comparator.comparing(EvidenceFact::evidenceKey))
                .toList();
        ConflictFinding typed = typedFactContradiction(cell.cellKey(), valid);
        if (typed != null) return typed;
        return conflictsRelationWithSupport(cell.cellKey(), valid);
    }

    // ------------------------------------------------------------------ constraint 1: typed facts

    private ConflictFinding typedFactContradiction(String cellKey, List<EvidenceFact> valid) {
        List<EvidenceFact> identified = valid.stream().filter(EvidenceFact::hasCompleteSourceIdentity).toList();
        TreeSet<String> involved = new TreeSet<>();
        TreeSet<String> reasonCodes = new TreeSet<>();
        for (int left = 0; left < identified.size(); left++) {
            for (int right = left + 1; right < identified.size(); right++) {
                EvidenceFact first = identified.get(left);
                EvidenceFact second = identified.get(right);
                // Same source identity: a local self-contradiction, never a global conflict.
                if (sameSourceIdentity(first, second)) continue;
                List<String> codes = mutuallyExclusiveCodes(first.claimText(), second.claimText());
                if (codes.isEmpty()) continue;
                involved.add(first.evidenceKey());
                involved.add(second.evidenceKey());
                reasonCodes.addAll(codes);
            }
        }
        if (involved.isEmpty()) return null;
        return new ConflictFinding(cellKey, KIND_TYPED_FACT_CONTRADICTION, List.copyOf(reasonCodes),
                List.copyOf(involved), rationale(KIND_TYPED_FACT_CONTRADICTION, involved, reasonCodes));
    }

    /**
     * Two evidence belong to the same source when their identity overlaps, exactly as
     * {@code independentSourceCount} defines independence: same registrable domain or same lineage.
     */
    private boolean sameSourceIdentity(EvidenceFact first, EvidenceFact second) {
        return first.registeredDomain().equals(second.registeredDomain())
                || first.lineageDigest().equals(second.lineageDigest());
    }

    /**
     * The DR-303 cross-claim mutual-exclusion predicate: two independent claims are mutually
     * exclusive iff they describe the <b>same fact domain</b> and take contradictory values on a
     * fact kind. Returns the reason codes that fired (empty when not exclusive).
     *
     * <p>"Same fact domain" is decided explicitly, never inferred from the validator's containment
     * result:</p>
     * <ol>
     *   <li><b>date context</b> — if both claims carry dates they must be the same date set; if
     *       exactly one carries a date the pair is not comparable. Two facts placed in different
     *       periods are not in conflict even when their numbers differ.</li>
     *   <li><b>subject skeleton</b> — the claim text with all date, number and direction tokens
     *       removed must be identical. Different subjects (e.g. revenue vs cost) are not the same
     *       fact.</li>
     * </ol>
     * <p>Only then are values compared: numbers with a compatible unit (same, or one blank) must
     * differ, and a comparison direction must have its opposite present.</p>
     */
    List<String> mutuallyExclusiveCodes(String claimA, String claimB) {
        ResearchTypedClaimValidator.Facts factsA = typedClaimValidator.facts(claimA);
        ResearchTypedClaimValidator.Facts factsB = typedClaimValidator.facts(claimB);
        if (!sameDateContext(factsA.dates(), factsB.dates())) return List.of();
        if (!skeleton(claimA).equals(skeleton(claimB))) return List.of();

        List<String> codes = new ArrayList<>();
        if (numbersExclusive(factsA.numbers(), factsB.numbers())) {
            codes.add(REASON_NUMBER_VALUE_CONFLICT);
        }
        if (comparisonsOpposed(factsA.comparisons(), factsB.comparisons())) {
            codes.add(REASON_COMPARISON_DIRECTION_CONFLICT);
        }
        return List.copyOf(codes);
    }

    /**
     * Two claims share a date context when neither carries a date, or both carry the same set of
     * dates. A claim with a date is never compared against a claim without one.
     *
     * <p>DR-304: package-private so the conflict-resolution policy reuses the <em>same</em> anchor
     * primitive rather than re-implementing it — the detector decides "can these two be compared"
     * and the resolver decides "who do we count"; both must agree on what a fact domain is.</p>
     */
    boolean sameDateContext(Set<String> datesA, Set<String> datesB) {
        if (datesA.isEmpty() && datesB.isEmpty()) return true;
        return !datesA.isEmpty() && datesA.equals(datesB);
    }

    /**
     * The claim text with every typed-fact token (date, number + unit, direction phrase) replaced by
     * a space, then whitespace-collapsed and lower-cased. This is the "fact domain" anchor: two
     * claims over the same subject share a skeleton; different subjects do not.
     *
     * <p>DR-304: package-private so the conflict-resolution policy reuses this exact definition when
     * it anchors its corroboration vote; there is deliberately only one skeleton implementation.</p>
     *
     * <p>Normalization policy (deliberately conservative and exact):</p>
     * <ul>
     *   <li><b>case is normalized</b> ({@code toLowerCase(Locale.ROOT)}) — capitalization carries no
     *       meaning in a fact domain, so {@code "Revenue was 100"} and {@code "revenue was 200"} must
     *       still meet on the same subject.</li>
     *   <li><b>punctuation is not normalized</b> and matching stays plain string equality. A
     *       punctuation/spelling difference therefore yields a <em>miss</em> (no conflict declared),
     *       never a false positive. The asymmetry is intentional: a miss only leaves a cell
     *       unmarked, whereas a false positive permanently demotes a correct cell.</li>
     * </ul>
     */
    String skeleton(String claim) {
        String value = claim == null ? "" : claim;
        value = ResearchTypedClaimValidator.DATE.matcher(value).replaceAll(" ");
        value = ResearchTypedClaimValidator.NUMBER.matcher(value).replaceAll(" ");
        for (ResearchTypedClaimValidator.ComparisonPattern comparison
                : ResearchTypedClaimValidator.COMPARISONS) {
            value = comparison.pattern().matcher(value).replaceAll(" ");
        }
        return value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Numbers are mutually exclusive when at least one pair is unit-compatible and <em>no</em>
     * unit-compatible pair shares the same value.
     */
    private boolean numbersExclusive(
            List<ResearchTypedClaimValidator.NumberFact> numbersA,
            List<ResearchTypedClaimValidator.NumberFact> numbersB
    ) {
        if (numbersA.isEmpty() || numbersB.isEmpty()) return false;
        boolean anyComparable = false;
        for (ResearchTypedClaimValidator.NumberFact left : numbersA) {
            for (ResearchTypedClaimValidator.NumberFact right : numbersB) {
                if (!unitCompatible(left.unit(), right.unit())) continue;
                anyComparable = true;
                // A shared value proves the two facts can be the same proposition: not exclusive.
                if (left.value().equals(right.value())) return false;
            }
        }
        return anyComparable;
    }

    private boolean unitCompatible(String left, String right) {
        return left.equals(right) || left.isBlank() || right.isBlank();
    }

    private boolean comparisonsOpposed(Set<String> comparisonsA, Set<String> comparisonsB) {
        for (String left : comparisonsA) {
            Set<String> opposites = ResearchTypedClaimValidator.OPPOSITES.getOrDefault(left, Set.of());
            for (String right : comparisonsB) {
                if (opposites.contains(right)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------- constraint 2: CONFLICTS with support

    private ConflictFinding conflictsRelationWithSupport(String cellKey, List<EvidenceFact> valid) {
        List<EvidenceFact> identified = valid.stream().filter(EvidenceFact::hasCompleteSourceIdentity).toList();
        List<EvidenceFact> conflicting = identified.stream()
                .filter(fact -> "CONFLICTS".equals(fact.relationType())).toList();
        if (conflicting.isEmpty()) return null;
        List<EvidenceFact> supporting = identified.stream()
                .filter(fact -> SUPPORTING_RELATIONS.contains(fact.relationType())).toList();
        TreeSet<String> involved = new TreeSet<>();
        for (EvidenceFact oppose : conflicting) {
            for (EvidenceFact support : supporting) {
                // A support/oppose pair inside one source is a local data-quality problem.
                if (sameSourceIdentity(oppose, support)) continue;
                involved.add(oppose.evidenceKey());
                involved.add(support.evidenceKey());
            }
        }
        // A lone CONFLICTS relation (no cross-source supporting evidence) is not a conflict.
        if (involved.isEmpty()) return null;
        return new ConflictFinding(cellKey, KIND_CONFLICTS_RELATION_WITH_SUPPORT,
                List.of(KIND_CONFLICTS_RELATION_WITH_SUPPORT), List.copyOf(involved),
                rationale(KIND_CONFLICTS_RELATION_WITH_SUPPORT, involved, List.of()));
    }

    // ------------------------------------------------------------------ rationale summary

    /** Bounded, deterministic summary. Contains only evidence ids and reason codes — no model text. */
    private String rationale(String kind, Collection<String> evidenceKeys, Collection<String> reasonCodes) {
        StringBuilder builder = new StringBuilder(kind)
                .append(":evidence=").append(String.join(",", evidenceKeys));
        if (!reasonCodes.isEmpty()) builder.append(":reasons=").append(String.join(",", reasonCodes));
        String value = builder.toString();
        return value.length() <= MAX_RATIONALE_CHARS ? value : value.substring(0, MAX_RATIONALE_CHARS);
    }
}
