package target

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/mgiacomi/bifrost/bifrost-console/internal/applicationclient"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/consolecore"
)

type fakeUpstreamClient struct {
	body       []byte
	instanceID string
	err        error
	ctxCh      chan context.Context
	blockOnCtx bool
}

func (c *fakeUpstreamClient) Probe(context.Context, applicationclient.Credential) (applicationclient.Instance, error) {
	return applicationclient.Instance{InstanceID: "11111111-1111-4111-8111-111111111111"}, nil
}
func (c *fakeUpstreamClient) Get(ctx context.Context, _ string, _ int64, _ applicationclient.Credential) ([]byte, string, error) {
	if c.ctxCh != nil {
		c.ctxCh <- ctx
	}
	if c.blockOnCtx {
		<-ctx.Done()
		return nil, "", ctx.Err()
	}
	return c.body, c.instanceID, c.err
}
func (*fakeUpstreamClient) Close() {}

func TestScopeUpstreamAppliesCredentialsAndDetectsIdentityMismatch(t *testing.T) {
	client := &fakeUpstreamClient{
		body:       []byte(`{"items":[]}`),
		instanceID: "22222222-2222-4222-8222-222222222222",
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	scope := Scope{
		ID:         "scope-1",
		InstanceID: "11111111-1111-4111-8111-111111111111",
		client:     client,
		credential: testCredentialValue(),
		Context:    ctx,
	}

	_, domain := scope.Upstream(context.Background(), "http://127.0.0.1:8080/test", 1024)
	if domain == nil {
		t.Fatal("expected identity mismatch error")
	}
	if domain.Code != consolecore.CodeTargetChanged {
		t.Fatalf("expected TARGET_CHANGED, got %s", domain.Code)
	}
}

func TestScopeUpstreamReturnsBodyOnMatchingInstanceID(t *testing.T) {
	client := &fakeUpstreamClient{
		body:       []byte(`{"items":[]}`),
		instanceID: "11111111-1111-4111-8111-111111111111",
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	scope := Scope{
		ID:         "scope-1",
		InstanceID: "11111111-1111-4111-8111-111111111111",
		client:     client,
		credential: testCredentialValue(),
		Context:    ctx,
	}

	body, domain := scope.Upstream(context.Background(), "http://127.0.0.1:8080/test", 1024)
	if domain != nil {
		t.Fatalf("unexpected error: %v", domain)
	}
	if string(body) != `{"items":[]}` {
		t.Fatalf("unexpected body: %s", body)
	}
}

func TestScopeUpstreamMapsScopeCancellationToTargetChanged(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	client := &fakeUpstreamClient{
		err: context.Canceled,
	}
	scope := Scope{
		ID:         "scope-1",
		InstanceID: "11111111-1111-4111-8111-111111111111",
		client:     client,
		credential: testCredentialValue(),
		Context:    ctx,
	}
	cancel()
	_, domain := scope.Upstream(context.Background(), "http://127.0.0.1:8080/test", 1024)
	if domain == nil || domain.Code != consolecore.CodeTargetChanged {
		t.Fatalf("expected TARGET_CHANGED for scope cancellation, got %v", domain)
	}
}

func TestScopeUpstreamMapsCallerCancellationToTargetUnavailable(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	client := &fakeUpstreamClient{
		err: context.Canceled,
	}
	scope := Scope{
		ID:         "scope-1",
		InstanceID: "11111111-1111-4111-8111-111111111111",
		client:     client,
		credential: testCredentialValue(),
		Context:    ctx,
	}
	callerCtx, callerCancel := context.WithCancel(context.Background())
	callerCancel()
	_, domain := scope.Upstream(callerCtx, "http://127.0.0.1:8080/test", 1024)
	if domain == nil || domain.Code != consolecore.CodeTargetUnavailable {
		t.Fatalf("expected TARGET_UNAVAILABLE for caller cancellation, got %v", domain)
	}
}

func TestScopeUpstreamMapsFailureToConsoleError(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	client := &fakeUpstreamClient{
		err: &applicationclient.Failure{Kind: applicationclient.FailureNotFound},
	}
	scope := Scope{
		ID:         "scope-1",
		InstanceID: "11111111-1111-4111-8111-111111111111",
		client:     client,
		credential: testCredentialValue(),
		Context:    ctx,
	}
	_, domain := scope.Upstream(context.Background(), "http://127.0.0.1:8080/test", 1024)
	if domain == nil || domain.Code != consolecore.CodeNotFound {
		t.Fatalf("expected NOT_FOUND, got %v", domain)
	}
}

type scopeTestCredential []byte

func (c scopeTestCredential) Apply(request *http.Request) error {
	request.Header.Set(applicationclient.APIKeyHeader, string(c))
	return nil
}

func testCredentialValue() applicationclient.Credential {
	return scopeTestCredential(strings.Repeat("k", 32))
}

func TestScopeUpstreamRejectsUnestablishedScope(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	scope := Scope{
		ID:         "scope-1",
		InstanceID: "11111111-1111-4111-8111-111111111111",
		client:     nil,
		credential: nil,
		Context:    ctx,
	}
	_, domain := scope.Upstream(context.Background(), "http://127.0.0.1:8080/test", 1024)
	if domain == nil {
		t.Fatal("expected error for unestablished scope")
	}
	if domain.Code != consolecore.CodeTargetAuthentication {
		t.Fatalf("expected TARGET_AUTHENTICATION_REQUIRED, got %s", domain.Code)
	}
}

func TestScopeUpstreamContextCombinesParentAndScope(t *testing.T) {
	ctxCh := make(chan context.Context, 1)
	client := &fakeUpstreamClient{
		body:       []byte(`{}`),
		instanceID: "11111111-1111-4111-8111-111111111111",
		ctxCh:      ctxCh,
		blockOnCtx: true,
	}
	scopeCtx, scopeCancel := context.WithCancel(context.Background())
	scope := Scope{
		ID:         "scope-1",
		InstanceID: "11111111-1111-4111-8111-111111111111",
		client:     client,
		credential: testCredentialValue(),
		Context:    scopeCtx,
	}
	parentCtx, parentCancel := context.WithCancel(context.Background())
	defer parentCancel()

	done := make(chan struct{})
	go func() {
		scope.Upstream(parentCtx, "http://127.0.0.1:8080/test", 1024)
		close(done)
	}()

	var receivedCtx context.Context
	select {
	case receivedCtx = <-ctxCh:
	case <-time.After(time.Second):
		t.Fatal("expected context to be passed to client.Get")
	}

	if receivedCtx == nil {
		t.Fatal("expected non-nil context")
	}

	scopeCancel()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("expected Upstream to return after scope cancellation")
	}

	if !errors.Is(receivedCtx.Err(), context.Canceled) {
		t.Fatalf("expected received context to be canceled after scope cancel, got %v", receivedCtx.Err())
	}
	if parentCtx.Err() != nil {
		t.Fatal("expected parent context to still be active")
	}
}
