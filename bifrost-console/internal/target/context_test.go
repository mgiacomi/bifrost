package target

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/mgiacomi/bifrost/bifrost-console/internal/applicationclient"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/consolecore"
)

type fakeClient struct {
	mu       sync.Mutex
	instance applicationclient.Instance
	err      error
	started  chan struct{}
	release  chan struct{}
}

func (client *fakeClient) Probe(context.Context, applicationclient.Credential) (applicationclient.Instance, error) {
	if client.started != nil {
		close(client.started)
		<-client.release
	}
	client.mu.Lock()
	defer client.mu.Unlock()
	return client.instance, client.err
}
func (*fakeClient) Close() {}

func fixedInstance(id string) applicationclient.Instance {
	return applicationclient.Instance{
		InstanceID: id, ConsoleCompatibilityVersion: "0.1.0-SNAPSHOT",
		ObservedAt: time.Now(), LiveMonitoringAvailable: true,
	}
}

func TestContextCreatesOrRotatesScopeOnlyForAuthoritativeChanges(t *testing.T) {
	ids := []ScopeID{"scope-1", "scope-2", "scope-3"}
	client := &fakeClient{instance: fixedInstance("11111111-1111-4111-8111-111111111111")}
	targetContext, _ := New(func(applicationclient.Address) (ProbeClient, error) { return client, nil },
		func() (ScopeID, error) { id := ids[0]; ids = ids[1:]; return id, nil }, time.Now)
	if err := targetContext.Select("http://127.0.0.1:8080"); err != nil {
		t.Fatal(err)
	}
	initial := targetContext.Snapshot().Status.TargetScopeID
	if _, err := targetContext.SupplyCredential(context.Background(), []byte(strings.Repeat("k", 32))); err != nil {
		t.Fatal(err)
	}
	if targetContext.Snapshot().Status.TargetScopeID != initial {
		t.Fatal("first credential rotated the selected scope")
	}
	if _, err := targetContext.SupplyCredential(context.Background(), []byte(strings.Repeat("k", 32))); err != nil {
		t.Fatal(err)
	}
	if targetContext.Snapshot().Status.TargetScopeID == initial {
		t.Fatal("replacement credential did not rotate")
	}
}

func TestLateProbeResultCannotCommitReturnOrPublishAfterScopeRotation(t *testing.T) {
	oldClient := &fakeClient{
		instance: fixedInstance("11111111-1111-4111-8111-111111111111"),
		started:  make(chan struct{}), release: make(chan struct{}),
	}
	newClient := &fakeClient{instance: fixedInstance("22222222-2222-4222-8222-222222222222")}
	calls := 0
	targetContext, _ := New(func(applicationclient.Address) (ProbeClient, error) {
		calls++
		if calls == 1 {
			return oldClient, nil
		}
		return newClient, nil
	}, func() func() (ScopeID, error) {
		ids := []ScopeID{"scope-1", "scope-2"}
		return func() (ScopeID, error) { id := ids[0]; ids = ids[1:]; return id, nil }
	}(), time.Now)
	if err := targetContext.Select("http://127.0.0.1:8080"); err != nil {
		t.Fatal(err)
	}
	result := make(chan *consolecore.Error, 1)
	go func() {
		_, domain := targetContext.SupplyCredential(context.Background(), []byte(strings.Repeat("a", 32)))
		result <- domain
	}()
	<-oldClient.started
	if err := targetContext.Select("http://127.0.0.1:8081"); err != nil {
		t.Fatal(err)
	}
	close(oldClient.release)
	if domain := <-result; domain == nil || domain.Code != consolecore.CodeTargetChanged {
		t.Fatalf("late result was not rejected: %#v", domain)
	}
	if targetContext.Snapshot().Status.InstanceID != "" {
		t.Fatal("late result committed an identity")
	}
}

