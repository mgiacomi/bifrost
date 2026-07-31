package artifact

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"time"

	"github.com/mgiacomi/bifrost/bifrost-console/internal/applicationclient"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/consolecore"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/target"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/workspace"
)

const (
	// streamBufferSize is the fixed-size buffer used to copy artifact bytes
	// from the upstream stream to the partial file. It bounds memory for any
	// artifact size.
	streamBufferSize = 32 * 1024
)

// TraceLoader loads authoritative current-scope trace metadata for a trace ID.
// The service uses it rather than browser-supplied size or path metadata.
type TraceLoader func(ctx context.Context, scope target.Scope, traceID string) (TraceMetadata, *consolecore.Error)

// StreamOpener opens a streaming artifact response within the current target
// scope. The returned stream is owned by the service and must be closed.
type StreamOpener func(ctx context.Context, scope target.Scope, traceID string) (*applicationclient.ArtifactStream, *consolecore.Error)

// runAcquisition is the leader goroutine for a joined acquisition. It loads
// metadata, opens one upstream stream, creates one partial file, copies bytes
// with a fixed buffer, validates the complete transfer, syncs, closes, and
// atomically renames before publishing the handle. On any failure it removes
// partial state, releases the reservation, classifies the failure, and
// publishes the error to all waiters.
//
// The acquisition context is tied to the target scope and service lifetime,
// not to any individual caller. Individual waiters cancel independently; the
// leader is cancelled only by scope/service cancellation or when no waiter
// remains.
func (service *Service) runAcquisition(entry *entry, scope target.Scope, traceID string) {
	defer close(entry.acquireFinished)

	// 1. Load authoritative trace metadata.
	metadata, domain := service.traceLoader(entry.acquireCtx, scope, traceID)
	if domain != nil {
		service.failAcquisition(entry, domain)
		return
	}
	service.mu.Lock()
	entry.metadata = metadata
	entry.applicationAvailability = ApplicationAvailable
	service.mu.Unlock()

	// 2. Open the upstream artifact stream.
	stream, domain := service.streamOpener(entry.acquireCtx, scope, traceID)
	if domain != nil {
		service.failAcquisition(entry, domain)
		return
	}

	// 3. Install the stream to disk.
	artifact, domain := service.installStream(entry, stream, metadata)
	if domain != nil {
		service.failAcquisition(entry, domain)
		return
	}

	// 4. Publish the successful result.
	service.publishAcquisitionSuccess(entry, artifact)
}

