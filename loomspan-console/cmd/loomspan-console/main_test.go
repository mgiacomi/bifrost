package main

import (
	"bytes"
	"context"
	"errors"
	"testing"

	"github.com/mgiacomi/loomspan/loomspan-console/internal/console"
)

func TestRunRejectsInvalidReleaseBeforeListen(t *testing.T) {
	listened := false
	err := run(context.Background(), nil, &bytes.Buffer{}, runtimeDependencies{
		version: "development",
		verify:  func() error { return nil },
		serve: func(context.Context, console.Options) error {
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
		serve: func(context.Context, console.Options) error {
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
		serve: func(context.Context, console.Options) error {
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
		verify:  func() error { return nil },
		serve: func(context.Context, console.Options) error {
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

func TestRunPassesCLIOverridesToService(t *testing.T) {
	var options console.Options
	err := run(context.Background(), []string{
		"--config", "profile.yaml",
		"--work-dir", "work",
		"--listen", "127.0.0.1:0",
		"--development-origin", "http://127.0.0.1:5173",
		"--no-open-browser",
		"--prompt-for-application-key",
	}, &bytes.Buffer{}, runtimeDependencies{
		version: "0.1.0-SNAPSHOT",
		verify:  func() error { return nil },
		serve: func(_ context.Context, received console.Options) error {
			options = received
			return nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if options.ConfigPath != "profile.yaml" || options.WorkDirectory != "work" ||
		options.ListenOverride != "127.0.0.1:0" ||
		options.DevelopmentOrigin != "http://127.0.0.1:5173" ||
		!options.NoOpenBrowser || !options.PromptForApplicationKey {
		t.Fatalf("options=%#v", options)
	}
}

func TestRunRejectsDevelopmentOriginBeforeService(t *testing.T) {
	served := false
	err := run(context.Background(), []string{"--development-origin", "http://localhost:5173"}, &bytes.Buffer{}, runtimeDependencies{
		version: "0.1.0-SNAPSHOT",
		verify:  func() error { return nil },
		serve: func(context.Context, console.Options) error {
			served = true
			return nil
		},
	})
	if err == nil || served {
		t.Fatalf("error=%v served=%v", err, served)
	}
}
