package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"
)

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "bifrost-console build:", err)
		os.Exit(1)
	}
}

func run(arguments []string) error {
	if len(arguments) == 0 || (arguments[0] != string(modeVerify) && arguments[0] != string(modeBuild)) {
		return fmt.Errorf("usage: go run ./internal/buildtool <verify|build> [--expected-version VERSION]")
	}
	mode := buildMode(arguments[0])
	flags := flag.NewFlagSet("buildtool", flag.ContinueOnError)
	expected := flags.String("expected-version", "", "require the root POM version to equal this value")
	if err := flags.Parse(arguments[1:]); err != nil {
		return err
	}
	paths, err := resolveProjectPaths()
	if err != nil {
		return err
	}
	version, err := readProductVersion(filepath.Join(paths.repository, "pom.xml"))
	if err != nil {
		return err
	}
	if *expected != "" && *expected != version {
		return fmt.Errorf("expected version %q does not match root POM version %q", *expected, version)
	}
	fmt.Printf("Bifrost Console %s: %s\n", mode, version)
	return runPipeline(mode, pipelineContext{paths: paths, productVersion: version}, pipelineDependencies{run: realRunner{}.run})
}
