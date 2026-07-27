package console

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/mgiacomi/bifrost/bifrost-console/internal/applicationclient"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/target"
)

func TestApplicationCredentialNeverAppearsOutsideSelectedRequestHeader(t *testing.T) {
	secret := "BIFROST_" + "TEST_APPLICATION_KEY_DO_NOT_LEAK_7f63"
	var received []string
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		received = append(received, request.Header.Values(applicationclient.APIKeyHeader)...)
		response.Header().Set(applicationclient.InstanceIDHeader, "11111111-1111-4111-8111-111111111111")
		_, _ = response.Write([]byte(`{"instanceId":"11111111-1111-4111-8111-111111111111","consoleCompatibilityVersion":"0.1.0-SNAPSHOT","observedAt":"2026-07-25T12:00:00Z","liveMonitoringAvailable":true}`))
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
	if err := targetContext.Select(server.URL); err != nil {
		t.Fatal(err)
	}
	snapshot, domain := targetContext.SupplyCredential(context.Background(), []byte(secret))
	if domain != nil {
		t.Fatal(domain)
	}
	encoded, err := json.Marshal(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	rendered := string(encoded) + fmt.Sprintf("%#v", targetContext)
	if strings.Contains(rendered, secret) {
		t.Fatal("credential escaped into snapshot or formatted target context")
	}
	if len(received) != 1 || received[0] != secret {
		t.Fatal("credential was not confined to exactly one selected request header")
	}
	targetContext.Close()
}