// installStream creates a partial file, copies the stream with a fixed buffer,
// validates the complete transfer, syncs, closes, and atomically renames to
// the installed location. It returns the acquired artifact on success or a
// domain error on failure.
func (service *Service) installStream(entry *entry, stream *applicationclient.ArtifactStream, metadata TraceMetadata) (AcquiredArtifact, *consolecore.Error) {
	declaredLength := stream.DeclaredLength()
	knownSize := metadata.SizeBytes
	if declaredLength > 0 && declaredLength > knownSize {
		knownSize = declaredLength
	}

	// Reserve capacity for a known-size copy upfront.
	if knownSize > 0 {
		service.mu.Lock()
		domain := service.reserveCapacity(knownSize)
		if domain != nil {
			service.mu.Unlock()
			_ = stream.Close()
			return AcquiredArtifact{}, domain
		}
		service.totalCharged += knownSize
		entry.localBytes = knownSize
		service.mu.Unlock()
	}

	// Create the partial file.
	file, partialPath, err := service.storage.createPartial()
	if err != nil {
		_ = stream.Close()
		domain := service.storageError(err, entry)
		return AcquiredArtifact{}, service.classifyArtifactFailure(domain, entry, "")
	}

	// cleanupAndReturn closes resources and classifies partial-file cleanup
	// through the workspace safety boundary before the reservation is released.
	cleanupAndReturn := func(domain *consolecore.Error) (AcquiredArtifact, *consolecore.Error) {
		if closeErr := file.Close(); closeErr != nil {
			domain = service.storageError(errors.Join(domain, closeErr), entry)
		}
		_ = stream.Close()
		return AcquiredArtifact{}, service.classifyArtifactFailure(domain, entry, partialPath)
	}

	// Copy the stream to the partial file with a fixed buffer.
	observed, domain := service.copyStream(entry, stream, file, knownSize)
	if domain != nil {
		return cleanupAndReturn(domain)
	}

	// Sync and close the partial file.
	if err := file.Sync(); err != nil {
		return cleanupAndReturn(service.storageError(err, entry))
	}
	if err := file.Close(); err != nil {
		_ = stream.Close()
		domain := service.storageError(err, entry)
		return AcquiredArtifact{}, service.classifyArtifactFailure(domain, entry, partialPath)
	}

	_ = stream.Close()

	// Validate the observed byte count against declared length and metadata.
	if declaredLength >= 0 && observed != declaredLength {
		available := true
		domain := consolecore.NewError(consolecore.CodeInvalidArtifact,
			"The downloaded artifact byte count does not match the declared length.",
			string(entry.key.scopeID), consolecore.Details{RawDownloadAvailable: &available}, nil)
		return AcquiredArtifact{}, service.classifyArtifactFailure(domain, entry, partialPath)
	}
	if metadata.SizeBytes > 0 && observed != metadata.SizeBytes {
		available := true
		domain := consolecore.NewError(consolecore.CodeInvalidArtifact,
			"The downloaded artifact byte count does not match the trace metadata.",
			string(entry.key.scopeID), consolecore.Details{RawDownloadAvailable: &available}, nil)
		return AcquiredArtifact{}, service.classifyArtifactFailure(domain, entry, partialPath)
	}

	// Generate the installed filename and atomic rename.
	installedPath, err := service.storage.installedName()
	if err != nil {
		domain := service.storageError(err, entry)
		return AcquiredArtifact{}, service.classifyArtifactFailure(domain, entry, partialPath)
	}
	if err := service.storage.rename(partialPath, installedPath); err != nil {
		domain := service.storageError(err, entry)
		return AcquiredArtifact{}, service.classifyArtifactFailure(domain, entry, partialPath)
	}

	// Adjust the charge to the exact observed bytes.
	service.mu.Lock()
	if knownSize > 0 && observed != knownSize {
		diff := observed - knownSize
		service.totalCharged += diff
		entry.localBytes = observed
	} else if knownSize == 0 {
		entry.localBytes = observed
	}
	entry.installedPath = installedPath
	service.mu.Unlock()

	now := service.clock()
	expiresAt := time.Time{}
	if !service.ttlNeverExpire {
		expiresAt = now.Add(service.ttlIdleTTL)
	}
	return AcquiredArtifact{
		Handle:        entry.handle,
		Metadata:      metadata,
		LocalBytes:    observed,
		AcquiredAt:    entry.acquisitionTime,
		LastUsedAt:    now,
		ExpiresAt:     expiresAt,
		HasIdleExpiry: !service.ttlNeverExpire,
	}, nil
}

