package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** JDBC-only command line entry point for production QA AnswerRun shadow export. */
public final class QaAnswerRunShadowExportCli {
    private static final String SALT_ENV = "NOTEWEAVE_RETRIEVAL_EXPORT_SALT";

    private QaAnswerRunShadowExportCli() {
    }

    public static void main(String[] args) {
        int exitCode = 2;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                QaAnswerRunShadowExportCliConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles("qa-answer-run-shadow-export-cli")
                .registerShutdownHook(false)
                .properties(
                        "spring.main.banner-mode=off",
                        "spring.main.log-startup-info=false",
                        "spring.jackson.property-naming-strategy=SNAKE_CASE",
                        "spring.datasource.hikari.read-only=true",
                        "spring.datasource.hikari.maximum-pool-size=2",
                        "spring.datasource.hikari.minimum-idle=0"
                )
                .run(springArguments(args))) {
            CliResult result = runCommand(
                    positionalArguments(args),
                    context.getBean(QaAnswerRunShadowExportService.class),
                    System.getenv(SALT_ENV)
            );
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            exitCode = 0;
        } catch (Exception ex) {
            System.err.println("QA AnswerRun shadow export failed: " + ex.getMessage());
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static CliResult runCommand(
            String[] args,
            QaAnswerRunShadowExportService service,
            String salt
    ) throws Exception {
        if (args.length != 4 || !"export-answer-runs".equals(args[0])) {
            throw usage();
        }
        QaAnswerRunShadowExportService.ExportResult result = service.exportAndWrite(
                Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), salt);
        return new CliResult(
                "export-answer-runs",
                result.snapshot().snapshotVersion(),
                result.caseCount()
        );
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
                "Usage: export-answer-runs <annotation-request.json> <run-map.json> <shadow.json>; JDBC from "
                        + "SPRING_DATASOURCE_* and salt from " + SALT_ENV);
    }

    private static String[] springArguments(String[] args) {
        return Arrays.stream(args).filter(item -> item.startsWith("--")).toArray(String[]::new);
    }

    private static String[] positionalArguments(String[] args) {
        return Arrays.stream(args).filter(item -> !item.startsWith("--")).toArray(String[]::new);
    }

    public record CliResult(String operation, String snapshotVersion, int caseCount) {
    }
}
