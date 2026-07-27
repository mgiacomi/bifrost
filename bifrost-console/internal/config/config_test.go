package config

import (
	"strings"
	"testing"
	"time"
)

func TestDecodeAcceptsCanonicalVersionOneDefaults(t *testing.T) {
	file, resolved, err := Decode("config.yaml", strings.NewReader(DefaultYAML))
	if err != nil {
		t.Fatal(err)
	}
	if file.Version != 1 || resolved.ListenerAddress != DefaultAddress ||
		resolved.MaxBytes != DefaultMaxBytes || resolved.IdleTTL != DefaultIdleTTL {
		t.Fatalf("unexpected resolved config: %#v %#v", file, resolved)
	}
}

func TestDecodeAcceptsExplicitLimitsAndSentinels(t *testing.T) {
	value := "version: 1\nlistener:\n  address: '[::1]:0'\ntrace-workspace:\n  max-bytes: unlimited\n  idle-ttl: never\n"
	_, resolved, err := Decode("config.yaml", strings.NewReader(value))
	if err != nil {
		t.Fatal(err)
	}
	if !resolved.Unlimited || !resolved.NeverExpire {
		t.Fatalf("sentinels not preserved: %#v", resolved)
	}
}

func TestDecodeRejectsUnknownDuplicateAndMultipleDocumentInput(t *testing.T) {
	cases := []string{
		DefaultYAML + "secret: value\n",
		"version: 1\nversion: 1\nlistener:\n  address: 127.0.0.1:1\ntrace-workspace:\n  max-bytes: 1GiB\n  idle-ttl: 1h\n",
		DefaultYAML + "---\nversion: 1\n",
		"version: &version 1\nlistener:\n  address: 127.0.0.1:1\ntrace-workspace:\n  max-bytes: 1GiB\n  idle-ttl: 1h\n",
	}
	for index, value := range cases {
		if _, _, err := Decode("config.yaml", strings.NewReader(value)); err == nil {
			t.Fatalf("case %d accepted", index)
		}
	}
}

func TestDecodeRejectsUnsafeAmbiguousAndOverflowingValues(t *testing.T) {
	for _, values := range [][2]string{
		{"0GiB", "4h"}, {"-1GiB", "4h"}, {"4GB", "4h"}, {"9223372036854775807TiB", "4h"},
		{"4GiB", "0h"}, {"4GiB", "-1h"}, {"4GiB", "1.5h"}, {"4GiB", "3600"},
	} {
		input := "version: 1\nlistener:\n  address: 127.0.0.1:7943\ntrace-workspace:\n  max-bytes: " + values[0] + "\n  idle-ttl: " + values[1] + "\n"
		if _, _, err := Decode("config.yaml", strings.NewReader(input)); err == nil {
			t.Fatalf("accepted max=%q ttl=%q", values[0], values[1])
		}
	}
}

func TestDurationParsingUsesExplicitCanonicalUnits(t *testing.T) {
	duration, never, err := parseDuration("30m")
	if err != nil || never || duration != 30*time.Minute {
		t.Fatalf("duration=%v never=%v err=%v", duration, never, err)
	}
}

func TestConfigSchemaContainsNoSecretFields(t *testing.T) {
	lower := strings.ToLower(DefaultYAML)
	for _, forbidden := range []string{"secret", "credential", "csrf", "cookie", "key:"} {
		if strings.Contains(lower, forbidden) {
			t.Fatalf("default config contains %q", forbidden)
		}
	}
}
