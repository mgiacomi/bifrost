package com.lokiscale.bifrost.internal.runtime.trace;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionTraceBoundaryCleanupTest {

    @Test
    void planningAndMissionDoNotEmitModelTraceTaxonomyDirectly() throws Exception {
        assertThat(source("com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java"))
                .doesNotContain("TraceRecordType.MODEL_REQUEST_PREPARED")
                .doesNotContain("TraceRecordType.MODEL_REQUEST_SENT")
                .doesNotContain("TraceRecordType.MODEL_RESPONSE_RECEIVED")
                .doesNotContain("recordTrace(");

        assertThat(source("com/lokiscale/bifrost/internal/runtime/DefaultMissionExecutionEngine.java"))
                .doesNotContain("TraceRecordType.MODEL_REQUEST_PREPARED")
                .doesNotContain("TraceRecordType.MODEL_REQUEST_SENT")
                .doesNotContain("TraceRecordType.MODEL_RESPONSE_RECEIVED")
                .doesNotContain("recordTrace(");
    }

    @Test
    void advisorsDoNotImportTraceRecordType() throws Exception {
        assertThat(source("com/lokiscale/bifrost/internal/linter/LinterCallAdvisor.java"))
                .doesNotContain("import com.lokiscale.bifrost.internal.core.TraceRecordType;");

        assertThat(source("com/lokiscale/bifrost/internal/outputschema/OutputSchemaCallAdvisor.java"))
                .doesNotContain("import com.lokiscale.bifrost.internal.core.TraceRecordType;");
    }

    private static String source(String relativePath) throws IOException {
        Path path = Path.of("src/main/java").resolve(relativePath);
        return Files.readString(path);
    }
}
