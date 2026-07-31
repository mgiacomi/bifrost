package browserapi

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"testing"
	"time"

	"github.com/mgiacomi/bifrost/bifrost-console/internal/artifact"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/consolecore"
)

type fixtureBootstrap struct {
	ProcessID     string    `json:"processId"`
	WorkspacePath string    `json:"workspacePath"`
	TabID         string    `json:"tabId"`
	CSRFToken     string    `json:"csrfToken"`
	Target        targetDTO `json:"target"`
}

func TestBrowserTargetFixtureCorpusMatchesCommittedInventoryByteForByte(t *testing.T) {
	observed := time.Date(2026, 7, 27, 0, 0, 0, 0, time.UTC)
	scope := "11111111-1111-4111-8111-111111111111"
	base := fixtureBootstrap{ProcessID: "process-1", WorkspacePath: `C:\workspace`, TabID: "tab-1", CSRFToken: "csrf-1"}
	noTarget := base
	noTarget.Target = targetDTO{Status: consolecore.NoTargetStatus(observed)}
	required := base
	required.Target = targetDTO{
		Address: "https://application.example/context",
		Status: consolecore.StatusSnapshot{
			ObservedAt: observed, TargetScopeID: scope, TargetSelection: consolecore.SelectionSelected,
			TargetConnection: consolecore.ConnectionUnknown, TargetAuthentication: consolecore.AuthenticationRequired,
			JavaGoCompatibility: consolecore.CompatibilityNotChecked,
			RuntimeIdentity:     consolecore.RuntimeNotEstablished, LiveMonitoring: consolecore.LiveUnknown,
		},
	}
	connected := required
	connected.Target.Status.TargetConnection = consolecore.ConnectionReachable
	connected.Target.Status.TargetAuthentication = consolecore.AuthenticationEstablished
	connected.Target.Status.JavaGoCompatibility = consolecore.CompatibilityCompatible
	connected.Target.Status.RuntimeIdentity = consolecore.RuntimeEstablished
	connected.Target.Status.InstanceID = "22222222-2222-4222-8222-222222222222"
	connected.Target.Status.LiveMonitoring = consolecore.LiveAvailable

	expected := map[string]any{
		"bootstrap-no-target.json":               noTarget,
		"bootstrap-authentication-required.json": required,
		"bootstrap-connected.json":               connected,
		"error-authentication-required.json": errorEnvelope{Error: browserError{
			Code: "TARGET_AUTHENTICATION_REQUIRED", Message: "The application key was rejected.", TargetScopeID: scope,
		}},
		"error-access-blocked.json": errorEnvelope{Error: browserError{
			Code: "TARGET_ACCESS_BLOCKED", Message: "The selected target denied access before Bifrost authentication.", TargetScopeID: scope,
		}},
		"error-unavailable.json": errorEnvelope{Error: browserError{
			Code: "TARGET_UNAVAILABLE", Message: "The selected target is unavailable.", TargetScopeID: scope,
			Details: consolecore.Details{TransportCategory: "timeout"},
		}},
		"error-incompatible.json": errorEnvelope{Error: browserError{
			Code: "INCOMPATIBLE_TARGET", Message: "The selected target uses a different Bifrost release.", TargetScopeID: scope,
			Details: consolecore.Details{ExpectedCompatibilityVersion: "0.1.0-SNAPSHOT", ObservedCompatibilityVersion: "0.1.0"},
		}},
		"error-target-changed.json": errorEnvelope{Error: browserError{
			Code: "TARGET_CHANGED", Message: "The selected target changed. Start this operation again.", TargetScopeID: scope,
			Details: consolecore.Details{CurrentTargetScopeID: "33333333-3333-4333-8333-333333333333"},
		}},
	}
	root := filepath.Join("..", "..", "browser-fixtures", "target")
	assertFixtureCorpus(t, root, expected)
}

