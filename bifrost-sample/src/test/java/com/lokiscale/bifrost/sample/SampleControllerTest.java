package com.lokiscale.bifrost.sample;

import com.lokiscale.bifrost.core.ExecutionJournal;
import com.lokiscale.bifrost.skillapi.SkillExecutionView;
import com.lokiscale.bifrost.skillapi.SkillTemplate;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SampleControllerTest {

    @Test
    void sampleControllerDelegatesToSkillTemplate() throws Exception {
        SkillTemplate skillTemplate = mock(SkillTemplate.class);
        SampleController controller = new SampleController(skillTemplate);
        ExecutionJournal journal = new ExecutionJournal(java.util.List.of());
        doAnswer(invocation -> {
            Consumer<SkillExecutionView> observer = invocation.getArgument(2);
            observer.accept(new SkillExecutionView("session-123", journal));
            return "\"ok\"";
        }).when(skillTemplate).invoke(eq("duplicateInvoiceChecker"), any(Map.class), any());

        Map<String, Object> response = (Map<String, Object>) controller.checkDuplicateInvoice(
                "C:\\opendev\\code\\bifrost\\bifrost-sample\\src\\test\\resources\\fixtures\\duplicate-invoice.txt");

        verify(skillTemplate).invoke(eq("duplicateInvoiceChecker"), any(Map.class), any());
        assertThat(response.get("result")).isEqualTo("\"ok\"");
        assertThat(response.get("sessionId")).isEqualTo("session-123");
        assertThat(response.get("executionJournal")).isEqualTo(journal);
    }
}
