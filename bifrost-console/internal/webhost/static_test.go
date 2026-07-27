package webhost

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"
)

func TestStaticHandlerServesEntryAndDeepLinks(t *testing.T) {
	handler := StaticHandler(testFiles())
	for _, target := range []string{"/", "/foundation/deep-link"} {
		response := request(t, handler, http.MethodGet, target)
		if response.Code != http.StatusOK || response.Body.String() != "<main>Bifrost</main>" {
			t.Fatalf("%s returned %d %q", target, response.Code, response.Body.String())
		}
		if response.Header().Get("Cache-Control") != "no-store" {
			t.Fatalf("%s cache = %q", target, response.Header().Get("Cache-Control"))
		}
	}
}

func TestStaticHandlerServesContentAddressedAssets(t *testing.T) {
	response := request(t, StaticHandler(testFiles()), http.MethodGet, "/assets/app-12345678.js")
	if response.Code != http.StatusOK || response.Body.String() != `console.log("bifrost")` {
		t.Fatalf("asset response = %d %q", response.Code, response.Body.String())
	}
	if response.Header().Get("Cache-Control") != immutable {
		t.Fatalf("cache = %q", response.Header().Get("Cache-Control"))
	}
	if !strings.HasPrefix(response.Header().Get("Content-Type"), "text/javascript") {
		t.Fatalf("content type = %q", response.Header().Get("Content-Type"))
	}
}

func TestStaticHandlerDoesNotFallbackAssetOrReservedPaths(t *testing.T) {
	for _, target := range []string{"/assets/missing-12345678.js", "/api/console/missing", "/file.css", "/../secret", "/%2e%2e/secret"} {
		response := request(t, StaticHandler(testFiles()), http.MethodGet, target)
		if response.Code != http.StatusNotFound {
			t.Fatalf("%s returned %d, want 404", target, response.Code)
		}
		if strings.Contains(response.Body.String(), "Bifrost") {
			t.Fatalf("%s returned the SPA entry", target)
		}
	}
}

func TestStaticHandlerMethodAndHeaderPolicy(t *testing.T) {
	handler := StaticHandler(testFiles())
	head := request(t, handler, http.MethodHead, "/assets/app-12345678.js")
	if head.Code != http.StatusOK || head.Body.Len() != 0 || head.Header().Get("Content-Length") == "" {
		t.Fatalf("HEAD response = %d length=%q body=%q", head.Code, head.Header().Get("Content-Length"), head.Body.String())
	}
	if head.Header().Get("X-Content-Type-Options") != "nosniff" {
		t.Fatal("nosniff header missing")
	}
	post := request(t, handler, http.MethodPost, "/")
	if post.Code != http.StatusMethodNotAllowed || post.Header().Get("Allow") != "GET, HEAD" {
		t.Fatalf("POST response = %d Allow=%q", post.Code, post.Header().Get("Allow"))
	}
}

func request(t *testing.T, handler http.Handler, method, target string) *httptest.ResponseRecorder {
	t.Helper()
	request := httptest.NewRequest(method, target, nil)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}

func testFiles() fstest.MapFS {
	return fstest.MapFS{
		"index.html":              {Data: []byte("<main>Bifrost</main>")},
		"assets/app-12345678.js":  {Data: []byte(`console.log("bifrost")`)},
		"assets/app-12345678.css": {Data: []byte("body{}")},
		".vite/manifest.json":     {Data: []byte("{}")},
	}
}
