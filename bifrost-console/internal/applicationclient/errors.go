package applicationclient

import "fmt"

type FailureKind string

const (
	FailureAuthentication FailureKind = "authentication"
	FailureAccess         FailureKind = "access"
	FailureIncompatible   FailureKind = "incompatible"
	FailureUnavailable    FailureKind = "unavailable"
	FailureProtocol       FailureKind = "protocol"
)

type TransportCategory string

const (
	CategoryDNS                TransportCategory = "dns"
	CategoryConnection         TransportCategory = "connection"
	CategoryTimeout            TransportCategory = "timeout"
	CategoryTLSUntrustedIssuer TransportCategory = "tls_untrusted_issuer"
	CategoryTLSHostname        TransportCategory = "tls_hostname_mismatch"
	CategoryTLSExpired         TransportCategory = "tls_expired"
	CategoryTLSNotYetValid     TransportCategory = "tls_not_yet_valid"
	CategoryTLSHandshake       TransportCategory = "tls_handshake"
	CategoryRedirect           TransportCategory = "redirect"
	CategoryNamespaceNotFound  TransportCategory = "namespace_not_found"
	CategoryUpstreamServer     TransportCategory = "upstream_server"
	CategoryUpstreamProtocol   TransportCategory = "upstream_protocol"
)

type Failure struct {
	Kind      FailureKind
	Category  TransportCategory
	Expected  string
	Observed  string
	Retryable bool
	cause     error
}

func (failure *Failure) Error() string {
	switch failure.Kind {
	case FailureAuthentication:
		return "Application authentication is required."
	case FailureAccess:
		return "The selected target denied access."
	case FailureIncompatible:
		return "The selected target is not compatible with this Console release."
	default:
		return "The selected target is unavailable."
	}
}

func (failure *Failure) Unwrap() error { return failure.cause }

func newFailure(kind FailureKind, category TransportCategory, cause error) *Failure {
	return &Failure{Kind: kind, Category: category, cause: cause}
}

func protocolFailure() *Failure {
	return newFailure(FailureProtocol, CategoryUpstreamProtocol, fmt.Errorf("invalid upstream protocol"))
}
