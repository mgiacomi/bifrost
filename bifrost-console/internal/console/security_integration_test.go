package console

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"testing/fstest"
	"time"

	"github.com/mgiacomi/bifrost/bifrost-console/internal/profile"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/workspace"
)

type lineWriter struct {
	mu    sync.Mutex
	lines chan string
}

func (writer *lineWriter) Write(content []byte) (int, error) {
	writer.mu.Lock()
	defer writer.mu.Unlock()
	writer.lines <- string(content)
	return len(content), nil
}

func TestLiveConsolePairsBootstrapsAndReleasesLocks(t *testing.T) {
	root := t.TempDir()
	configPath := filepath.Join(root, "profile", "config.yaml")
	workPath := filepath.Join(root, "work")
	output := &lineWriter{lines: make(chan string, 8)}
	context, cancel := context.WithCancel(context.Background())
	result := make(chan error, 1)
	go func() {
		result <- Run(context, Options{
			ConfigPath: configPath, WorkDirectory: workPath,
			ListenOverride: "127.0.0.1:0", NoOpenBrowser: true,
		}, Dependencies{
			Files: fstest.MapFS{
				"index.html":             {Data: []byte("<main>Bifrost</main>")},
				"assets/app-12345678.js": {Data: []byte("export{}")},
			},
			Output: output,
		})
	}()

	var pairingURL string
	timeout := time.After(5 * time.Second)
	for pairingURL == "" {
		select {
		case line := <-output.lines:
			if strings.HasPrefix(line, "Pairing URL: ") {
				pairingURL = strings.TrimSpace(strings.TrimPrefix(line, "Pairing URL: "))
			}
		case <-timeout:
			t.Fatal("pairing URL was not printed")
		}
	}
	parsed, err := url.Parse(pairingURL)
	if err != nil {
		t.Fatal(err)
	}
	secret := strings.TrimPrefix(parsed.Fragment, "/pair/")
	origin := "http://" + parsed.Host

	exchangeRequest, _ := http.NewRequest(http.MethodPost, origin+"/api/console/v1/pairing/exchange", strings.NewReader(`{"secret":"`+secret+`"}`))
	exchangeRequest.Header.Set("Origin", origin)
	exchange, err := http.DefaultClient.Do(exchangeRequest)
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, exchange.Body)
	exchange.Body.Close()
	if exchange.StatusCode != http.StatusOK || len(exchange.Cookies()) != 1 {
		t.Fatalf("exchange status=%d cookies=%v", exchange.StatusCode, exchange.Cookies())
	}

	bootstrapRequest, _ := http.NewRequest(http.MethodPost, origin+"/api/console/v1/bootstrap", strings.NewReader(`{}`))
	bootstrapRequest.Header.Set("Origin", origin)
	bootstrapRequest.AddCookie(exchange.Cookies()[0])
	bootstrap, err := http.DefaultClient.Do(bootstrapRequest)
	if err != nil {
		t.Fatal(err)
	}
	var state map[string]string
	if err := json.NewDecoder(bootstrap.Body).Decode(&state); err != nil {
		t.Fatal(err)
	}
	bootstrap.Body.Close()
	if bootstrap.StatusCode != http.StatusOK || state["workspacePath"] != workPath ||
		state["tabId"] == "" || state["csrfToken"] == "" {
		t.Fatalf("bootstrap status=%d state=%v", bootstrap.StatusCode, state)
	}

	cancel()
	select {
	case err := <-result:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("service did not shut down")
	}

	reopenedProfile, err := profile.Open(configPath)
	if err != nil {
		t.Fatalf("profile lock was not released: %v", err)
	}
	reopenedProfile.Close()
	reopenedWorkspace, err := workspace.Open(workPath)
	if err != nil {
		t.Fatalf("workspace lock was not released: %v", err)
	}
	reopenedWorkspace.Close()
}
