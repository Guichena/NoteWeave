package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public final class RetrievalEvaluationCli {
    private static final String SALT_ENV = "NOTEWEAVE_RETRIEVAL_EXPORT_SALT";

    private RetrievalEvaluationCli() {
    }

    public static void main(String[] args) {
        int exitCode = 2;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                RetrievalEvaluationCliConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles("retrieval-evaluation-cli")
                .registerShutdownHook(false)
                .properties(
                        "spring.main.banner-mode=off",
                        "spring.main.log-startup-info=false"
                )
                .run(springArguments(args))) {
            NoteWeaveProperties properties = context.getBean(NoteWeaveProperties.class);
            if (!properties.elasticsearch().enabled()) {
                throw new IllegalStateException("Retrieval evaluation CLI requires Elasticsearch to be enabled");
            }
            CliResult result = runCommand(
                    positionalArguments(args),
                    context.getBean(QaGoldAnnotationDraftCapture.class),
                    context.getBean(QaGoldAnnotationCompiler.class),
                    System.getenv(SALT_ENV)
            );
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            exitCode = 0;
        } catch (Exception ex) {
            System.err.println("Retrieval evaluation CLI failed: " + ex.getMessage());
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static CliResult runCommand(
            String[] args,
            QaGoldAnnotationDraftCapture draftCapture,
            QaGoldAnnotationCompiler compiler,
            String salt
    ) throws Exception {
        if (args.length == 0) {
            throw usage();
        }
        return switch (args[0]) {
            case "capture-draft" -> captureDraft(args, draftCapture, salt);
            case "compile-reviewed" -> compileReviewed(args, compiler, salt);
            default -> throw usage();
        };
    }

    private static CliResult captureDraft(
            String[] args,
            QaGoldAnnotationDraftCapture draftCapture,
            String salt
    ) throws Exception {
        if (args.length != 3) {
            throw usage();
        }
        var result = draftCapture.captureAndWrite(
                Path.of(args[1]),
                Path.of(args[2]),
                salt
        );
        return new CliResult(
                "capture-draft",
                result.draft().datasetVersion(),
                "",
                result.draft().cases().size(),
                result.redactionStats()
        );
    }

    private static CliResult compileReviewed(
            String[] args,
            QaGoldAnnotationCompiler compiler,
            String salt
    ) throws Exception {
        if (args.length != 6) {
            throw usage();
        }
        var result = compiler.compileAndWrite(
                Path.of(args[1]),
                Path.of(args[2]),
                Path.of(args[3]),
                Path.of(args[4]),
                args[5],
                salt
        );
        return new CliResult(
                "compile-reviewed",
                result.sanitizedGoldSet().datasetVersion(),
                result.shadowSnapshot().snapshotVersion(),
                result.sanitizedGoldSet().cases().size(),
                result.redactionStats()
        );
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
                "Usage: capture-draft <request.json> <draft.json> | "
                        + "compile-reviewed <request.json> <reviewed.json> <gold.json> <shadow.json> "
                        + "<snapshot-version>; salt from " + SALT_ENV);
    }

    private static String[] springArguments(String[] args) {
        return Arrays.stream(args).filter(item -> item.startsWith("--")).toArray(String[]::new);
    }

    private static String[] positionalArguments(String[] args) {
        return Arrays.stream(args).filter(item -> !item.startsWith("--")).toArray(String[]::new);
    }

    public record CliResult(
            String operation,
            String datasetVersion,
            String snapshotVersion,
            int caseCount,
            RetrievalSnapshotSanitizer.RedactionStats redactionStats
    ) {
    }
}
