# Bifrost Console — Agent Notes

## Verification commands

### Standard (no race detector)
```text
go test ./...
go run ./internal/buildtool verify
```

### Race detector (requires gcc/cgo)
The `-race` flag requires `CGO_ENABLED=1` and a C compiler. On this Windows machine, gcc is available via MSYS2 but is not on the default PATH. Prefix the command with the mingw64 bin directory:

```powershell
$env:PATH = "C:\msys64\mingw64\bin;" + $env:PATH
$env:CGO_ENABLED = "1"
go test -race ./...
```

### Java fixture corpus
The fixture corpus test in `bifrost-spring-boot-starter` validates that the Go trace-analysis processor matches the Java reference implementation byte-for-byte:

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter test -Dtest=ConsoleTraceFixtureCorpusTest -DfailIfNoTests=false
```

To regenerate committed fixtures (after intentionally changing the corpus):

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter test -Dtest=ConsoleTraceFixtureCorpusTest -Dbifrost.console.fixtures.regenerate=true -DfailIfNoTests=false
```

## Line endings

All committed fixture files (`bifrost-console-fixtures/traces/*.ndjson`, `bifrost-console-fixtures/expected/*.json`) and this repo's `README.md` use LF line endings. The Java test's `writeExpected` and `writeInvalid` helpers normalize to LF explicitly to avoid CRLF on Windows.
