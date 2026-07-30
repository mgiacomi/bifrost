package browserapi

import (
	"fmt"
	"net/http"
	"strings"

	"github.com/mgiacomi/bifrost/bifrost-console/internal/browserauth"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/live"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/observability"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/target"
)

const csrfHeader = "X-Bifrost-Console-CSRF"

type Options struct {
	Policy        Policy
	Pairing       *browserauth.Pairing
	Sessions      *browserauth.Registry
	ProcessID     string
	Workspace     string
	PairingURL    func(string) string
	PrintPairing  func(string) error
	Target        *target.Context
	Observability *observability.Service
	Live          *live.Service
}

type Router struct {
	options Options
}

func New(options Options) (*Router, error) {
	if options.Pairing == nil || options.Sessions == nil || options.PairingURL == nil {
		return nil, fmt.Errorf("browser API dependencies are incomplete")
	}
	return &Router{options: options}, nil
}

func (router *Router) ServeHTTP(response http.ResponseWriter, request *http.Request) {
	ApplyHeaders(response.Header())
	response.Header().Set("Cache-Control", "no-store")
	if !router.options.Policy.ValidateHost(request) {
		writeError(response, http.StatusBadRequest, "INVALID_REQUEST", "Browser request rejected.")
		return
	}
	if !router.options.Policy.ValidateOrigin(request) {
		writeError(response, http.StatusForbidden, "BROWSER_SECURITY_REJECTED", "Browser request rejected.")
		return
	}
	if request.Method != http.MethodPost {
		response.Header().Set("Allow", "POST")
		writeError(response, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "Use POST for this operation.")
		return
	}
	switch request.URL.Path {
	case "/api/console/v1/pairing/exchange":
		router.exchange(response, request)
	case "/api/console/v1/pairing/challenge":
		router.manualChallenge(response, request)
	case "/api/console/v1/bootstrap":
		router.withSession(response, request, false, router.bootstrap)
	case "/api/console/v1/pairing/link":
		router.withSession(response, request, true, router.pairingLink)
	case "/api/console/v1/tabs/release":
		router.withSession(response, request, true, router.releaseTab)
	case "/api/console/v1/tabs/heartbeat":
		router.withSession(response, request, true, router.heartbeat)
	case "/api/console/v1/target/status":
		router.withSession(response, request, false, router.targetStatus)
	case "/api/console/v1/target/connect":
		router.withSession(response, request, true, router.targetConnect)
	case "/api/console/v1/target/credential":
		router.withSession(response, request, true, router.targetCredential)
	case "/api/console/v1/target/recheck":
		router.withSession(response, request, true, router.targetRecheck)
	case "/api/console/v1/observability/instance":
		router.withSession(response, request, false, router.observabilityInstance)
	case "/api/console/v1/skills/list":
		router.withSession(response, request, false, router.skillsList)
	case "/api/console/v1/skills/detail":
		router.withSession(response, request, false, router.skillDetail)
	case "/api/console/v1/active-executions/list":
		router.withSession(response, request, false, router.activeExecutionsList)
	case "/api/console/v1/active-executions/detail":
		router.withSession(response, request, false, router.activeExecutionDetail)
	case "/api/console/v1/traces/list":
		router.withSession(response, request, false, router.tracesList)
	case "/api/console/v1/traces/detail":
		router.withSession(response, request, false, router.traceDetail)
	case "/api/console/v1/activity/stream":
		router.withSessionSSE(response, request, router.activityStream)
	case "/api/console/v1/activity/recent":
		router.withSession(response, request, false, router.activityRecent)
	default:
		writeError(response, http.StatusNotFound, "NOT_FOUND", "Console operation not found.")
	}
}

