package browserapi

import (
	"net/http/httptest"
	"testing"
)

func TestBrowserPolicyAcceptsOnlyActualProductionAuthorityAndOrigin(t *testing.T) {
	policy, err := NewPolicy("127.0.0.1:7943", "http://127.0.0.1:7943", "")
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest("POST", "http://127.0.0.1:7943/api/console/v1/bootstrap", nil)
	request.Host = "127.0.0.1:7943"
	request.Header.Set("Origin", "http://127.0.0.1:7943")
	if !policy.ValidateHost(request) || !policy.ValidateOrigin(request) {
		t.Fatal("exact production request rejected")
	}
	for _, host := range []string{"localhost:7943", "127.0.0.1:7944", "127.0.0.1:7943,evil"} {
		request.Host = host
		if policy.ValidateHost(request) {
			t.Fatalf("host %q accepted", host)
		}
	}
}

func TestDevelopmentPolicyAddsOnlyConfiguredViteAuthorityOriginPair(t *testing.T) {
	policy, err := NewPolicy("127.0.0.1:7943", "http://127.0.0.1:7943", "http://127.0.0.1:5173")
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest("POST", "http://127.0.0.1:5173/api/console/v1/bootstrap", nil)
	request.Host = "127.0.0.1:5173"
	request.Header.Set("Origin", "http://127.0.0.1:5173")
	if !policy.ValidateHost(request) || !policy.ValidateOrigin(request) {
		t.Fatal("exact development pair rejected")
	}
	request.Header.Set("Origin", "http://127.0.0.1:7943")
	if policy.ValidateOrigin(request) {
		t.Fatal("cross-paired origin accepted")
	}
}

func TestParseLoopbackOriginRejectsBroadOrAmbiguousValues(t *testing.T) {
	for _, value := range []string{"https://127.0.0.1:1", "http://localhost:1", "http://0.0.0.0:1", "http://127.0.0.1", "http://127.0.0.1:1/", " http://127.0.0.1:1", "http://127.0.0.1:1/path", "http://user@127.0.0.1:1"} {
		if _, _, err := ParseLoopbackOrigin(value); err == nil {
			t.Fatalf("%q accepted", value)
		}
	}
}