func TestConcurrentMutationsProbeOnlyTheirCommittedScope(t *testing.T) {
	ids := []ScopeID{"scope-a", "scope-b"}
	client := &fakeClient{
		instance: fixedInstance("11111111-1111-4111-8111-111111111111"),
	}
	targetContext, _ := New(
		func(applicationclient.Address) (ProbeClient, error) { return client, nil },
		func() (ScopeID, error) {
			id := ids[0]
			ids = ids[1:]
			return id, nil
		},
		time.Now,
	)
	defer targetContext.Close()

	type operationResult struct {
		snapshot Snapshot
		domain   *consolecore.Error
	}
	firstResult := make(chan operationResult, 1)
	secondResult := make(chan operationResult, 1)
	targetContext.probeMu.Lock()
	probeLockHeld := true
	defer func() {
		if probeLockHeld {
			targetContext.probeMu.Unlock()
		}
	}()
	go func() {
		snapshot, domain := targetContext.SelectAndConnect(
			context.Background(),
			"http://127.0.0.1:8081",
			[]byte(strings.Repeat("a", 32)),
		)
		firstResult <- operationResult{snapshot: snapshot, domain: domain}
	}()
	waitForSelectedAddress(t, targetContext, "http://127.0.0.1:8081")
	go func() {
		snapshot, domain := targetContext.SelectAndConnect(
			context.Background(),
			"http://127.0.0.1:8082",
			[]byte(strings.Repeat("b", 32)),
		)
		secondResult <- operationResult{snapshot: snapshot, domain: domain}
	}()
	waitForSelectedAddress(t, targetContext, "http://127.0.0.1:8082")
	targetContext.probeMu.Unlock()
	probeLockHeld = false

	first := <-firstResult
	if first.domain == nil || first.domain.Code != consolecore.CodeTargetChanged {
		t.Fatalf("superseded mutation returned another scope's result: snapshot=%#v error=%#v", first.snapshot, first.domain)
	}
	second := <-secondResult
	if second.domain != nil || second.snapshot.Address != "http://127.0.0.1:8082" ||
		second.snapshot.Status.TargetScopeID != "scope-b" {
		t.Fatalf("current mutation did not return its committed scope: snapshot=%#v error=%#v", second.snapshot, second.domain)
	}
}

