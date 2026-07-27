package browserauth

import (
	"net/http"
	"time"
)

const SessionCookieName = "bifrost_console_session"

func SessionCookie(value string) *http.Cookie {
	return &http.Cookie{
		Name:     SessionCookieName,
		Value:    value,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteStrictMode,
	}
}

func ExpiredSessionCookie() *http.Cookie {
	cookie := SessionCookie("")
	cookie.Expires = time.Unix(1, 0)
	cookie.MaxAge = -1
	return cookie
}
