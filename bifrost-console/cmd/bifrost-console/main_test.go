package main

import (
	"bytes"
	"context"
	"errors"
	"testing"
)

func TestRunRejectsInvalidReleaseBeforeListen(t *testing.T) {
	listened := false
	err := run(context.Background(), nil, &bytes.Buffer{}, runtimeDependencies{
		version: "development",
		verify:  func() error { return nil },
		serve: func(context.Context, string) error {
			listened = true
			return nil
		},
	})
	if err == nil || listened {
		t.Fatalf("error=%v listened=%v", err, listened)
	}
}

func TestRunRejectsInvalidAssetsBeforeListen(t *testing.T) {
	listened := false
	sentinel := errors.New("invalid assets")
	err := run(context.Background(), nil, &bytes.Buffer{}, runtimeDependencies{
		version: "0.1.0-SNAPSHOT",
		verify:  func() error { return sentinel },
		serve: func(context.Context, string) error {
			listened = true
			return nil
		},
	})
	if !errors.Is(err, sentinel) || listened {
		t.Fatalf("error=%v listened=%v", err, listened)
	}
}

func TestRunStartsOnlyAfterAssetValidation(t *testing.T) {
	validated := false
	err := run(context.Background(), []string{"--listen", "127.0.0.1:0"}, &bytes.Buffer{}, runtimeDependencies{
		version: "0.1.0-SNAPSHOT",
		verify: func() error {
			validated = true
			return nil
		},
		serve: func(context.Context, string) error {
			if !validated {
				t.Fatal("listener invoked before asset validation")
			}
			return nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestVersionFlagPrintsInjectedProductVersion(t *testing.T) {
	var output bytes.Buffer
	listened := false
	err := run(context.Background(), []string{"--version"}, &output, runtimeDependencies{
		version: "0.1.0-SNAPSHOT",
		serve: func(context.Context, string) error {
			listened = true
			return nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if output.String() != "0.1.0-SNAPSHOT\n" || listened {
		t.Fatalf("output=%q listened=%v", output.String(), listened)
	}
}
