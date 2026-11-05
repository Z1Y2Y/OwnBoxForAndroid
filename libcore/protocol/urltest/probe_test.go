package urltest

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
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
		{name: "cloudflare host", link: "https://cp.cloudflare.com/generate_204", want: DefaultFallbackURL},
		{name: "cloudflare subdomain", link: "https://api.cloudflare.com/generate_204", want: DefaultFallbackURL},
		{name: "other host", link: "https://www.gstatic.com/generate_204", want: DefaultCFURL},
		{name: "lookalike host", link: "https://notcloudflare.com/generate_204", want: DefaultCFURL},
		{name: "path/query text is irrelevant", link: "https://example.com/generate_204?next=cloudflare.com", want: DefaultCFURL},
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

func TestProbeFallbackDoesNotReportFallbackLatencyAsPrimary(t *testing.T) {
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
	if err == nil {
		t.Fatal("expected the configured URL failure to remain a failure")
	}
	if delay != 0 {
		t.Fatalf("fallback latency must not be returned as primary latency, got %dms", delay)
	}
	if !strings.Contains(err.Error(), "fallback URL is reachable") {
		t.Fatalf("expected diagnostic fallback information, got %v", err)
	}
	if primaryRequests.Load() == 0 || fallbackRequests.Load() == 0 {
		t.Fatalf("expected both URLs to be probed, primary=%d fallback=%d", primaryRequests.Load(), fallbackRequests.Load())
	}
}

func TestProbeRejectsNon2xxHTTPStatus(t *testing.T) {
	tests := []struct {
		name   string
		status int
	}{
		{name: "redirect", status: http.StatusFound},
		{name: "unauthorized", status: http.StatusUnauthorized},
		{name: "forbidden", status: http.StatusForbidden},
		{name: "not found", status: http.StatusNotFound},
		{name: "server error", status: http.StatusInternalServerError},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(test.status)
			}))
			defer server.Close()

			delay, err := probeSingleURL(context.Background(), newProbeDialOutbound(), server.URL, time.Second)
			if err == nil {
				t.Fatalf("status %d must not be a successful probe (delay=%dms)", test.status, delay)
			}
			if delay != 0 {
				t.Fatalf("failed HTTP status returned latency %dms", delay)
			}
		})
	}
}

func TestProbeMeasuresResponseHeadersNotBodyTransfer(t *testing.T) {
	const bodyDelay = 250 * time.Millisecond
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
	if delay >= uint16(bodyDelay.Milliseconds()/2) {
		t.Fatalf("latency %dms includes delayed response-body transfer; expected header TTFB below %dms", delay, bodyDelay.Milliseconds()/2)
	}
}

func TestProbeCancellationDoesNotReturnWarmupLatency(t *testing.T) {
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
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected cancellation to remain an error, got delay=%dms err=%v", delay, err)
	}
	if delay != 0 {
		t.Fatalf("cancelled probe returned warm-up latency %dms", delay)
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

func TestProbeFallbackUsesRemainingOverallTimeout(t *testing.T) {
	primary := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer primary.Close()

	fallback := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-r.Context().Done():
			return
		case <-time.After(time.Second):
			w.WriteHeader(http.StatusNoContent)
		}
	}))
	defer fallback.Close()

	started := time.Now()
	_, err := probeOutboundWithFallback(context.Background(), newProbeDialOutbound(), primary.URL, fallback.URL, 120*time.Millisecond)
	elapsed := time.Since(started)
	if err == nil {
		t.Fatal("expected the fallback request to time out")
	}
	if elapsed > 350*time.Millisecond {
		t.Fatalf("primary + fallback exceeded the 120ms overall budget: elapsed %s", elapsed)
	}
}
