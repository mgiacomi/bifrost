import com.lokiscale.bifrost.core.*;
import com.lokiscale.bifrost.runtime.state.*;
import com.lokiscale.bifrost.linter.*;
import com.lokiscale.bifrost.outputschema.*;
import java.time.*;
import java.util.*;

public class TraceDebug {
  public static void main(String[] args) throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC);
    DefaultExecutionStateService stateService = new DefaultExecutionStateService(clock);
    BifrostSession session = new BifrostSession("debug-session", 3);
    ExecutionPlan plan = new ExecutionPlan("plan-1", "root.visible.skill", Instant.parse("2026-03-15T12:00:00Z"), java.util.List.of(new PlanTask("task-1", "Use tool", PlanTaskStatus.PENDING, null)));
    LinterOutcome outcome = new LinterOutcome("linted.skill", "regex", 2, 1, 2, LinterOutcomeStatus.PASSED, "Return fenced YAML only.");
    OutputSchemaOutcome outputSchemaOutcome = new OutputSchemaOutcome("schema.skill", OutputSchemaFailureMode.SCHEMA_VALIDATION_FAILED, 1, 0, 2, OutputSchemaOutcomeStatus.RETRYING, java.util.List.of(new OutputSchemaValidationIssue("$.vendorName", "missing required field 'vendorName'", "vendorName")));
    ExecutionFrame frame = stateService.openMissionFrame(session, "root.visible.skill", java.util.Map.of("objective", "hello"));
    stateService.storePlan(session, plan);
    stateService.logPlanCreated(session, plan);
    stateService.logToolCall(session, TaskExecutionEvent.linked("allowed.visible.skill", "task-1", java.util.Map.of("arguments", java.util.Map.of("value", "hello")), null));
    stateService.logToolResult(session, TaskExecutionEvent.linked("allowed.visible.skill", "task-1", java.util.Map.of("result", "done"), null));
    stateService.recordLinterOutcome(session, outcome);
    stateService.recordOutputSchemaOutcome(session, outputSchemaOutcome);
    stateService.logError(session, java.util.Map.of("message", "boom"));
    stateService.closeMissionFrame(session, frame);
    System.out.println("JOURNAL");
    for (JournalEntry e : session.getJournalSnapshot()) System.out.println(e.type() + " -> " + e.payload());
    System.out.println("RECORDS");
    session.getExecutionTraceHandle().readRecords(r -> System.out.println(r.sequence() + " " + r.recordType() + " md=" + r.metadata() + " data=" + r.data()));
  }
}
