package com.lokiscale.bifrost.runtime.trace;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionTraceBoundaryCleanupTest {

    @Test
    void planningAndMissionDoNotEmitModelTraceTaxonomyDirectly() throws Exception {
        assertThat(source("com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java"))
                .doesNotContain("TraceRecordType.MODEL_REQUEST_PREPARED")
                .doesNotContain("TraceRecordType.MODEL_REQUEST_SENT")
                .doesNotContain("TraceRecordType.MODEL_RESPONSE_RECEIVED")
                .doesNotContain("recordTrace(");

        assertThat(source("com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java"))
                .doesNotContain("TraceRecordType.MODEL_REQUEST_PREPARED")
                .doesNotContain("TraceRecordType.MODEL_REQUEST_SENT")
                .doesNotContain("TraceRecordType.MODEL_RESPONSE_RECEIVED")
                .doesNotContain("recordTrace(");
    }

    @Test
    void advisorsDoNotImportTraceRecordType() throws Exception {
        assertThat(source("com/lokiscale/bifrost/linter/LinterCallAdvisor.java"))
                .doesNotContain("import com.lokiscale.bifrost.core.TraceRecordType;");

        assertThat(source("com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java"))
                .doesNotContain("import com.lokiscale.bifrost.core.TraceRecordType;");
    }

    private static String source(String relativePath) throws IOException {
        Path path = Path.of("src/main/java").resolve(relativePath);
        return Files.readString(path);
    }
}
