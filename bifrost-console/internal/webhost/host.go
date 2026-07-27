package webhost

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"time"
)

type ListenFunc func(network, address string) (net.Listener, error)

type Host struct {
	Address  string
	Handler  http.Handler
	Listen   ListenFunc
	OnListen func(net.Addr)
}

func ValidateLoopbackAddress(address string) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return fmt.Errorf("invalid listener address %q: %w", address, err)
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return fmt.Errorf("listener address %q must use an explicit loopback IP", address)
	}
	return nil
}

func (host Host) Run(runContext context.Context) error {
	if err := ValidateLoopbackAddress(host.Address); err != nil {
		return err
	}
	if host.Handler == nil {
		return fmt.Errorf("HTTP handler is required")
	}
	listen := host.Listen
	if listen == nil {
		listen = net.Listen
	}
	listener, err := listen("tcp", host.Address)
	if err != nil {
		return fmt.Errorf("listen on %s: %w", host.Address, err)
	}
	if host.OnListen != nil {
		host.OnListen(listener.Addr())
	}
	server := &http.Server{
		Handler:           host.Handler,
		ReadHeaderTimeout: 5 * time.Second,
	}
	result := make(chan error, 1)
	go func() {
		result <- server.Serve(listener)
	}()
	select {
	case <-runContext.Done():
		shutdownContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownContext); err != nil {
			return fmt.Errorf("shut down Console host: %w", err)
		}
		err := <-result
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return nil
	case err := <-result:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}