// copyStream copies bytes from the upstream stream to the partial file with a
// fixed-size buffer. For unknown-length streams it charges capacity
// incrementally. It returns the observed byte count on success or a domain
// error on failure (cancellation, short write, disk-full, or capacity).
func (service *Service) copyStream(entry *entry, stream *applicationclient.ArtifactStream, file writableFile, knownSize int64) (int64, *consolecore.Error) {
	buffer := make([]byte, streamBufferSize)
	var observed int64
	for {
		select {
		case <-entry.acquireCtx.Done():
			return observed, service.cancellationError(entry)
		default:
		}
		n, readErr := stream.Body().Read(buffer)
		if n > 0 {
			if knownSize > 0 && (observed > knownSize || int64(n) > knownSize-observed) {
				available := true
				return observed, consolecore.NewError(consolecore.CodeInvalidArtifact,
					"The downloaded artifact exceeds its declared size.",
					string(entry.key.scopeID),
					consolecore.Details{RawDownloadAvailable: &available}, nil)
			}
			// Unknown-length bytes must be admitted before they reach disk so
			// every partial byte is covered by aggregate capacity.
			if knownSize == 0 {
				service.mu.Lock()
				domain := service.reserveCapacity(int64(n))
				if domain != nil {
					service.mu.Unlock()
					return observed, domain
				}
				service.totalCharged += int64(n)
				entry.localBytes += int64(n)
				service.mu.Unlock()
			}
			written, writeErr := file.Write(buffer[:n])
			observed += int64(written)
			if writeErr != nil {
				return observed, service.storageError(writeErr, entry)
			}
			if written < n {
				return observed, service.storageError(errors.New("short write"), entry)
			}
		}
		if readErr != nil {
			if errors.Is(readErr, io.EOF) {
				return observed, nil
			}
			if errors.Is(readErr, context.Canceled) {
				return observed, service.cancellationError(entry)
			}
			return observed, consolecore.NewError(consolecore.CodeTargetUnavailable,
				"The artifact stream was interrupted.", string(entry.key.scopeID),
				consolecore.Details{}, readErr)
		}
	}
}

// cancellationError maps an acquisition-context cancellation to the right
// domain error. If the service is closed, it returns CONSOLE_ERROR. If the
// current scope no longer matches the entry's scope (scope rotation), it
// returns TARGET_CHANGED. Otherwise (last waiter left), it returns a
// request-scoped cancellation.
//
// The acquisition context is always cancelled by the time this runs—either by
// scope rotation, service close, or the last waiter departing—so checking
// acquireCtx.Err() cannot distinguish the causes. Instead we compare the
// current scope ID against the entry's scope ID.
func (service *Service) cancellationError(entry *entry) *consolecore.Error {
	service.mu.Lock()
	closed := service.closed
	currentScopeID := service.currentScopeID
	service.mu.Unlock()
	if closed {
		return consolecore.NewError(consolecore.CodeConsoleError,
			"The Console is shutting down.", string(entry.key.scopeID),
			consolecore.Details{}, nil)
	}
	if currentScopeID != entry.key.scopeID {
		return consolecore.NewError(consolecore.CodeTargetChanged,
			"The selected target changed. Start this operation again.",
			string(entry.key.scopeID), consolecore.Details{}, nil)
	}
	return consolecore.NewError(consolecore.CodeTargetUnavailable,
		"The operation was canceled.", string(entry.key.scopeID),
		consolecore.Details{}, nil)
}

// storageError maps a filesystem error to a domain error. In unlimited
// mode, disk-full (ENOSPC) maps to LOCAL_STORAGE_UNAVAILABLE. In finite mode,
// disk-full was already caught by capacity reservation. Other I/O errors map
// to LOCAL_STORAGE_UNAVAILABLE.
func (service *Service) storageError(err error, entry *entry) *consolecore.Error {
	return consolecore.NewError(consolecore.CodeLocalStorageUnavailable,
		"Local artifact storage is unavailable.", string(entry.key.scopeID),
		consolecore.Details{}, err)
}

// classifyArtifactFailure removes partial state and verifies that the workspace
// remains safe. Persistent cleanup or workspace-probe failure is process-fatal.
func (service *Service) classifyArtifactFailure(domain *consolecore.Error, entry *entry, partialPath string) *consolecore.Error {
	classified := service.workspace.ClassifyArtifactFailure(domain, func() error {
		if partialPath != "" {
			return service.storage.remove(partialPath)
		}
		return nil
	})
	if workspace.IsFatal(classified) {
		slog.Error("artifact storage failure is fatal", "scopeId", entry.key.scopeID)
		if service.fatal != nil {
			service.fatal(classified)
		}
		return consolecore.NewError(consolecore.CodeConsoleError,
			"The Console workspace is no longer safe.", string(entry.key.scopeID),
			consolecore.Details{}, classified)
	}
	if domainErr, ok := classified.(*consolecore.Error); ok {
		return domainErr
	}
	return domain
}

