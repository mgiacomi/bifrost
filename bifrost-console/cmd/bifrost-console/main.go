package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"syscall"

	"github.com/mgiacomi/bifrost/bifrost-console/internal/release"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/webassets"
	"github.com/mgiacomi/bifrost/bifrost-console/internal/webhost"
)

type runtimeDependencies struct {
	version string
	verify  func() error
	serve   func(context.Context, string) error
}

func main() {
	files, err := webassets.Embedded()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	dependencies := runtimeDependencies{
		version: release.ProductVersion(),
		verify: func() error {
			_, err := webassets.Verify(files, release.ProductVersion())
			return err
		},
		serve: func(context context.Context, address string) error {
			return (webhost.Host{
				Address: address,
				Handler: webhost.StaticHandler(files),
				OnListen: func(bound net.Addr) {
					fmt.Printf("Bifrost Console %s listening on http://%s\n", release.ProductVersion(), bound)
				},
			}).Run(context)
		},
	}
	context, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(context, os.Args[1:], os.Stdout, dependencies); err != nil {
		fmt.Fprintln(os.Stderr, "bifrost-console:", err)
		os.Exit(1)
	}
}

func run(context context.Context, arguments []string, output io.Writer, dependencies runtimeDependencies) error {
	flags := flag.NewFlagSet("bifrost-console", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	versionOnly := flags.Bool("version", false, "print the Bifrost product version")
	address := flags.String("listen", "127.0.0.1:7943", "explicit loopback listener address")
	if err := flags.Parse(arguments); err != nil {
		return err
	}
	if flags.NArg() != 0 {
		return fmt.Errorf("unexpected arguments: %v", flags.Args())
	}
	if err := release.ValidateProductVersion(dependencies.version); err != nil {
		return err
	}
	if *versionOnly {
		_, err := fmt.Fprintln(output, dependencies.version)
		return err
	}
	if dependencies.verify == nil || dependencies.serve == nil {
		return fmt.Errorf("runtime dependencies are incomplete")
	}
	if err := dependencies.verify(); err != nil {
		return fmt.Errorf("validate embedded browser assets: %w", err)
	}
	return dependencies.serve(context, *address)
}
