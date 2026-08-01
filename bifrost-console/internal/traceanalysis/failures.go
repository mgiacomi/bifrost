package traceanalysis

import "github.com/mgiacomi/bifrost/bifrost-console/internal/consolecore"

// failureGraph tracks ERROR_RECORDED failure facts and validates terminal
// failure linkage. Failure identity uses only explicit failureId and
// terminalFailureId.
type failureGraph struct {
	failures map[string]bool // failureId -> terminal flag
	order    []string        // failureId in first-seen order
}

// newFailureGraph creates an empty failure graph.
func newFailureGraph() *failureGraph {
	return &failureGraph{failures: map[string]bool{}}
}

// onErrorRecord processes an ERROR_RECORDED record. It records the failureId
// and terminal flag when a failureId is present. An ERROR_RECORDED without a
// failureId is treated as an opaque nonterminal error and is not indexed; the
// terminal linkage check only resolves the completion's terminalFailureId
// against recorded terminal failures.
func (g *failureGraph) onErrorRecord(rec *Record) *consolecore.Error {
	failureID := rec.metadataStringOrEmpty("failureId")
	if failureID == "" {
		return nil
	}
	terminal, present, err := rec.metadataBool("terminal")
	if err != nil {
		return invalidityError(CategoryInvalidTerminalFailure, rec.TraceID)
	}
	if !present {
		// Default to nonterminal when the flag is absent.
		terminal = false
	}
	if existing, dup := g.failures[failureID]; dup {
		// A repeated failureId must not change terminality.
		if existing != terminal {
			return invalidityError(CategoryInvalidTerminalFailure, rec.TraceID)
		}
	} else {
		g.failures[failureID] = terminal
		g.order = append(g.order, failureID)
	}
	return nil
}

// metadataBoolStrict returns the boolean metadata value and whether it was
// present and non-null.
func (r *Record) metadataBoolStrict(key string) (bool, bool) {
	b, present, err := r.metadataBool(key)
	if err != nil || !present {
		return false, false
	}
	return b, true
}

// validateTerminalLink checks that a failed/aborted terminal outcome has a
// resolvable terminal failure, and a succeeded outcome forbids one. The
// terminalFailureId referenced by TRACE_COMPLETED must match a recorded terminal
// ERROR_RECORDED failure.
func (g *failureGraph) validateTerminalLink(outcome TraceOutcome, terminalFailureID string, traceID string) *consolecore.Error {
	if outcome == OutcomeSucceeded {
		if terminalFailureID != "" {
			return invalidityError(CategoryInvalidTerminalFailure, traceID)
		}
		return nil
	}
	// FAILED or ABORTED requires a resolvable terminal failure.
	if terminalFailureID == "" {
		return invalidityError(CategoryInvalidTerminalFailure, traceID)
	}
	terminal, exists := g.failures[terminalFailureID]
	if !exists || !terminal {
		return invalidityError(CategoryInvalidTerminalFailure, traceID)
	}
	return nil
}

// hasTerminalFailure reports whether a terminal failure with the given ID was
// recorded.
func (g *failureGraph) hasTerminalFailure(id string) bool {
	terminal, ok := g.failures[id]
	return ok && terminal
}
