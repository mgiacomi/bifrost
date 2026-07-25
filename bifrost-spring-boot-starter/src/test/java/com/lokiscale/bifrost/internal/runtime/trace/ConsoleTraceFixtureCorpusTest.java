package com.lokiscale.bifrost.internal.runtime.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.lokiscale.bifrost.internal.core.TracePersistencePolicy;
import com.lokiscale.bifrost.internal.core.TraceRecordType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsoleTraceFixtureCorpusTest
{
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-24T12:00:00Z"), ZoneOffset.UTC);
    private static final Set<String> VALID = Set.of(
            "single-attempt-success",
            "terminal-failure",
            "terminal-abort",
            "advisor-retry",
            "nested-retry-sequences",
            "validation-exhaustion",
            "unavailable-usage",
            "unattributed-usage",
            "nonterminal-error-then-success",
            "chunked-payload");
    private static final Map<String, String> INVALID = Map.of(
            "malformed-json", "MALFORMED_JSON",
            "inconsistent-identities", "INCONSISTENT_IDENTITY",
            "duplicate-sequence", "NON_MONOTONIC_SEQUENCE",
            "incomplete-chunks", "INCOMPLETE_CHUNKS",
            "missing-completion", "MISSING_COMPLETION",
            "non-final-completion", "NON_FINAL_COMPLETION",
            "unsupported-enum", "UNSUPPORTED_VALUE",
            "contradictory-usage-reconciliation", "CONTRADICTORY_USAGE");

    @TempDir
    Path temporaryDirectory;

    @Test
    @Order(1)
    void generatedCorpusMatchesCommittedFixturesByteForByte() throws Exception
    {
        Path generated = temporaryDirectory.resolve("generated");
        generate(generated);
        Path committed = fixtureRoot();

        if (Boolean.getBoolean("bifrost.console.fixtures.regenerate"))
        {
            copyCorpus(generated, committed);
        }

        assertThat(fileNames(committed)).containsExactlyElementsOf(fileNames(generated));
        for (String name : fileNames(generated))
        {
            assertThat(Files.readAllBytes(committed.resolve(name)))
                    .as(name)
                    .isEqualTo(Files.readAllBytes(generated.resolve(name)));
        }
    }

    @Test
    @Order(2)
    void corpusInventoryContainsEveryRequiredSemanticCase() throws Exception
    {
        Set<String> expected = new LinkedHashSet<>();
        Stream.concat(VALID.stream(), INVALID.keySet().stream()).sorted().forEach(name ->
        {
            expected.add("traces/" + name + ".ndjson");
            expected.add("expected/" + name + ".json");
        });
        assertThat(fileNames(fixtureRoot())).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @Order(3)
    void validFixturesSatisfyAttemptTerminalAndUsageInvariants() throws Exception
    {
        for (String name : VALID)
        {
            List<JsonNode> records = parseLines(fixtureRoot().resolve("traces").resolve(name + ".ndjson"));
            assertThat(records).isNotEmpty();
            assertThat(records).extracting(node -> node.path("sequence").asLong()).isSorted();
            assertThat(records.stream().filter(node -> "TRACE_COMPLETED".equals(node.path("recordType").asText())))
                    .hasSize(1);
            assertThat(records.getLast().path("recordType").asText()).isEqualTo("TRACE_COMPLETED");
            assertThat(records).allSatisfy(node -> assertThat(node.has("schemaVersion")).isFalse());

            Map<String, List<Integer>> sequenceAttempts = new LinkedHashMap<>();
            Map<String, List<String>> attemptLifecycle = new LinkedHashMap<>();
            List<Map<String, Object>> actualAttempts = new ArrayList<>();
            records.stream()
                    .filter(node -> node.path("recordType").asText().startsWith("MODEL_"))
                    .forEach(node ->
                    {
                        String attemptId = node.path("metadata").path("attemptId").asText();
                        assertThat(attemptId).isNotBlank();
                        attemptLifecycle.computeIfAbsent(attemptId, ignored -> new ArrayList<>())
                                .add(node.path("recordType").asText());
                        if ("MODEL_RESPONSE_RECEIVED".equals(node.path("recordType").asText()))
                        {
                            sequenceAttempts
                                    .computeIfAbsent(
                                            node.path("metadata").path("retrySequenceId").asText(),
                                            ignored -> new ArrayList<>())
                                    .add(node.path("metadata").path("attemptNumber").asInt());
                            actualAttempts.add(expectedAttempt(
                                    node.path("metadata").path("retrySequenceId").asText(),
                                    attemptId,
                                    node.path("metadata").path("attemptNumber").asInt()));
                        }
                    });
            sequenceAttempts.values().forEach(numbers ->
                    assertThat(numbers).containsExactlyElementsOf(
                            Stream.iterate(1, number -> number + 1).limit(numbers.size()).toList()));
            attemptLifecycle.values().forEach(recordTypes -> assertThat(recordTypes).containsExactly(
                    TraceRecordType.MODEL_REQUEST_PREPARED.name(),
                    TraceRecordType.MODEL_REQUEST_SENT.name(),
                    TraceRecordType.MODEL_RESPONSE_RECEIVED.name()));

            JsonNode expected = JSON.readTree(
                    fixtureRoot().resolve("expected").resolve(name + ".json").toFile());
            JsonNode actualAttemptsNode = JSON.valueToTree(actualAttempts);
            assertThat(actualAttemptsNode).isEqualTo(expected.path("attempts"));
            List<Map<String, Object>> actualValidationLinks = new ArrayList<>();
            records.stream()
                    .filter(node -> node.path("recordType").asText().startsWith("ADVISOR_"))
                    .forEach(node ->
                    {
                        assertThat(node.at("/metadata/retrySequenceId").asText()).isNotBlank();
                        assertThat(node.at("/metadata/attemptId").asText()).isNotBlank();
                        assertThat(node.at("/metadata/attemptNumber").asInt()).isPositive();
                        actualValidationLinks.add(expectedValidationLink(
                                node.at("/metadata/status").asText(),
                                node.at("/metadata/retrySequenceId").asText(),
                                node.at("/metadata/attemptId").asText(),
                                node.at("/metadata/attemptNumber").asInt()));
                    });
            JsonNode actualValidationLinksNode = JSON.valueToTree(actualValidationLinks);
            assertThat(actualValidationLinksNode).isEqualTo(expected.path("validationLinks"));
        }
    }

    @Test
    @Order(4)
    void invalidFixturesHaveOneNamedExpectedClassification() throws Exception
    {
        for (Map.Entry<String, String> entry : INVALID.entrySet())
        {
            JsonNode expected = JSON.readTree(
                    fixtureRoot().resolve("expected").resolve(entry.getKey() + ".json").toFile());
            assertThat(expected.path("valid").asBoolean()).isFalse();
            assertThat(expected.path("errorCategory").asText()).isEqualTo(entry.getValue());
        }
    }

    @Test
    @Order(5)
    void unattributedUsageExpectedResultIsTerminalMinusAttributedResponses() throws Exception
    {
        JsonNode expected = JSON.readTree(
                fixtureRoot().resolve("expected/unattributed-usage.json").toFile());
        assertThat(expected.at("/unattributedUsage/promptUnits").asInt()).isEqualTo(
                expected.at("/terminalUsage/promptUnits").asInt()
                        - expected.at("/attributedUsage/promptUnits").asInt());
        assertThat(expected.at("/unattributedUsage/completionUnits").asInt()).isEqualTo(
                expected.at("/terminalUsage/completionUnits").asInt()
                        - expected.at("/attributedUsage/completionUnits").asInt());
    }

    private static void generate(Path root) throws Exception
    {
        Files.createDirectories(root.resolve("traces"));
        Files.createDirectories(root.resolve("expected"));
        for (String name : VALID)
        {
            generateValid(root, name);
        }
        generateInvalid(root);
    }

    private static void generateValid(Path root, String name) throws Exception
    {
        Path trace = root.resolve("traces").resolve(name + ".ndjson");
        AtomicInteger ids = new AtomicInteger();
        DefaultExecutionTraceHandle handle = new DefaultExecutionTraceHandle(
                "trace-" + name,
                "session-" + name,
                trace,
                TracePersistencePolicy.ALWAYS,
                CLOCK,
                () -> "payload-" + ids.incrementAndGet(),
                "fixture-thread",
                "traces/" + name + ".ndjson");

        Usage attributed = Usage.ZERO;
        Usage terminal = Usage.ZERO;
        String outcome = "SUCCEEDED";
        String terminalFailureId = null;

        switch (name)
        {
            case "single-attempt-success" ->
            {
                appendAttempt(handle, "retry-1", "attempt-1", 1, 10, 4, "EXACT");
                attributed = terminal = new Usage(10, 4);
            }
            case "terminal-failure" ->
            {
                appendAttempt(handle, "retry-1", "attempt-1", 1, 7, 2, "EXACT");
                terminalFailureId = "failure-terminal";
                handle.append(TraceRecordType.ERROR_RECORDED,
                        ordered("failureId", terminalFailureId, "terminal", true),
                        Map.of("message", "provider failed"));
                attributed = terminal = new Usage(7, 2);
                outcome = "FAILED";
            }
            case "terminal-abort" ->
            {
                terminalFailureId = "failure-abort";
                handle.append(TraceRecordType.ERROR_RECORDED,
                        ordered("failureId", terminalFailureId, "terminal", true),
                        Map.of("message", "interrupted"));
                outcome = "ABORTED";
            }
            case "advisor-retry" ->
            {
                appendAttempt(handle, "retry-1", "attempt-1", 1, 10, 4, "EXACT");
                handle.append(TraceRecordType.ADVISOR_REQUEST_MUTATION_RECORDED,
                        attempt("retry-1", "attempt-1", 1, Map.of("status", "retrying")),
                        Map.of("validator", "linter"));
                appendAttempt(handle, "retry-1", "attempt-2", 2, 8, 3, "EXACT");
                handle.append(TraceRecordType.ADVISOR_RESPONSE_MUTATION_RECORDED,
                        attempt("retry-1", "attempt-2", 2, Map.of("status", "passed")),
                        Map.of("validator", "linter"));
                attributed = terminal = new Usage(18, 7);
            }
            case "nested-retry-sequences" ->
            {
                appendAttempt(handle, "retry-outer", "attempt-outer-1", 1, 4, 2, "ESTIMATED");
                appendAttempt(handle, "retry-inner", "attempt-inner-1", 1, 5, 1, "EXACT");
                appendAttempt(handle, "retry-inner", "attempt-inner-2", 2, 3, 1, "EXACT");
                attributed = terminal = new Usage(12, 4);
            }
            case "validation-exhaustion" ->
            {
                appendAttempt(handle, "retry-1", "attempt-1", 1, 6, 2, "EXACT");
                handle.append(TraceRecordType.ADVISOR_REQUEST_MUTATION_RECORDED,
                        attempt("retry-1", "attempt-1", 1, Map.of("status", "retrying")),
                        Map.of("validator", "output-schema"));
                appendAttempt(handle, "retry-1", "attempt-2", 2, 5, 2, "EXACT");
                handle.append(TraceRecordType.ADVISOR_RESPONSE_MUTATION_RECORDED,
                        attempt("retry-1", "attempt-2", 2, Map.of("status", "exhausted")),
                        Map.of("validator", "output-schema"));
                terminalFailureId = "failure-validation";
                handle.append(TraceRecordType.ERROR_RECORDED,
                        ordered("failureId", terminalFailureId, "terminal", true),
                        Map.of("message", "validation exhausted"));
                attributed = terminal = new Usage(11, 4);
                outcome = "FAILED";
            }
            case "unavailable-usage" -> appendAttempt(
                    handle, "retry-1", "attempt-1", 1, 0, 0, "UNAVAILABLE");
            case "unattributed-usage" ->
            {
                appendAttempt(handle, "retry-1", "attempt-1", 1, 10, 4, "EXACT");
                attributed = new Usage(10, 4);
                terminal = new Usage(13, 6);
            }
            case "nonterminal-error-then-success" ->
            {
                handle.append(TraceRecordType.ERROR_RECORDED,
                        ordered("failureId", "failure-recovered", "terminal", false),
                        Map.of("message", "recoverable cleanup failure"));
                appendAttempt(handle, "retry-1", "attempt-1", 1, 5, 2, "EXACT");
                attributed = terminal = new Usage(5, 2);
            }
            case "chunked-payload" ->
            {
                handle.append(TraceRecordType.MODEL_REQUEST_PREPARED,
                        attempt("retry-1", "attempt-1", 1, Map.of()),
                        Map.of("messages", List.of("user")));
                handle.append(TraceRecordType.MODEL_REQUEST_SENT,
                        attempt("retry-1", "attempt-1", 1, Map.of()),
                        "x".repeat(5000));
                appendResponse(handle, "retry-1", "attempt-1", 1, 2, 1, "EXACT");
                attributed = terminal = new Usage(2, 1);
            }
            default -> throw new IllegalArgumentException(name);
        }

        Map<String, Object> completion = new LinkedHashMap<>();
        completion.put("outcome", outcome);
        completion.put("sessionUsageSnapshot", terminal.asMap());
        if (terminalFailureId != null)
        {
            completion.put("terminalFailureId", terminalFailureId);
            handle.markErrored();
        }
        handle.finalizeTrace(completion);
        writeExpected(root, name, validExpected(name, outcome, terminalFailureId, attributed, terminal));
    }

    private static void appendAttempt(
            DefaultExecutionTraceHandle handle,
            String retryId,
            String attemptId,
            int number,
            int prompt,
            int completion,
            String precision) throws IOException
    {
        Map<String, Object> metadata = attempt(retryId, attemptId, number, Map.of());
        handle.append(TraceRecordType.MODEL_REQUEST_PREPARED, metadata, Map.of("messages", List.of("user")));
        handle.append(TraceRecordType.MODEL_REQUEST_SENT, metadata, Map.of("messages", List.of("user")));
        appendResponse(handle, retryId, attemptId, number, prompt, completion, precision);
    }

    private static void appendResponse(
            DefaultExecutionTraceHandle handle,
            String retryId,
            String attemptId,
            int number,
            int prompt,
            int completion,
            String precision) throws IOException
    {
        Map<String, Object> usage = usage(prompt, completion, precision);
        handle.append(TraceRecordType.MODEL_RESPONSE_RECEIVED,
                attempt(retryId, attemptId, number, Map.of("usage", usage)),
                Map.of("content", "fixture response"));
    }

    private static Map<String, Object> attempt(
            String retryId,
            String attemptId,
            int number,
            Map<String, Object> extra)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retrySequenceId", retryId);
        result.put("attemptId", attemptId);
        result.put("attemptNumber", number);
        result.putAll(extra);
        return result;
    }

    private static Map<String, Object> usage(int prompt, int completion, String precision)
    {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("promptUnits", prompt);
        usage.put("completionUnits", completion);
        usage.put("totalUnits", prompt + completion);
        usage.put("precision", precision);
        return usage;
    }

    private static Map<String, Object> ordered(Object... keysAndValues)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2)
        {
            result.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return result;
    }

    private static Map<String, Object> validExpected(
            String name,
            String outcome,
            String terminalFailureId,
            Usage attributed,
            Usage terminal)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case", name);
        result.put("valid", true);
        result.put("traceId", "trace-" + name);
        result.put("sessionId", "session-" + name);
        result.put("outcome", outcome);
        result.put("terminalFailureId", terminalFailureId);
        result.put("attributedUsage", attributed.asMap());
        result.put("terminalUsage", terminal.asMap());
        result.put("unattributedUsage", terminal.minus(attributed).asMap());
        result.put("attempts", expectedAttempts(name));
        result.put("validationLinks", expectedValidationLinks(name));
        return result;
    }

    private static List<Map<String, Object>> expectedAttempts(String name)
    {
        return switch (name)
        {
            case "advisor-retry", "validation-exhaustion" -> List.of(
                    expectedAttempt("retry-1", "attempt-1", 1),
                    expectedAttempt("retry-1", "attempt-2", 2));
            case "nested-retry-sequences" -> List.of(
                    expectedAttempt("retry-outer", "attempt-outer-1", 1),
                    expectedAttempt("retry-inner", "attempt-inner-1", 1),
                    expectedAttempt("retry-inner", "attempt-inner-2", 2));
            case "terminal-abort", "nonterminal-error-then-success" ->
                    name.equals("terminal-abort")
                            ? List.of()
                            : List.of(expectedAttempt("retry-1", "attempt-1", 1));
            default -> List.of(expectedAttempt("retry-1", "attempt-1", 1));
        };
    }

    private static List<Map<String, Object>> expectedValidationLinks(String name)
    {
        return switch (name)
        {
            case "advisor-retry" -> List.of(
                    expectedValidationLink("retrying", "retry-1", "attempt-1", 1),
                    expectedValidationLink("passed", "retry-1", "attempt-2", 2));
            case "validation-exhaustion" -> List.of(
                    expectedValidationLink("retrying", "retry-1", "attempt-1", 1),
                    expectedValidationLink("exhausted", "retry-1", "attempt-2", 2));
            default -> List.of();
        };
    }

    private static Map<String, Object> expectedAttempt(
            String retrySequenceId,
            String attemptId,
            int attemptNumber)
    {
        return ordered(
                "retrySequenceId", retrySequenceId,
                "attemptId", attemptId,
                "attemptNumber", attemptNumber);
    }

    private static Map<String, Object> expectedValidationLink(
            String status,
            String retrySequenceId,
            String attemptId,
            int attemptNumber)
    {
        return ordered(
                "status", status,
                "retrySequenceId", retrySequenceId,
                "attemptId", attemptId,
                "attemptNumber", attemptNumber);
    }

    private static void generateInvalid(Path root) throws Exception
    {
        List<String> base = Files.readAllLines(
                root.resolve("traces/single-attempt-success.ndjson"), StandardCharsets.UTF_8);
        writeInvalid(root, "malformed-json", List.of("{not-json"));

        List<String> inconsistent = new ArrayList<>(base);
        inconsistent.set(1, inconsistent.get(1).replace(
                "\"sessionId\":\"session-single-attempt-success\"",
                "\"sessionId\":\"different-session\""));
        writeInvalid(root, "inconsistent-identities", inconsistent);

        List<String> duplicate = new ArrayList<>(base);
        duplicate.set(1, duplicate.get(1).replace("\"sequence\":2", "\"sequence\":1"));
        writeInvalid(root, "duplicate-sequence", duplicate);

        List<String> chunks = Files.readAllLines(root.resolve("traces/chunked-payload.ndjson"), StandardCharsets.UTF_8);
        List<String> incomplete = new ArrayList<>(chunks);
        incomplete.removeIf(line -> line.contains("\"recordType\":\"PAYLOAD_CHUNK_APPENDED\"")
                && line.contains("\"chunkIndex\":1"));
        writeInvalid(root, "incomplete-chunks", incomplete);

        writeInvalid(root, "missing-completion", base.subList(0, base.size() - 1));

        List<String> nonFinal = new ArrayList<>(base);
        JsonNode completion = JSON.readTree(nonFinal.getLast());
        long next = completion.path("sequence").asLong() + 1;
        nonFinal.add(base.getFirst()
                .replace("\"sequence\":1", "\"sequence\":" + next)
                .replace("\"recordType\":\"TRACE_STARTED\"", "\"recordType\":\"ERROR_RECORDED\""));
        writeInvalid(root, "non-final-completion", nonFinal);

        List<String> unsupported = new ArrayList<>(base);
        unsupported.set(unsupported.size() - 1,
                unsupported.getLast().replace("\"outcome\":\"SUCCEEDED\"", "\"outcome\":\"FUTURE\""));
        writeInvalid(root, "unsupported-enum", unsupported);

        List<String> contradictory = new ArrayList<>(base);
        contradictory.set(contradictory.size() - 1,
                contradictory.getLast()
                        .replace("\"promptUnits\":10", "\"promptUnits\":1")
                        .replace("\"totalUnits\":14", "\"totalUnits\":2"));
        writeInvalid(root, "contradictory-usage-reconciliation", contradictory);
    }

    private static void writeInvalid(Path root, String name, List<String> lines) throws Exception
    {
        Files.write(root.resolve("traces").resolve(name + ".ndjson"), lines, StandardCharsets.UTF_8);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("case", name);
        expected.put("valid", false);
        expected.put("errorCategory", INVALID.get(name));
        writeExpected(root, name, expected);
    }

    private static void writeExpected(Path root, String name, Map<String, Object> expected) throws Exception
    {
        String serialized = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(expected)
                .replace("\r\n", "\n") + "\n";
        Files.writeString(root.resolve("expected").resolve(name + ".json"), serialized, StandardCharsets.UTF_8);
    }

    private static List<JsonNode> parseLines(Path path) throws Exception
    {
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8))
        {
            nodes.add(JSON.readTree(line));
        }
        return nodes;
    }

    private static Path fixtureRoot()
    {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path direct = cwd.resolve("bifrost-console-fixtures");
        if (Files.isDirectory(direct) || Files.isDirectory(cwd.resolve("bifrost-spring-boot-starter")))
        {
            return direct;
        }
        return cwd.getParent().resolve("bifrost-console-fixtures");
    }

    private static List<String> fileNames(Path root) throws IOException
    {
        if (Files.notExists(root))
        {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root))
        {
            return files.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("README.md"))
                    .map(root::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private static void copyCorpus(Path source, Path target) throws IOException
    {
        Files.createDirectories(target.resolve("traces"));
        Files.createDirectories(target.resolve("expected"));
        for (String name : fileNames(source))
        {
            Path destination = target.resolve(name);
            Files.createDirectories(destination.getParent());
            Files.copy(source.resolve(name), destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        try (Stream<Path> existing = Files.walk(target))
        {
            for (Path path : existing.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("README.md"))
                    .sorted(Comparator.reverseOrder())
                    .toList())
            {
                String name = target.relativize(path).toString().replace('\\', '/');
                if (!fileNames(source).contains(name))
                {
                    Files.delete(path);
                }
            }
        }
    }

    private record Usage(int promptUnits, int completionUnits)
    {
        private static final Usage ZERO = new Usage(0, 0);

        private Map<String, Object> asMap()
        {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("promptUnits", promptUnits);
            result.put("completionUnits", completionUnits);
            result.put("totalUnits", promptUnits + completionUnits);
            return result;
        }

        private Usage minus(Usage other)
        {
            return new Usage(promptUnits - other.promptUnits, completionUnits - other.completionUnits);
        }
    }
}
