package webhost

import (
	"context"
	"errors"
	"net"
	"net/http"
	"testing"
	"time"
)

func TestHostAnnouncesOnlyAfterListenSucceeds(t *testing.T) {
	listenFailure := errors.New("port unavailable")
	announced := false
	host := Host{
		Address: "127.0.0.1:7943",
		Handler: http.NotFoundHandler(),
		Listen: func(string, string) (net.Listener, error) {
			return nil, listenFailure
		},
		OnListen: func(net.Addr) {
			announced = true
		},
	}
	if err := host.Run(context.Background()); !errors.Is(err, listenFailure) {
		t.Fatalf("Run() error = %v, want %v", err, listenFailure)
	}
	if announced {
		t.Fatal("host announced listening after listener creation failed")
	}
}

func TestHostRejectsNonLoopbackAddress(t *testing.T) {
	for _, address := range []string{"0.0.0.0:0", "[::]:0", "192.168.1.10:8080", "localhost:8080", "example.com:80"} {
		t.Run(address, func(t *testing.T) {
			called := false
			host := Host{
				Address: address,
				Handler: http.NotFoundHandler(),
				Listen: func(string, string) (net.Listener, error) {
					called = true
					return nil, nil
				},
			}
			if err := host.Run(context.Background()); err == nil {
				t.Fatal("Run() accepted non-loopback address")
			}
			if called {
				t.Fatal("listener opened before address validation")
			}
		})
	}
	for _, address := range []string{"127.0.0.1:0", "[::1]:0"} {
		if err := ValidateLoopbackAddress(address); err != nil {
			t.Fatalf("%s rejected: %v", address, err)
		}
	}
}

func TestHostShutsDownOnContextCancellation(t *testing.T) {
	context, cancel := context.WithCancel(context.Background())
	defer cancel()
	listenerAddress := make(chan string, 1)
	host := Host{
		Address: "127.0.0.1:0",
		Handler: http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
			response.WriteHeader(http.StatusNoContent)
		}),
		Listen: func(network, address string) (net.Listener, error) {
			return net.Listen(network, address)
		},
		OnListen: func(address net.Addr) {
			listenerAddress <- address.String()
		},
	}
	result := make(chan error, 1)
	go func() { result <- host.Run(context) }()
	select {
	case address := <-listenerAddress:
		response, err := http.Get("http://" + address)
		if err != nil {
			t.Fatal(err)
		}
		_ = response.Body.Close()
	case <-time.After(3 * time.Second):
		t.Fatal("listener did not open")
	}
	cancel()
	select {
	case err := <-result:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("host did not shut down")
	}
}
