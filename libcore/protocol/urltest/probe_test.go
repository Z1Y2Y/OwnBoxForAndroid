package urltest

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	singURLTest "github.com/sagernet/sing-box/common/urltest"
	boxlog "github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
)

func TestGetFallbackLink(t *testing.T) {
	tests := []struct {
		name string
		link string
		want string
	}{
		{name: "cloudflare URL", link: "https://cp.cloudflare.com/generate_204", want: DefaultFallbackURL},
		{name: "substring also matches", link: "https://example.com/path/cloudflare.com", want: DefaultFallbackURL},
		{name: "other URL", link: "https://www.gstatic.com/generate_204", want: DefaultCFURL},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := GetFallbackLink(test.link); got != test.want {
				t.Fatalf("GetFallbackLink(%q) = %q, want %q", test.link, got, test.want)
			}
		})
	}
}

type probeDialOutbound struct {
	*testMockOutbound
}

func (o *probeDialOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, network, destination.String())
}

func newProbeDialOutbound() *probeDialOutbound {
	return &probeDialOutbound{testMockOutbound: newTestOutbound("probe")}
}

func TestProbeReturnsFallbackLatencyWhenFallbackIsReachable(t *testing.T) {
	var primaryRequests atomic.Int32
	primary := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		primaryRequests.Add(1)
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer primary.Close()

	var fallbackRequests atomic.Int32
	fallback := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		fallbackRequests.Add(1)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer fallback.Close()

	delay, err := probeOutboundWithFallback(context.Background(), newProbeDialOutbound(), primary.URL, fallback.URL, 2*time.Second)
	if err != nil {
		t.Fatalf("expected reachable fallback to succeed, got %v", err)
	}
	if delay == 0 {
		t.Fatal("expected a non-zero latency from the reachable fallback")
	}
	if primaryRequests.Load() == 0 || fallbackRequests.Load() == 0 {
		t.Fatalf("expected both URLs to be probed, primary=%d fallback=%d", primaryRequests.Load(), fallbackRequests.Load())
	}
}

func TestProbeAcceptsHTTPStatusesBelow500(t *testing.T) {
	statuses := []int{
		http.StatusFound,
		http.StatusUnauthorized,
		http.StatusForbidden,
		http.StatusNotFound,
	}
	for _, status := range statuses {
		t.Run(http.StatusText(status), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(status)
			}))
			defer server.Close()

			delay, err := probeSingleURL(context.Background(), newProbeDialOutbound(), server.URL, time.Second)
			if err != nil {
				t.Fatalf("status %d should be accepted by the release probe: %v", status, err)
			}
			if delay == 0 {
				t.Fatalf("status %d returned zero latency", status)
			}
		})
	}
}

func TestProbeRejectsServerError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	delay, err := probeSingleURL(context.Background(), newProbeDialOutbound(), server.URL, time.Second)
	if err == nil {
		t.Fatalf("expected HTTP 500 to fail, got %dms", delay)
	}
	if delay != 0 {
		t.Fatalf("failed HTTP status returned latency %dms", delay)
	}
}

func TestProbeLatencyIncludesResponseBodyRead(t *testing.T) {
	const bodyDelay = 180 * time.Millisecond
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Length", "4")
		w.WriteHeader(http.StatusOK)
		if flusher, ok := w.(http.Flusher); ok {
			flusher.Flush()
		}
		time.Sleep(bodyDelay)
		_, _ = io.WriteString(w, "body")
	}))
	defer server.Close()

	delay, err := probeSingleURL(context.Background(), newProbeDialOutbound(), server.URL, 2*time.Second)
	if err != nil {
		t.Fatalf("probeSingleURL returned an error: %v", err)
	}
	if delay < uint16(bodyDelay.Milliseconds()/2) {
		t.Fatalf("latency %dms did not include the delayed body read", delay)
	}
}

func TestProbeCancellationFallsBackToWarmupLatency(t *testing.T) {
	secondRequestStarted := make(chan struct{})
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if requests.Add(1) == 1 {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		close(secondRequestStarted)
		<-r.Context().Done()
	}))
	defer server.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	cancelAfterSecondRequest := make(chan struct{})
	go func() {
		<-secondRequestStarted
		cancel()
		close(cancelAfterSecondRequest)
	}()

	delay, err := probeSingleURL(ctx, newProbeDialOutbound(), server.URL, 2*time.Second)
	<-cancelAfterSecondRequest
	if err != nil {
		t.Fatalf("release behavior should fall back to the warm-up latency, got %v", err)
	}
	if delay == 0 {
		t.Fatal("cancelled second request should preserve the non-zero warm-up latency")
	}
}

func TestCancelledBatchDoesNotModifyOutboundHistory(t *testing.T) {
	history := singURLTest.NewHistoryStorage()
	history.StoreURLTestHistory("probe", &adapter.URLTestHistory{Time: time.Now(), Delay: 123})

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	result := URLTestOutbounds(ctx, nil, history, boxlog.NewNOPFactory().Logger(), []adapter.Outbound{newProbeDialOutbound()}, "http://127.0.0.1:1", time.Second, true)
	if _, exists := result["probe"]; exists {
		t.Fatal("cancelled measurement must not be returned as a result")
	}
	got := history.LoadURLTestHistory("probe")
	if got == nil || got.Delay != 123 {
		t.Fatalf("cancelled measurement changed outbound history: got %#v, want delay 123ms", got)
	}
}
