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
	parsed, err := url.Parse(primaryLink)
	if err == nil {
		host := strings.TrimSuffix(strings.ToLower(parsed.Hostname()), ".")
		if host == "cloudflare.com" || strings.HasSuffix(host, ".cloudflare.com") {
			return DefaultFallbackURL
		}
	}
	return DefaultCFURL
}

// ProbeOutbound warms the destination connection, then measures response-header
// TTFB over the existing detour. The fallback URL only diagnoses reachability;
// its latency is never reported as the configured URL's result.
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
	// Treat timeout as a budget for the complete primary + fallback attempt, not
	// as a fresh allowance for each URL.
	ctx, cancel := context.WithTimeout(parentCtx, timeout)
	defer cancel()

	primaryTimeout := timeout
	if timeout > 3500*time.Millisecond {
		primaryTimeout = timeout - 1500*time.Millisecond
	}

	delay, err := probeSingleURL(ctx, detour, link, primaryTimeout)
	if err == nil && delay > 0 {
		return delay, nil
	}
	if err == nil {
		err = fmt.Errorf("primary probe returned no latency")
	}
	if ctx.Err() != nil {
		return 0, ctx.Err()
	}

	// Keep fallback as a connectivity diagnostic only. Its RTT is for a
	// different host and must not be reported as the configured URL's latency.
	fallbackTimeout := 3000 * time.Millisecond
	if deadline, ok := ctx.Deadline(); ok {
		remaining := time.Until(deadline)
		if remaining < fallbackTimeout {
			fallbackTimeout = remaining
		}
	}
	if fallbackTimeout <= 0 {
		return 0, err
	}
	fbDelay, fbErr := probeSingleURL(ctx, detour, fallbackLink, fallbackTimeout)
	if ctx.Err() != nil {
		return 0, ctx.Err()
	}
	if fbErr == nil && fbDelay > 0 {
		return 0, fmt.Errorf("primary test URL failed: %w; fallback URL is reachable (%dms), but its latency is not used", err, fbDelay)
	}

	if fbErr != nil {
		return 0, fmt.Errorf("primary test URL failed: %w; fallback test URL failed: %v", err, fbErr)
	}
	return 0, err
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
		if resp1.StatusCode < http.StatusOK || resp1.StatusCode >= http.StatusMultipleChoices {
			err = fmt.Errorf("HTTP error %d", resp1.StatusCode)
		}
	}
	if err != nil {
		return 0, err
	}
	if ctx.Err() != nil {
		return 0, ctx.Err()
	}
	pass1 := time.Since(start1)

	// Stage two records the response-header TTFB; body transfer is excluded.
	req2, err2 := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err2 == nil {
		req2.Header.Set("User-Agent", BrowserUserAgent)
		start2 := time.Now()
		resp2, err2Do := client.Do(req2)
		if err2Do == nil {
			elapsed := time.Since(start2)
			statusCode := resp2.StatusCode
			_ = resp2.Body.Close()
			if ctx.Err() != nil {
				return 0, ctx.Err()
			}
			if statusCode < http.StatusOK || statusCode >= http.StatusMultipleChoices {
				return 0, fmt.Errorf("HTTP error %d", statusCode)
			}
			lat := uint16(elapsed.Milliseconds())
			if lat == 0 {
				lat = 1
			}
			return lat, nil
		}
	}

	if ctx.Err() != nil {
		return 0, ctx.Err()
	}

	// Preserve the warm-up result only for a non-cancellation second-request
	// failure (for example, a peer that closes keep-alive connections).
	lat := uint16(pass1.Milliseconds())
	if lat == 0 {
		lat = 1
	}
	return lat, nil
}
