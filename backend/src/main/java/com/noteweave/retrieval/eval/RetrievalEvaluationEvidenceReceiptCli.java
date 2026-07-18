package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Arrays;

public final class RetrievalEvaluationEvidenceReceiptCli {
    private RetrievalEvaluationEvidenceReceiptCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage: RetrievalEvaluationEvidenceReceiptCli "
                            + "<gold-set.json> <receipt.json> <shadow.json> [shadow.json ...]");
        }
        var writer = new RetrievalEvaluationEvidenceReceipt();
        var receipt = writer.createAndWrite(
                Path.of(args[0]),
                Arrays.stream(args).skip(2).map(Path::of).toList(),
                Path.of(args[1])
        );
        var summary = new ReceiptSummary(
                receipt.schemaVersion(),
                receipt.datasetVersion(),
                receipt.strategyProfile(),
                receipt.snapshots().size(),
                receipt.stability().rankingsStable()
        );
        System.out.println(new ObjectMapper().findAndRegisterModules()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(summary));
    }

    private record ReceiptSummary(
            String schemaVersion,
            String datasetVersion,
            String strategyProfile,
            int snapshotCount,
            boolean rankingsStable
    ) {
    }
}
