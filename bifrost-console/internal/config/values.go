package config

import (
	"fmt"
	"math"
	"net"
	"strconv"
	"strings"
	"time"
)

func (file File) Resolve() (Resolved, error) {
	if file.Version != SchemaVersion {
		return Resolved{}, fmt.Errorf("version: must be 1")
	}
	if err := ValidateListenerAddress(file.Listener.Address); err != nil {
		return Resolved{}, fmt.Errorf("listener.address: %w", err)
	}
	maxBytes, unlimited, err := parseBytes(file.TraceWorkspace.MaxBytes)
	if err != nil {
		return Resolved{}, fmt.Errorf("trace-workspace.max-bytes: %w", err)
	}
	idleTTL, never, err := parseDuration(file.TraceWorkspace.IdleTTL)
	if err != nil {
		return Resolved{}, fmt.Errorf("trace-workspace.idle-ttl: %w", err)
	}
	return Resolved{
		ListenerAddress: file.Listener.Address,
		MaxBytes:        maxBytes,
		Unlimited:       unlimited,
		IdleTTL:         idleTTL,
		NeverExpire:     never,
	}, nil
}

func ValidateListenerAddress(address string) error {
	if address == "" || address != strings.TrimSpace(address) || strings.Contains(address, ",") {
		return fmt.Errorf("must be an explicit loopback IP and port")
	}
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return fmt.Errorf("must be an explicit loopback IP and port")
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() || strings.Contains(host, "%") {
		return fmt.Errorf("must use an explicit loopback IP")
	}
	number, err := strconv.ParseUint(port, 10, 16)
	if err != nil {
		return fmt.Errorf("port must be between 0 and 65535")
	}
	if number > math.MaxUint16 {
		return fmt.Errorf("port must be between 0 and 65535")
	}
	return nil
}

func parseBytes(value string) (int64, bool, error) {
	if value == "unlimited" {
		return 0, true, nil
	}
	units := map[string]int64{
		"KiB": 1 << 10,
		"MiB": 1 << 20,
		"GiB": 1 << 30,
		"TiB": 1 << 40,
	}
	for suffix, multiplier := range units {
		if strings.HasSuffix(value, suffix) {
			number := strings.TrimSuffix(value, suffix)
			if number == "" || strings.HasPrefix(number, "+") || strings.ContainsAny(number, ".-") {
				break
			}
			parsed, err := strconv.ParseInt(number, 10, 64)
			if err != nil || parsed <= 0 || parsed > math.MaxInt64/multiplier {
				break
			}
			return parsed * multiplier, false, nil
		}
	}
	return 0, false, fmt.Errorf("must be a positive integer with KiB, MiB, GiB, or TiB, or unlimited")
}

func parseDuration(value string) (time.Duration, bool, error) {
	if value == "never" {
		return 0, true, nil
	}
	if value == "" || strings.HasPrefix(value, "+") || strings.ContainsAny(value, ".-") {
		return 0, false, fmt.Errorf("must be a positive canonical duration or never")
	}
	unit := value[len(value)-1:]
	if unit != "s" && unit != "m" && unit != "h" {
		return 0, false, fmt.Errorf("must use s, m, or h units, or never")
	}
	number := value[:len(value)-1]
	if number == "" {
		return 0, false, fmt.Errorf("must be a positive canonical duration or never")
	}
	parsed, err := strconv.ParseUint(number, 10, 63)
	if err != nil || parsed == 0 {
		return 0, false, fmt.Errorf("must be a positive canonical duration or never")
	}
	duration, err := time.ParseDuration(value)
	if err != nil || duration <= 0 {
		return 0, false, fmt.Errorf("must be a positive canonical duration or never")
	}
	return duration, false, nil
}
