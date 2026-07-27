package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

func TestProjectDeclarationsMatchPinnedToolchains(t *testing.T) {
	paths, err := resolveProjectPaths()
	if err != nil {
		t.Fatal(err)
	}
	nodeVersion, err := os.ReadFile(filepath.Join(paths.module, ".node-version"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(string(nodeVersion)) != requiredNode {
		t.Fatalf(".node-version = %q", nodeVersion)
	}
	goModule, err := os.ReadFile(filepath.Join(paths.module, "go.mod"))
	if err != nil {
		t.Fatal(err)
	}
	if !regexp.MustCompile(`(?m)^go 1\.26\.0$`).Match(goModule) ||
		!regexp.MustCompile(`(?m)^toolchain go1\.26\.5$`).Match(goModule) {
		t.Fatalf("go.mod does not declare the pinned toolchain:\n%s", goModule)
	}
}

func TestPackageManifestUsesExactDirectVersions(t *testing.T) {
	paths, err := resolveProjectPaths()
	if err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(filepath.Join(paths.web, "package.json"))
	if err != nil {
		t.Fatal(err)
	}
	var manifest struct {
		Private         bool              `json:"private"`
		PackageManager  string            `json:"packageManager"`
		Engines         map[string]string `json:"engines"`
		Dependencies    map[string]string `json:"dependencies"`
		DevDependencies map[string]string `json:"devDependencies"`
	}
	if err := json.Unmarshal(raw, &manifest); err != nil {
		t.Fatal(err)
	}
	if !manifest.Private || manifest.PackageManager != "npm@"+requiredNPM ||
		manifest.Engines["node"] != requiredNode || manifest.Engines["npm"] != requiredNPM {
		t.Fatalf("package metadata does not match pinned tools: %+v", manifest)
	}
	expected := map[string]string{
		"@tailwindcss/vite": "4.3.3", "react": "19.2.8", "react-aria-components": "1.19.0",
		"react-dom": "19.2.8", "react-router": "8.3.0", "tailwindcss": "4.3.3",
		"@playwright/test": "1.62.0", "@testing-library/dom": "10.4.1",
		"@testing-library/jest-dom": "7.0.0", "@testing-library/react": "16.3.2",
		"@testing-library/user-event": "14.6.1", "@types/react": "19.2.17",
		"@types/react-dom": "19.2.3", "@vitejs/plugin-react": "6.0.4",
		"@vitest/coverage-v8": "4.1.10", "jsdom": "29.1.1", "typescript": "7.0.2",
		"vite": "8.1.5", "vitest": "4.1.10",
	}
	actual := make(map[string]string)
	for name, version := range manifest.Dependencies {
		actual[name] = version
	}
	for name, version := range manifest.DevDependencies {
		actual[name] = version
	}
	if len(actual) != len(expected) {
		t.Fatalf("direct dependency count = %d, want %d", len(actual), len(expected))
	}
	for name, version := range expected {
		if actual[name] != version {
			t.Errorf("%s = %q, want %q", name, actual[name], version)
		}
	}
}
