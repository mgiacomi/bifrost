package target

import (
	"context"
	"crypto/rand"
	"fmt"

	"github.com/mgiacomi/bifrost/bifrost-console/internal/applicationclient"
)

type ScopeID string

type Scope struct {
	ID         ScopeID
	Context    context.Context
	Target     applicationclient.Address
	InstanceID string
	client     ProbeClient
	credential applicationclient.Credential
}

func (scope Scope) Probe(parent context.Context) (applicationclient.Instance, error) {
	if scope.client == nil || scope.credential == nil {
		return applicationclient.Instance{}, fmt.Errorf("target scope has no application access")
	}
	operation, cancel := context.WithCancel(parent)
	stop := context.AfterFunc(scope.Context, cancel)
	defer func() {
		stop()
		cancel()
	}()
	return scope.client.Probe(operation, scope.credential)
}

func (scope Scope) GoString() string {
	return fmt.Sprintf("Scope{ID:%q,Target:%q,InstanceID:%q}", scope.ID, scope.Target.String(), scope.InstanceID)
}

func newScopeID() (ScopeID, error) {
	var data [16]byte
	if _, err := rand.Read(data[:]); err != nil {
		return "", fmt.Errorf("generate target scope")
	}
	data[6] = (data[6] & 0x0f) | 0x40
	data[8] = (data[8] & 0x3f) | 0x80
	return ScopeID(fmt.Sprintf("%08x-%04x-%04x-%04x-%012x",
		data[0:4], data[4:6], data[6:8], data[8:10], data[10:16])), nil
}