// TestBrowserArtifactFixtureCorpusMatchesCommittedInventoryByteForByte proves
// that the committed artifact fixture JSON files in
// browser-fixtures/artifacts/ match the Go DTOs byte-for-byte. This covers the
// Phase 3 acquire-response and storage-snapshot DTOs, ensuring the wire
// contract is stable and the TypeScript contracts.ts types match the real
// backend response shape.
func TestBrowserArtifactFixtureCorpusMatchesCommittedInventoryByteForByte(t *testing.T) {
	scope := "11111111-1111-4111-8111-111111111111"
	finalizedAt := time.Date(2026, 7, 27, 10, 10, 0, 0, time.UTC)
	acquiredAt := time.Date(2026, 7, 27, 10, 15, 0, 0, time.UTC)
	expiresAt := time.Date(2026, 7, 27, 10, 20, 0, 0, time.UTC)
	appExpiresAt := time.Date(2026, 7, 27, 11, 10, 0, 0, time.UTC)

	expected := map[string]any{
		"acquire-response.json": acquiredArtifactDTO{
			Handle:        "01-handletoken",
			TraceID:       "trace-1",
			SessionID:     "session-1",
			Outcome:       "SUCCEEDED",
			FinalizedAt:   finalizedAt,
			LocalBytes:    4096,
			AcquiredAt:    acquiredAt,
			LastUsedAt:    acquiredAt,
			ExpiresAt:     expiresAt,
			HasIdleExpiry: true,
		},
		"storage-snapshot.json": storageSnapshotDTO{
			TargetScopeID:  scope,
			WorkspaceLabel: "work",
			MaxBytes:       1048576,
			Unlimited:      false,
			IdleTTL:        "5m0s",
			NeverExpire:    false,
			ChargedBytes:   4096,
			AcquiredCount:  1,
			Entries: []artifact.StoredEntry{
				{
					TraceID:                   "trace-1",
					SessionID:                 "session-1",
					Outcome:                   "SUCCEEDED",
					PersistencePolicy:         "RETAINED",
					FinalizedAt:               finalizedAt,
					AcquiredAt:                acquiredAt,
					LastUsedAt:                acquiredAt,
					ExpiresAt:                 expiresAt,
					HasIdleExpiry:             true,
					LocalBytes:                4096,
					ApplicationTraceExpiresAt: appExpiresAt,
					ApplicationAvailability:   "AVAILABLE",
					LocalAvailable:            true,
					ActivePin:                 false,
				},
			},
		},
	}
	root := filepath.Join("..", "..", "browser-fixtures", "artifacts")
	assertFixtureCorpus(t, root, expected)
}

// assertFixtureCorpus verifies that every file in root matches a Go value in
// expected, marshaled to JSON with a trailing newline. It checks that the
// directory contains exactly the expected files (no more, no fewer) and that
// each file's bytes match the marshaled DTO exactly.
func assertFixtureCorpus(t *testing.T, root string, expected map[string]any) {
	t.Helper()
	entries, err := os.ReadDir(root)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, entry := range entries {
		if !entry.IsDir() {
			names = append(names, entry.Name())
		}
	}
	sort.Strings(names)
	expectedNames := make([]string, 0, len(expected))
	for name := range expected {
		expectedNames = append(expectedNames, name)
	}
	sort.Strings(expectedNames)
	if !equalStrings(names, expectedNames) {
		t.Fatalf("fixture inventory=%v expected=%v", names, expectedNames)
	}
	for name, value := range expected {
		generated, err := json.Marshal(value)
		if err != nil {
			t.Fatal(err)
		}
		generated = append(generated, '\n')
		committed, err := os.ReadFile(filepath.Join(root, name))
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(generated, committed) {
			t.Errorf("%s differs\nwant %s\ngot  %s", name, generated, committed)
		}
	}
}

func equalStrings(left, right []string) bool {
	if len(left) != len(right) {
		return false
	}
	for index := range left {
		if left[index] != right[index] {
			return false
		}
	}
	return true
}
