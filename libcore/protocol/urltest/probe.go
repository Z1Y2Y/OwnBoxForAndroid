package urltest

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/sagernet/sing-box/adapter"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"golang.org/x/net/http2"
)

const (
	DefaultFallbackURL = "https://www.gstatic.com/generate_204"
	DefaultCFURL       = "https://cp.cloudflare.com/generate_204"
	BrowserUserAgent   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

func GetFallbackLink(primaryLink string) string {
	if strings.Contains(primaryLink, "cloudflare.com") {
		return DefaultFallbackURL
	}
	return DefaultCFURL
}

// ProbeOutbound uses a two-stage keep-alive probe and retries the configured
// URL with the alternate endpoint when the primary endpoint is unavailable.
func ProbeOutbound(ctx context.Context, detour adapter.Outbound, link string, timeout time.Duration) (uint16, error) {
	if detour == nil {
		return 0, E.New("nil detour")
	}
	if link == "" {
		link = DefaultCFURL
	}
	if timeout <= 0 {
		timeout = 5000 * time.Millisecond
	}

	return probeOutboundWithFallback(ctx, detour, link, GetFallbackLink(link), timeout)
}

func probeOutboundWithFallback(parentCtx context.Context, detour adapter.Outbound, link, fallbackLink string, timeout time.Duration) (uint16, error) {
	primaryTimeout := timeout
	fallbackTimeout := 3000 * time.Millisecond
	if timeout > 3500*time.Millisecond {
		primaryTimeout = timeout - 1500*time.Millisecond
	}

	delay, err := probeSingleURL(parentCtx, detour, link, primaryTimeout)
	if err == nil && delay > 0 {
		return delay, nil
	}

	fbDelay, fbErr := probeSingleURL(parentCtx, detour, fallbackLink, fallbackTimeout)
	if fbErr == nil && fbDelay > 0 {
		return fbDelay, nil
	}
	if err != nil {
		return 0, err
	}
	return 0, fbErr
}

func probeSingleURL(parentCtx context.Context, detour adapter.Outbound, link string, timeout time.Duration) (uint16, error) {
	linkURL, err := url.Parse(link)
	if err != nil {
		return 0, E.Cause(err, "parse test link")
	}
	hostname := linkURL.Hostname()

	ctx, cancel := context.WithTimeout(parentCtx, timeout)
	defer cancel()

	transport := &http.Transport{
		DialContext: func(dialCtx context.Context, network, addr string) (net.Conn, error) {
			return detour.DialContext(dialCtx, network, M.ParseSocksaddr(addr))
		},
		TLSClientConfig: &tls.Config{
			ServerName:         hostname,
			InsecureSkipVerify: true,
			NextProtos:         []string{"h2", "http/1.1"},
		},
		ForceAttemptHTTP2: true,
		DisableKeepAlives: false,
		MaxIdleConns:      3,
		IdleConnTimeout:   10 * time.Second,
	}
	_ = http2.ConfigureTransport(transport)
	defer transport.CloseIdleConnections()

	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	// Stage one establishes and warms the proxied HTTP/TLS connection.
	req1, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return 0, err
	}
	req1.Header.Set("User-Agent", BrowserUserAgent)

	start1 := time.Now()
	resp1, err := client.Do(req1)
	if err == nil {
		_, _ = io.CopyN(io.Discard, resp1.Body, 8192)
		_ = resp1.Body.Close()
		if resp1.StatusCode >= 500 {
			err = fmt.Errorf("HTTP error %d", resp1.StatusCode)
		}
	}
	if err != nil {
		return 0, err
	}
	pass1 := time.Since(start1)

	// Stage two reuses the keep-alive connection and records the probe latency.
	req2, err2 := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err2 == nil {
		req2.Header.Set("User-Agent", BrowserUserAgent)
		start2 := time.Now()
		resp2, err2Do := client.Do(req2)
		if err2Do == nil {
			_, _ = io.CopyN(io.Discard, resp2.Body, 8192)
			_ = resp2.Body.Close()
			if resp2.StatusCode < 500 {
				lat := uint16(time.Since(start2).Milliseconds())
				if lat == 0 {
					lat = 1
				}
				return lat, nil
			}
		}
	}

	// If the remote endpoint does not support keep-alive, use the warm-up time.
	lat := uint16(pass1.Milliseconds())
	if lat == 0 {
		lat = 1
	}
	return lat, nil
}