// failAcquisition releases the already-cleaned reservation and publishes the
// terminal error to all waiters.
func (service *Service) failAcquisition(entry *entry, domain *consolecore.Error) {
	service.mu.Lock()
	defer service.mu.Unlock()
	if entry.state == stateRemoved {
		// Already removed by someone else; still publish the error to waiters.
		entry.acquireResult = acquireResult{err: domain}
		select {
		case <-entry.acquireDone:
		default:
			close(entry.acquireDone)
		}
		return
	}
	if entry.acquireCancel != nil {
		entry.acquireCancel()
	}
	if entry.scopeStop != nil {
		entry.scopeStop()
	}
	service.releaseReservationLocked(entry)
	entry.state = stateRemoved
	entry.acquireResult = acquireResult{err: domain}
	close(entry.acquireDone)
	delete(service.entries, entry.key)
	delete(service.handles, entry.handle)
	service.rescheduleIdleTimerLocked()
}

// publishAcquisitionSuccess transitions the entry to installed and publishes
// the handle to all waiters. If the entry was invalidated (scope rotation)
// during the transfer, the installed file is removed and TARGET_CHANGED is
// published instead.
func (service *Service) publishAcquisitionSuccess(entry *entry, artifact AcquiredArtifact) {
	service.mu.Lock()
	defer service.mu.Unlock()
	if entry.state == stateRemoved {
		// The entry was invalidated during the transfer. Remove the file.
		domain := consolecore.NewError(
			consolecore.CodeTargetChanged,
			"The selected target changed. Start this operation again.",
			string(entry.key.scopeID), consolecore.Details{}, nil)
		if cleanupDomain := service.removeInstalledFileLocked(entry); cleanupDomain != nil {
			domain = cleanupDomain
		}
		service.releaseReservationLocked(entry)
		entry.acquireResult = acquireResult{err: domain}
		close(entry.acquireDone)
		return
	}
	if entry.acquireCtx.Err() != nil || entry.waiters <= 0 {
		domain := consolecore.NewError(consolecore.CodeTargetUnavailable,
			"The operation was canceled.", string(entry.key.scopeID),
			consolecore.Details{}, entry.acquireCtx.Err())
		if service.closed {
			domain = consolecore.NewError(consolecore.CodeConsoleError,
				"The Console is shutting down.", string(entry.key.scopeID),
				consolecore.Details{}, entry.acquireCtx.Err())
		} else if service.currentScopeID != entry.key.scopeID {
			domain = consolecore.NewError(consolecore.CodeTargetChanged,
				"The selected target changed. Start this operation again.",
				string(entry.key.scopeID), consolecore.Details{}, entry.acquireCtx.Err())
		}
		if cleanupDomain := service.removeEntryLocked(entry); cleanupDomain != nil {
			domain = cleanupDomain
		}
		entry.acquireResult = acquireResult{err: domain}
		close(entry.acquireDone)
		return
	}
	entry.state = stateInstalled
	entry.lastUsedAt = artifact.LastUsedAt
	if entry.scopeStop != nil {
		entry.scopeStop()
		entry.scopeStop = nil
	}
	entry.acquireResult = acquireResult{artifact: artifact}
	close(entry.acquireDone)
	service.rescheduleIdleTimerLocked()
}

// releaseReservationLocked releases the entry's charged bytes from the total.
// The caller must hold the service mutex.
func (service *Service) releaseReservationLocked(entry *entry) {
	if entry.localBytes > 0 {
		service.totalCharged -= entry.localBytes
		if service.totalCharged < 0 {
			service.totalCharged = 0
		}
		entry.localBytes = 0
	}
}

// computeExpiry returns the idle expiry deadline for an entry.
func (service *Service) computeExpiry(entry *entry) time.Time {
	deadline, _ := service.idleDeadline(entry)
	return deadline
}