func waitForSelectedAddress(t *testing.T, targetContext *Context, expected string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		targetContext.mu.Lock()
		current := targetContext.current
		matches := current != nil && current.address.String() == expected
		targetContext.mu.Unlock()
		if matches {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("target address %q was not selected", expected)
}

func TestContextEstablishesFirstIdentityWithoutRotationAndRotatesChangedIdentity(t *testing.T) {
	client := &fakeClient{instance: fixedInstance("11111111-1111-4111-8111-111111111111")}
	ids := []ScopeID{"scope-1", "scope-2"}
	targetContext, _ := New(func(applicationclient.Address) (ProbeClient, error) { return client, nil },
		func() (ScopeID, error) { id := ids[0]; ids = ids[1:]; return id, nil }, time.Now)
	if err := targetContext.Select("http://127.0.0.1:8080"); err != nil {
		t.Fatal(err)
	}
	initial := targetContext.Snapshot().Status.TargetScopeID
	if _, domain := targetContext.SupplyCredential(context.Background(), []byte(strings.Repeat("a", 32))); domain != nil {
		t.Fatal(domain)
	}
	if targetContext.Snapshot().Status.TargetScopeID != initial {
		t.Fatal("first identity rotated")
	}
	client.mu.Lock()
	client.instance = fixedInstance("22222222-2222-4222-8222-222222222222")
	client.mu.Unlock()
	if _, domain := targetContext.Recheck(context.Background()); domain != nil {
		t.Fatal(domain)
	}
	snapshot := targetContext.Snapshot()
	if snapshot.Status.TargetScopeID == initial || snapshot.Status.InstanceID != "22222222-2222-4222-8222-222222222222" {
		t.Fatalf("changed identity did not rotate atomically: %#v", snapshot)
	}
}

type ownerRecorder struct {
	name  string
	order *[]string
}

func (owner ownerRecorder) InvalidateTargetScope(ScopeID, context.Context) {
	*owner.order = append(*owner.order, owner.name)
}

func TestRotationInvalidatesOwnersInRegistrationOrder(t *testing.T) {
	ids := []ScopeID{"scope-1", "scope-2"}
	targetContext, _ := New(func(applicationclient.Address) (ProbeClient, error) { return &fakeClient{}, nil },
		func() (ScopeID, error) { id := ids[0]; ids = ids[1:]; return id, nil }, time.Now)
	var order []string
	if err := targetContext.RegisterOwner("skills", ownerRecorder{"skills", &order}); err != nil {
		t.Fatal(err)
	}
	if err := targetContext.RegisterOwner("artifacts", ownerRecorder{"artifacts", &order}); err != nil {
		t.Fatal(err)
	}
	if err := targetContext.Select("http://127.0.0.1:8080"); err != nil {
		t.Fatal(err)
	}
	if err := targetContext.Select("http://127.0.0.1:8081"); err != nil {
		t.Fatal(err)
	}
	if strings.Join(order, ",") != "skills,artifacts" {
		t.Fatalf("owner order=%v", order)
	}
}

type fakeTimer struct {
	callback func()
	stopped  bool
}

func (timer *fakeTimer) Stop() bool {
	timer.stopped = true
	return true
}

func TestRetryCoordinatorUsesOneTimerAndBoundedSchedule(t *testing.T) {
	failure := &applicationclient.Failure{
		Kind: applicationclient.FailureUnavailable, Category: applicationclient.CategoryConnection, Retryable: true,
	}
	client := &fakeClient{err: failure}
	targetContext, _ := New(func(applicationclient.Address) (ProbeClient, error) { return client, nil },
		func() (ScopeID, error) { return "scope-1", nil }, time.Now)
	var delays []time.Duration
	var timers []*fakeTimer
	targetContext.timerFactory = func(delay time.Duration, callback func()) timerHandle {
		delays = append(delays, delay)
		timer := &fakeTimer{callback: callback}
		timers = append(timers, timer)
		return timer
	}
	targetContext.jitter = func(delay time.Duration) time.Duration { return delay }
	if err := targetContext.Select("http://127.0.0.1:8080"); err != nil {
		t.Fatal(err)
	}
	_, _ = targetContext.SupplyCredential(context.Background(), []byte(strings.Repeat("a", 32)))
	if len(delays) != 1 || delays[0] != time.Second {
		t.Fatalf("first retry delays=%v", delays)
	}
	timers[0].callback()
	if len(delays) != 2 || delays[1] != 2*time.Second {
		t.Fatalf("second retry delays=%v", delays)
	}
	_, _ = targetContext.Recheck(context.Background())
	if !timers[1].stopped {
		t.Fatal("manual recheck did not preempt the pending retry")
	}
}

func TestCallerCancellationDoesNotPublishFailureOrScheduleRetry(t *testing.T) {
	client := &fakeClient{err: context.Canceled}
	targetContext, _ := New(
		func(applicationclient.Address) (ProbeClient, error) { return client, nil },
		func() (ScopeID, error) { return "scope-1", nil },
		time.Now,
	)
	var timers int
	targetContext.timerFactory = func(time.Duration, func()) timerHandle {
		timers++
		return &fakeTimer{}
	}
	if err := targetContext.Select("http://127.0.0.1:8080"); err != nil {
		t.Fatal(err)
	}
	parent, cancel := context.WithCancel(context.Background())
	cancel()
	_, domain := targetContext.SupplyCredential(
		parent,
		[]byte(strings.Repeat("a", 32)),
	)
	if domain == nil || !errors.Is(domain, context.Canceled) {
		t.Fatalf("caller cancellation was not retained: %#v", domain)
	}
	status := targetContext.Snapshot().Status
	if status.TargetConnection != consolecore.ConnectionUnknown || timers != 0 {
		t.Fatalf("cancellation published failure or retry: status=%#v timers=%d", status, timers)
	}
}
