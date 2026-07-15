package main

import "testing"

func TestSmartInfoFormatsNamedConnectionIdentity(t *testing.T) {
	record := TraceRecord{
		RecordType: "MODEL_REQUEST_PREPARED",
		Metadata: map[string]interface{}{
			"frameworkModel": "routed-sonnet",
			"connection":     "openrouter",
			"driver":         "OPENAI",
			"providerModel":  "anthropic/claude-sonnet-4",
			"skillName":      "summarize",
		},
	}

	want := "routed-sonnet -> openrouter (OPENAI/anthropic/claude-sonnet-4)  summarize"
	if got := smartInfo(record); got != want {
		t.Fatalf("smartInfo() = %q, want %q", got, want)
	}
}

func TestSmartInfoDistinguishesConnectionsUsingTheSameDriver(t *testing.T) {
	native := TraceRecord{RecordType: "MODEL_REQUEST_PREPARED", Metadata: map[string]interface{}{
		"frameworkModel": "native", "connection": "openai-main", "driver": "OPENAI", "providerModel": "gpt-5",
	}}
	gateway := TraceRecord{RecordType: "MODEL_REQUEST_PREPARED", Metadata: map[string]interface{}{
		"frameworkModel": "routed", "connection": "openrouter", "driver": "OPENAI", "providerModel": "anthropic/sonnet",
	}}

	if smartInfo(native) == smartInfo(gateway) {
		t.Fatalf("same-driver connections must remain distinguishable: %q", smartInfo(native))
	}
}
