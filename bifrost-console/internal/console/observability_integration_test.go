package console

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/mgiacomi/bifrost/bifrost-console/internal/applicationclient"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/observability"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/target"
)

func TestObservabilityRoutesDoNotLeakApplicationCredential(t *testing.T) {
	secret := "BIFROST_" + "TEST_APPLICATION_KEY_DO_NOT_LEAK_9a2c"
	var received []string
	fixture, err := os.ReadFile(filepath.Join("..", "..", "..", "bifrost-console-fixtures", "application-rest", "skills-page.json"))
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		received = append(received, request.Header.Values(applicationclient.APIKeyHeader)...)
		response.Header().Set("Content-Type", "application/json")
		response.Header().Set(applicationclient.InstanceIDHeader, "11111111-1111-4111-8111-111111111111")
		if strings.HasSuffix(request.URL.Path, "/instance") {
			_, _ = response.Write([]byte(`{"instanceId":"11111111-1111-4111-8111-111111111111","consoleCompatibilityVersion":"0.1.0-SNAPSHOT","observedAt":"2026-07-25T12:00:00Z","liveMonitoringAvailable":true,"registeredSkillCount":1,"activeExecutionCount":0,"catalogedTraceCount":0,"tracePersistencePolicy":"PERSISTENT","completionGraceTtl":"PT2M","traceCatalogMetadataTtl":"PT168H"}`))
			return
		}
		_, _ = response.Write(fixture)
	}))
	defer server.Close()
	policy := applicationclient.NetworkPolicy{
		ConnectTimeout: time.Second, ResponseHeaderTimeout: time.Second, RequestTimeout: time.Second,
	}
	targetContext, err := target.New(func(address applicationclient.Address) (target.ProbeClient, error) {
		return applicationclient.New(address, policy, "0.1.0-SNAPSHOT")
	}, nil, time.Now)
	if err != nil {
		t.Fatal(err)
	}
	defer targetContext.Close()
	if err := targetContext.Select(server.URL); err != nil {
		t.Fatal(err)
	}
	if _, domain := targetContext.SupplyCredential(context.Background(), []byte(secret)); domain != nil {
		t.Fatal(domain)
	}
	service := observability.New()
	scope, domain := targetContext.Capture()
	if domain != nil {
		t.Fatal(domain)
	}
	page, domain := service.ListSkills(context.Background(), scope, observability.ListRequest{})
	if domain != nil {
		t.Fatal(domain)
	}
	encoded, err := json.Marshal(page)
	if err != nil {
		t.Fatal(err)
	}
	rendered := string(encoded) + fmt.Sprintf("%#v", page)
	if strings.Contains(rendered, secret) {
		t.Fatal("credential escaped into observability DTO or formatted output")
	}
	for _, hdr := range received {
		if hdr != secret {
			t.Fatalf("unexpected header value: %q", hdr)
		}
	}
	if len(received) == 0 {
		t.Fatal("credential was not sent on any upstream request")
	}
}
