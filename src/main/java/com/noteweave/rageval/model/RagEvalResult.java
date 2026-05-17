package com.noteweave.rageval.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Setter
@Entity
@Table(name = "rag_eval_result")
public class RagEvalResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "answer_message_id")
    private Long answerMessageId;

    @Column(name = "retrieval_trace_id")
    private Long retrievalTraceId;

    @Column(name = "llm_call_log_id")
    private Long llmCallLogId;

    @Column(name = "recall_at_k", precision = 8, scale = 4)
    private BigDecimal recallAtK;

    @Column(precision = 8, scale = 4)
    private BigDecimal mrr;

    @Column(name = "citation_coverage", precision = 8, scale = 4)
    private BigDecimal citationCoverage;

    @Column(name = "groundedness_score", precision = 8, scale = 4)
    private BigDecimal groundednessScore;

    @Column(name = "answer_quality_score", precision = 8, scale = 4)
    private BigDecimal answerQualityScore;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "answer_snapshot", columnDefinition = "LONGTEXT")
    private String answerSnapshot;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
