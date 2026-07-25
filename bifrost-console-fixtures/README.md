# Bifrost Console Trace Fixtures

This directory is the current-release Java-to-Go semantic contract for execution traces. The trace format itself is an ephemeral diagnostic format: these fixtures describe the current checkout and do not promise that older trace files remain readable.

`traces/` contains ten valid traces and eight deliberately invalid artifacts. `expected/` contains only semantic results needed by future Console analysis: identity, outcome, terminal failure, physical-attempt grouping, validation-to-attempt links, attributed and terminal usage, the derived unattributed remainder, or one invalidity category. It intentionally contains no UI model or diagnosis.

The Java test generates valid cases through `DefaultExecutionTraceHandle`; invalid cases are minimal named mutations. Normal tests generate into a temporary directory and byte-compare the complete inventory:

```text
mvn -pl bifrost-spring-boot-starter -Dtest=ConsoleTraceFixtureCorpusTest test
```

Regenerate intentionally with:

```text
mvn -pl bifrost-spring-boot-starter -Dtest=ConsoleTraceFixtureCorpusTest -Dbifrost.console.fixtures.regenerate=true test
```

Run regeneration twice and require the second run to produce no diff. PR 06 will stream this same corpus as artifacts, and PR 13 will consume these expected results from Go; neither should copy it elsewhere.
