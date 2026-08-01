package traceanalysis

import "github.com/mgiacomi/bifrost/bifrost-console/internal/consolecore"

// attemptBuild is the working state for one model attempt.
type attemptBuild struct {
	retrySequenceID string
	attemptID       string
	attemptNumber   int64
	usage           Usage
	usageComplete   bool
	hasResponse     bool
	lifecycle       []TraceRecordType
}

// attemptGraph tracks attempt/retry membership using only explicit attemptId
// and retrySequenceId. It validates positive consistent attempt numbers and
// lifecycle ordering for consumed model request/response facts. It also collects
// advisor validation links keyed by their recorded attempt identity.
type attemptGraph struct {
	attempts        map[string]*attemptBuild // keyed by attemptId
	order           []string                 // attemptId in first-seen order
	validationLinks []validationLink
}

// newAttemptGraph creates an empty attempt graph.
func newAttemptGraph() *attemptGraph {
	return &attemptGraph{attempts: map[string]*attemptBuild{}}
}

// onAdvisorRecord processes an ADVISOR_REQUEST_MUTATION_RECORDED or
// ADVISOR_RESPONSE_MUTATION_RECORDED record, capturing its validation link.
func (g *attemptGraph) onAdvisorRecord(rec *Record) *consolecore.Error {
	status := rec.metadataStringOrEmpty("status")
	retryID := rec.metadataStringOrEmpty("retrySequenceId")
	attemptID := rec.metadataStringOrEmpty("attemptId")
	number, present, valid := rec.metadataIntStrict("attemptNumber")
	attempt, exists := g.attempts[attemptID]
	if status == "" || retryID == "" || attemptID == "" || !present || !valid || number <= 0 ||
		!exists || attempt.retrySequenceID != retryID || attempt.attemptNumber != number {
		return invalidityError(CategoryInvalidAttempt, rec.TraceID)
	}
	g.validationLinks = append(g.validationLinks, validationLink{
		Status:          status,
		RetrySequenceID: retryID,
		AttemptID:       attemptID,
		AttemptNumber:   number,
	})
	return nil
}

// onModelRecord processes a MODEL_REQUEST_PREPARED, MODEL_REQUEST_SENT, or
// MODEL_RESPONSE_RECEIVED record. It validates attempt identity consistency and
// lifecycle ordering.
func (g *attemptGraph) onModelRecord(rec *Record) *consolecore.Error {
	attemptID := rec.metadataStringOrEmpty("attemptId")
	retryID := rec.metadataStringOrEmpty("retrySequenceId")
	numberStr, numberPresent, _ := rec.metadataIntStrict("attemptNumber")
	if attemptID == "" || retryID == "" || !numberPresent || numberStr <= 0 {
		return invalidityError(CategoryInvalidAttempt, rec.TraceID)
	}
	a, exists := g.attempts[attemptID]
	if !exists {
		a = &attemptBuild{
			retrySequenceID: retryID,
			attemptID:       attemptID,
			attemptNumber:   numberStr,
		}
		g.attempts[attemptID] = a
		g.order = append(g.order, attemptID)
	} else {
		// Identity consistency: retrySequenceId and attemptNumber must not change.
		if a.retrySequenceID != retryID || a.attemptNumber != numberStr {
			return invalidityError(CategoryInvalidAttempt, rec.TraceID)
		}
	}
	// Lifecycle ordering: PREPARED -> SENT -> RESPONSE_RECEIVED, no repeats.
	if !lifecycleAccepts(a.lifecycle, rec.Type) {
		return invalidityError(CategoryInvalidAttempt, rec.TraceID)
	}
	a.lifecycle = append(a.lifecycle, rec.Type)

	if rec.Type == RecordModelResponseReceived {
		a.hasResponse = true
		usage, complete, ok := extractResponseUsage(rec)
		if !ok {
			return invalidityError(CategoryInvalidUsage, rec.TraceID)
		}
		a.usage = usage
		a.usageComplete = complete
	}
	return nil
}

// lifecycleAccepts reports whether recType can follow the existing lifecycle.
func lifecycleAccepts(lifecycle []TraceRecordType, recType TraceRecordType) bool {
	switch recType {
	case RecordModelRequestPrepared:
		return len(lifecycle) == 0
	case RecordModelRequestSent:
		return len(lifecycle) == 1 && lifecycle[0] == RecordModelRequestPrepared
	case RecordModelResponseReceived:
		return len(lifecycle) == 2 &&
			lifecycle[0] == RecordModelRequestPrepared &&
			lifecycle[1] == RecordModelRequestSent
	}
	return false
}

// extractResponseUsage extracts and validates the usage object from a
// MODEL_RESPONSE_RECEIVED record. It returns the usage, whether it is complete
// (precision != UNAVAILABLE and present), and whether the usage was valid.
func extractResponseUsage(rec *Record) (Usage, bool, bool) {
	return extractUsage(rec, "usage")
}

// extractUsage extracts a usage object from a metadata key. It returns the
// usage, whether it is complete (precision present and != UNAVAILABLE), and
// whether the usage was valid. Absent usage is zero with complete=false.
func extractUsage(rec *Record, key string) (Usage, bool, bool) {
	m, err := rec.metadataObject()
	if err != nil {
		return Usage{}, false, false
	}
	raw, ok := m[key]
	if !ok || isNullRaw(raw) {
		return Usage{}, false, true
	}
	var u usagePayload
	if err := jsonUnmarshal(raw, &u); err != nil {
		return Usage{}, false, false
	}
	if u.PromptUnits == nil || u.CompletionUnits == nil || u.TotalUnits == nil || u.Precision == nil {
		return Usage{}, false, false
	}
	if _, known := knownPrecision(*u.Precision); !known {
		return Usage{}, false, false
	}
	complete := *u.Precision != string(PrecisionUnavailable)
	usage := Usage{PromptUnits: *u.PromptUnits, CompletionUnits: *u.CompletionUnits, TotalUnits: *u.TotalUnits}
	if !validateUsageComponents(usage, complete) {
		return Usage{}, false, false
	}
	return usage, complete, true
}
