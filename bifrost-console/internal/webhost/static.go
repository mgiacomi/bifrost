package webhost

import (
	"io/fs"
	"mime"
	"net/http"
	"path"
	"strconv"
	"strings"
)

const (
	noStore   = "no-store"
	immutable = "public, max-age=31536000, immutable"
)

func StaticHandler(files fs.FS) http.Handler {
	return http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodGet && request.Method != http.MethodHead {
			response.Header().Set("Allow", "GET, HEAD")
			http.Error(response, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		response.Header().Set("X-Content-Type-Options", "nosniff")
		name, navigation, ok := requestPath(request)
		if !ok {
			http.NotFound(response, request)
			return
		}
		data, err := fs.ReadFile(files, name)
		if err != nil {
			if navigation {
				name = "index.html"
				data, err = fs.ReadFile(files, name)
			}
			if err != nil {
				http.NotFound(response, request)
				return
			}
		}
		contentType := mime.TypeByExtension(path.Ext(name))
		switch path.Ext(name) {
		case ".html":
			contentType = "text/html; charset=utf-8"
		case ".js":
			contentType = "text/javascript; charset=utf-8"
		case ".css":
			contentType = "text/css; charset=utf-8"
		case ".json":
			contentType = "application/json"
		}
		if contentType == "" {
			contentType = "application/octet-stream"
		}
		response.Header().Set("Content-Type", contentType)
		response.Header().Set("Content-Length", strconv.Itoa(len(data)))
		if name == "index.html" {
			response.Header().Set("Cache-Control", noStore)
		} else if isContentAddressed(name) {
			response.Header().Set("Cache-Control", immutable)
		} else {
			response.Header().Set("Cache-Control", noStore)
		}
		response.WriteHeader(http.StatusOK)
		if request.Method == http.MethodGet {
			_, _ = response.Write(data)
		}
	})
}

func requestPath(request *http.Request) (name string, navigation bool, ok bool) {
	if request.URL.Path == "" || !strings.HasPrefix(request.URL.Path, "/") {
		return "", false, false
	}
	escaped := strings.ToLower(request.URL.EscapedPath())
	if strings.Contains(escaped, "%2f") || strings.Contains(escaped, "%5c") ||
		strings.Contains(escaped, "%2e") || strings.Contains(request.URL.Path, "\\") {
		return "", false, false
	}
	cleaned := path.Clean(request.URL.Path)
	if cleaned != request.URL.Path && request.URL.Path != "/" {
		return "", false, false
	}
	if cleaned == "/api/console" || strings.HasPrefix(cleaned, "/api/console/") {
		return strings.TrimPrefix(cleaned, "/"), false, true
	}
	if cleaned == "/" {
		return "index.html", true, true
	}
	name = strings.TrimPrefix(cleaned, "/")
	if name == "" || strings.HasPrefix(name, ".") {
		return "", false, false
	}
	navigation = path.Ext(name) == ""
	return name, navigation, true
}

func isContentAddressed(name string) bool {
	base := path.Base(name)
	extension := path.Ext(base)
	stem := strings.TrimSuffix(base, extension)
	dash := strings.LastIndex(stem, "-")
	if dash < 0 || len(stem)-dash-1 < 8 {
		return false
	}
	for _, character := range stem[dash+1:] {
		if !strings.ContainsRune("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_", character) {
			return false
		}
	}
	return true
}
