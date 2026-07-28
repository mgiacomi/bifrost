package browserapi

import (
	"encoding/json"
	"net/http"

	"github.com/mgiacomi/bifrost/bifrost-console/internal/observability"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/target"
)

const maxObservabilityJSONBody = 4 * 1024

func (router *Router) observabilityInstance(response http.ResponseWriter, request *http.Request, _ string) {
	if router.options.Observability == nil || router.options.Target == nil {
		writeError(response, http.StatusInternalServerError, "CONSOLE_ERROR", "Observability service is unavailable.")
		return
	}
	if err := decodeJSONLimit(request, &struct{}{}, maxObservabilityJSONBody); err != nil {
		writeError(response, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request.")
		return
	}
	scope, domain := router.options.Target.Capture()
	if domain != nil {
		writeDomainError(response, domain)
		return
	}
	status, domain := router.options.Observability.GetInstance(request.Context(), scope)
	if domain != nil {
		writeDomainError(response, domain)
		return
	}
	router.writeScopedJSON(response, scope.ID, status)
}

func (router *Router) skillsList(response http.ResponseWriter, request *http.Request, _ string) {
	if router.options.Observability == nil || router.options.Target == nil {
		writeError(response, http.StatusInternalServerError, "CONSOLE_ERROR", "Observability service is unavailable.")
		return
	}
	var body struct {
		Cursor   string `json:"cursor,omitempty"`
		PageSize int    `json:"pageSize,omitempty"`
	}
	if err := decodeJSONLimit(request, &body, maxObservabilityJSONBody); err != nil {
		writeError(response, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request.")
		return
	}
	scope, domain := router.options.Target.Capture()
	if domain != nil {
		writeDomainError(response, domain)
		return
	}
	page, domain := router.options.Observability.ListSkills(request.Context(), scope, observability.ListRequest{
		Cursor:   body.Cursor,
		PageSize: body.PageSize,
	})
	if domain != nil {
		writeDomainError(response, domain)
		return
	}
	router.writeScopedJSON(response, scope.ID, page)
}

func (router *Router) skillDetail(response http.ResponseWriter, request *http.Request, _ string) {
	if router.options.Observability == nil || router.options.Target == nil {
		writeError(response, http.StatusInternalServerError, "CONSOLE_ERROR", "Observability service is unavailable.")
		return
	}
	var body struct {
		RegisteredName string `json:"registeredName"`
	}
	if err := decodeJSONLimit(request, &body, maxObservabilityJSONBody); err != nil {
		writeError(response, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request.")
		return
	}
	scope, domain := router.options.Target.Capture()
	if domain != nil {
		writeDomainError(response, domain)
		return
	}
	detail, domain := router.options.Observability.GetSkill(request.Context(), scope, body.RegisteredName)
	if domain != nil {
		writeDomainError(response, domain)
		return
	}
	router.writeScopedJSON(response, scope.ID, detail)
}

func (router *Router) activeExecutionsList(response http.ResponseWriter, request *http.Request, _ string) {
	if router.options.Observability == nil || router.options.Target == nil {
		writeError(response, http.StatusInternalServerError, "CONSOLE_ERROR", "Observability service is unavailable.")
		return
	}
	var body struct {
		Cursor   string `json:"cursor,omitempty"`
		PageSize int    `json:"pageSize,omitempty"`
	}
	if err := decodeJSONLimit(request, &body, maxObservabilityJSONBody); err != nil {
		writeError(response, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request.")
		return
	}
	scope, domain := router.options.Target.Capture()
	if domain != nil {
		writeDomainError(response, domain)
		return
	}
	page, domain := router.options.Observability.ListActiveExecutions(request.Context(), scope, observability.ListRequest{
		Cursor:   body.Cursor,
		PageSize: body.PageSize,
	})
	if domain != nil {
		writeDomainError(response, domain)
		return
	}
	router.writeScopedJSON(response, scope.ID, page)
}

func (router *Router) activeExecutionDetail(response http.ResponseWriter, request *http.Request, _ string) {
	if router.options.Observability == nil || router.options.Target == nil {
		writeError(response, http.StatusInternalServerError, "CONSOLE_ERROR", "Observability service is unavailable.")
		return
	}
	var body struct {
		SessionID string `json:"sessionId"`
	}
	if err := decodeJSONLimit(request, &body, maxObservabilityJSONBody); err != nil {
		writeError(response, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request.")
		return
	}
	scope, domain := router.options.Target.Capture()
	if domain != nil {
		writeDomainError(response, domain)
		return
	}
	execution, domain := router.options.Observability.GetActiveExecution(request.Context(), scope, body.SessionID)
	if domain != nil {
		writeDomainError(response, domain)
		return
	}
	router.writeScopedJSON(response, scope.ID, execution)
}

func (router *Router) tracesList(response http.ResponseWriter, request *http.Request, _ string) {
	if router.options.Observability == nil || router.options.Target == nil {
		writeError(response, http.StatusInternalServerError, "CONSOLE_ERROR", "Observability service is unavailable.")
		return
	}
	var body struct {
		Cursor   string `json:"cursor,omitempty"`
		PageSize int    `json:"pageSize,omitempty"`
	}
	if err := decodeJSONLimit(request, &body, maxObservabilityJSONBody); err != nil {
		writeError(response, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request.")
		return
	}
	scope, domain := router.options.Target.Capture()
	if domain != nil {
		writeDomainError(response, domain)
		return
	}
	page, domain := router.options.Observability.ListTraces(request.Context(), scope, observability.ListRequest{
		Cursor:   body.Cursor,
		PageSize: body.PageSize,
	})
	if domain != nil {
		writeDomainError(response, domain)
		return
	}
	router.writeScopedJSON(response, scope.ID, page)
}

func (router *Router) traceDetail(response http.ResponseWriter, request *http.Request, _ string) {
	if router.options.Observability == nil || router.options.Target == nil {
		writeError(response, http.StatusInternalServerError, "CONSOLE_ERROR", "Observability service is unavailable.")
		return
	}
	var body struct {
		TraceID string `json:"traceId"`
	}
	if err := decodeJSONLimit(request, &body, maxObservabilityJSONBody); err != nil {
		writeError(response, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request.")
		return
	}
	scope, domain := router.options.Target.Capture()
	if domain != nil {
		writeDomainError(response, domain)
		return
	}
	trace, domain := router.options.Observability.GetTrace(request.Context(), scope, body.TraceID)
	if domain != nil {
		writeDomainError(response, domain)
		return
	}
	router.writeScopedJSON(response, scope.ID, trace)
}

func (router *Router) writeScopedJSON(response http.ResponseWriter, scope target.ScopeID, value any) {
	content, err := json.Marshal(value)
	if err != nil {
		writeError(response, http.StatusInternalServerError, "CONSOLE_ERROR", "The Console response could not be created.")
		return
	}
	content = append(content, '\n')
	if domain := router.options.Target.PublishCurrent(scope, func() {
		writeJSONBytes(response, http.StatusOK, content)
	}); domain != nil {
		writeDomainError(response, domain)
	}
}
