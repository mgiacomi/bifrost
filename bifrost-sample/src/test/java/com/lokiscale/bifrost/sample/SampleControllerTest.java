package com.lokiscale.bifrost.sample;

import com.lokiscale.bifrost.core.ExecutionJournal;
import com.lokiscale.bifrost.skillapi.SkillExecutionView;
import com.lokiscale.bifrost.skillapi.SkillTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

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
    void sampleControllerDelegatesExpensesToPublicYamlSkill() {
        SkillTemplate skillTemplate = mock(SkillTemplate.class);
        SampleController controller = new SampleController(skillTemplate, new DefaultResourceLoader());
        org.mockito.Mockito.when(skillTemplate.invoke("expenseLookup", Map.of())).thenReturn("[\"expense\"]");

        assertThat(controller.getExpenses()).isEqualTo("[\"expense\"]");
        verify(skillTemplate).invoke("expenseLookup", Map.of());
    }

    @Test
    void sampleControllerDelegatesFeedstockSampleToSkillTemplate() {
        SkillTemplate skillTemplate = mock(SkillTemplate.class);
        SampleController controller = new SampleController(skillTemplate, new DefaultResourceLoader());
        ExecutionJournal journal = new ExecutionJournal(java.util.List.of());
        doAnswer(invocation -> {
            Consumer<SkillExecutionView> observer = invocation.getArgument(2);
            observer.accept(new SkillExecutionView("feedstock-session", journal));
            return "{\"ticket_no\":\"46843\"}";
        }).when(skillTemplate).invoke(eq("feedstockTicketParser"), any(Map.class), any());

        Map<String, Object> response = (Map<String, Object>) controller.parseSampleFeedstockTicket();

        verify(skillTemplate).invoke(eq("feedstockTicketParser"), any(Map.class), any());
        assertThat(response.get("result")).isEqualTo("{\"ticket_no\":\"46843\"}");
        assertThat(response.get("filePath")).isEqualTo("classpath:/forms/feedstock-p1.jpg");
        assertThat(response.get("sessionId")).isEqualTo("feedstock-session");
        assertThat(response.get("executionJournal")).isEqualTo(journal);
    }

    @Test
    void sampleControllerDelegatesToSkillTemplate() throws Exception {
        SkillTemplate skillTemplate = mock(SkillTemplate.class);
        SampleController controller = new SampleController(skillTemplate, new DefaultResourceLoader());
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

    @Test
    void sampleControllerDelegatesPureYamlFeedstockResourceToSkillTemplate() {
        SkillTemplate skillTemplate = mock(SkillTemplate.class);
        SampleController controller = new SampleController(skillTemplate, new DefaultResourceLoader());
        doAnswer(invocation -> "{\"ticket_no\":\"46843\"}")
                .when(skillTemplate).invoke(eq("feedstockTicketParserBySkill"), any(Map.class), any());

        Map<String, Object> response = (Map<String, Object>) controller.parseSampleFeedstockTicketBySkill();

        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(skillTemplate).invoke(eq("feedstockTicketParserBySkill"), inputCaptor.capture(), any());
        assertThat(inputCaptor.getValue().get("image")).isInstanceOf(Resource.class);
        assertThat(response.get("result")).isEqualTo("{\"ticket_no\":\"46843\"}");
        assertThat(response.get("filePath")).isEqualTo("classpath:/forms/feedstock-p1.jpg");
    }
}