func (router *Router) exchange(response http.ResponseWriter, request *http.Request) {
	var body struct {
		Secret string `json:"secret"`
	}
	if err := decodeJSON(request, &body); err != nil {
		writeError(response, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request.")
		return
	}
	if !router.options.Pairing.Consume(body.Secret) {
		writeError(response, http.StatusUnauthorized, "PAIRING_REJECTED", "Pairing link is invalid or expired.")
		return
	}
	sessionID, err := router.options.Sessions.CreateSession()
	if err != nil {
		writeError(response, http.StatusTooManyRequests, "LIMIT_EXCEEDED", "Browser session limit reached.")
		return
	}
	http.SetCookie(response, browserauth.SessionCookie(sessionID))
	writeJSON(response, http.StatusOK, map[string]bool{"paired": true})
}

func (router *Router) manualChallenge(response http.ResponseWriter, request *http.Request) {
	if err := decodeJSON(request, &struct{}{}); err != nil {
		writeError(response, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request.")
		return
	}
	secret, err := router.options.Pairing.Create(true)
	if err != nil {
		writeError(response, http.StatusTooManyRequests, "RATE_LIMITED", "A pairing challenge is already available. Try again shortly.")
		return
	}
	if router.options.PrintPairing != nil {
		if err := router.options.PrintPairing(router.options.PairingURL(secret)); err != nil {
			writeError(response, http.StatusInternalServerError, "PAIRING_UNAVAILABLE", "Pairing challenge could not be displayed.")
			return
		}
	}
	writeJSON(response, http.StatusAccepted, map[string]bool{"challengePrinted": true})
}

type authenticatedHandler func(http.ResponseWriter, *http.Request, string)

func (router *Router) withSession(response http.ResponseWriter, request *http.Request, csrf bool, handler authenticatedHandler) {
	cookie, err := request.Cookie(browserauth.SessionCookieName)
	if err != nil || !router.options.Sessions.Authenticate(cookie.Value) {
		http.SetCookie(response, browserauth.ExpiredSessionCookie())
		writeError(response, http.StatusUnauthorized, "SESSION_REQUIRED", "Pairing is required.")
		return
	}
	if csrf {
		tabIDs := request.Header.Values("X-Bifrost-Console-Tab")
		tokens := request.Header.Values(csrfHeader)
		if len(tabIDs) != 1 || len(tokens) != 1 || strings.Contains(tokens[0], ",") ||
			!router.options.Sessions.ValidateCSRF(cookie.Value, tabIDs[0], tokens[0]) {
			writeError(response, http.StatusForbidden, "BROWSER_SECURITY_REJECTED", "Browser request rejected.")
			return
		}
	}
	handler(response, request, cookie.Value)
}

func (router *Router) bootstrap(response http.ResponseWriter, request *http.Request, sessionID string) {
	var body struct {
		TabID string `json:"tabId,omitempty"`
	}
	if err := decodeJSON(request, &body); err != nil {
		writeError(response, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request.")
		return
	}
	result, err := router.options.Sessions.Bootstrap(sessionID, body.TabID)
	if err != nil {
		writeError(response, http.StatusTooManyRequests, "LIMIT_EXCEEDED", "Browser tab limit reached.")
		return
	}
	writeJSON(response, http.StatusOK, map[string]any{
		"processId":     router.options.ProcessID,
		"workspacePath": router.options.Workspace,
		"tabId":         result.TabID,
		"csrfToken":     result.CSRF,
		"target":        targetResponse(router.targetSnapshot()),
	})
}

func (router *Router) targetSnapshot() target.Snapshot {
	if router.options.Target == nil {
		return target.Snapshot{}
	}
	return router.options.Target.Snapshot()
}

func (router *Router) pairingLink(response http.ResponseWriter, request *http.Request, _ string) {
	if err := decodeJSON(request, &struct{}{}); err != nil {
		writeError(response, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request.")
		return
	}
	secret, err := router.options.Pairing.Create(false)
	if err != nil {
		writeError(response, http.StatusInternalServerError, "PAIRING_UNAVAILABLE", "Pairing link could not be created.")
		return
	}
	writeJSON(response, http.StatusOK, map[string]string{"pairingUrl": router.options.PairingURL(secret)})
}

func (router *Router) releaseTab(response http.ResponseWriter, request *http.Request, sessionID string) {
	if err := decodeJSON(request, &struct{}{}); err != nil {
		writeError(response, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request.")
		return
	}
	tabIDs := request.Header.Values("X-Bifrost-Console-Tab")
	if len(tabIDs) == 1 {
		router.options.Sessions.ReleaseTab(sessionID, tabIDs[0])
	}
	writeJSON(response, http.StatusOK, map[string]bool{"released": true})
}

func (router *Router) heartbeat(response http.ResponseWriter, request *http.Request, _ string) {
	var body struct{}
	if err := decodeJSON(request, &body); err != nil {
		writeError(response, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request.")
		return
	}
	writeJSON(response, http.StatusOK, map[string]bool{"active": true})
}
