package browserapi

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"testing"
	"time"

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
