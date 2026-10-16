package com.noteweave.memory;

/** 列表接口上的 Beta 后验。均值和方差由 α、β 现算。 */
public record MemoryConfidenceResponse(
        double alpha,
        double beta,
        double mean,
        double variance
) {
    public static MemoryConfidenceResponse of(double alpha, double beta) {
        MemoryEvidence.Posterior posterior = new MemoryEvidence.Posterior(alpha, beta);
        return new MemoryConfidenceResponse(alpha, beta, posterior.mean(), posterior.variance());
    }
}
