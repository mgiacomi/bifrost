package artifact

import (
	"errors"
	"io"

	"github.com/mgiacomi/bifrost/bifrost-console/internal/consolecore"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/target"
)

// Lease pins an installed artifact entry for downstream analysis (PR 13). It
// increments the entry's pin count for its lifetime, preventing eviction and
// explicit removal. Only a successful Close refreshes the entry's last-use
// time. The lease provides read access to the installed file without ever
// exposing the filesystem path through any DTO.
type Lease struct {
	service *Service
	entry   *entry
	scopeID target.ScopeID
	closed  bool
	readers map[io.ReadCloser]struct{}
}

// Open returns a reader over the installed artifact file. The reader is valid
// until Close is called on the lease or the reader is closed. If the entry has
// been invalidated (scope rotation, removal), Open returns an error.
//
// This seam is deliberately ready for PR 13's streaming/parser work; PR 12
// does not parse NDJSON records or build trace views from it.
func (lease *Lease) Open() (io.ReadCloser, error) {
	lease.service.mu.Lock()
	defer lease.service.mu.Unlock()
	if lease.closed {
		return nil, errors.New("artifact lease is closed")
	}
	if lease.entry.state != stateInstalled {
		return nil, errors.New("artifact is no longer installed")
	}
	reader, err := lease.service.storage.open(lease.entry.installedPath)
	if err != nil {
		return nil, err
	}
	lease.readers[reader] = struct{}{}
	return &leaseReader{lease: lease, reader: reader}, nil
}

// Close releases the pin. If success is true, the entry's last-use time is
// refreshed, which extends its idle deadline. If success is false (error or
// cancellation), the last-use time is not refreshed. If the entry was marked
// for deferred removal (expiry or scope invalidation while pinned), the final
// Close removes the entry and releases its bytes.
//
// Close is idempotent.
func (lease *Lease) Close(success bool) error {
	lease.service.mu.Lock()
	defer lease.service.mu.Unlock()
	if lease.closed {
		return nil
	}
	lease.closed = true
	entry := lease.entry
	for reader := range lease.readers {
		_ = reader.Close()
		delete(lease.readers, reader)
	}
	delete(entry.leases, lease)
	entry.pinCount--
	if entry.pinCount < 0 {
		entry.pinCount = 0
	}
	if success && entry.state == stateInstalled {
		entry.lastUsedAt = lease.service.clock()
		lease.service.rescheduleIdleTimerLocked()
	}
	// If the entry was marked for deferred removal and the last pin just
	// closed, remove it now.
	if entry.state == stateDeferredRemoval && entry.pinCount == 0 {
		return lease.service.removeEntryLocked(entry)
	}
	return nil
}

// ScopeID returns the target scope ID the lease was issued for. Callers can
// compare it against the current scope to detect rotation.
func (lease *Lease) ScopeID() target.ScopeID {
	return lease.scopeID
}

// useEntry issues a lease for an installed entry, incrementing its pin count.
// The caller must hold the service mutex.
func (service *Service) useEntryLocked(entry *entry, scopeID target.ScopeID) (*Lease, *consolecore.Error) {
	if entry.state != stateInstalled {
		return nil, consolecore.NewError(consolecore.CodeArtifactExpired,
			"The artifact is no longer available.", string(scopeID), consolecore.Details{}, nil)
	}
	if entry.leases == nil {
		entry.leases = make(map[*Lease]struct{})
	}
	entry.pinCount++
	lease := &Lease{
		service: service,
		entry:   entry,
		scopeID: scopeID,
		readers: make(map[io.ReadCloser]struct{}),
	}
	entry.leases[lease] = struct{}{}
	return lease, nil
}

// invalidateLeasesLocked synchronously closes every reader and invalidates
// every lease for an entry. It is used only for authoritative scope/process
// invalidation; ordinary expiry and removal continue to defer while pinned.
func (service *Service) invalidateLeasesLocked(entry *entry) {
	for lease := range entry.leases {
		lease.closed = true
		for reader := range lease.readers {
			_ = reader.Close()
			delete(lease.readers, reader)
		}
		delete(entry.leases, lease)
	}
	entry.pinCount = 0
}

type leaseReader struct {
	lease  *Lease
	reader io.ReadCloser
	closed bool
}

func (reader *leaseReader) Read(buffer []byte) (int, error) {
	return reader.reader.Read(buffer)
}

func (reader *leaseReader) Close() error {
	reader.lease.service.mu.Lock()
	defer reader.lease.service.mu.Unlock()
	if reader.closed {
		return nil
	}
	reader.closed = true
	delete(reader.lease.readers, reader.reader)
	return reader.reader.Close()
}
