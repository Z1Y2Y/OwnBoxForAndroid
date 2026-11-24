package autoselect

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"libcore/protocol/appsticky"
)

type stickyTestOutbound struct {
	*mockMemberOutbound
	dialCalls   atomic.Int64
	packetCalls atomic.Int64
	dialErr     atomic.Pointer[error]
	packetErr   atomic.Pointer[error]
}

func newStickyTestOutbound(tag string) *stickyTestOutbound {
	member := &stickyTestOutbound{mockMemberOutbound: newMockMember(tag)}
	member.dialFn = func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
		member.dialCalls.Add(1)
		if err := member.dialErr.Load(); err != nil {
			return nil, *err
		}
		client, peer := net.Pipe()
		_ = peer.Close()
		return client, nil
	}
	return member
}

func contextWithStickyTestApp(packageNames ...string) context.Context {
	metadata := &adapter.InboundContext{ProcessInfo: &adapter.ConnectionOwner{PackageNames: packageNames}}
	return adapter.WithContext(context.Background(), metadata)
}

func newStickyAutoSelect(t *testing.T, options AutoSelectOptions, members ...*stickyTestOutbound) *AutoSelect {
	t.Helper()
	items := make(map[string]adapter.Outbound, len(members))
	for _, member := range members {
		items[member.Tag()] = member
	}
	ctx := newAutoSelectTestContext(&mockOutboundManager{items: items})
	group, err := NewAutoSelect(ctx, nil, log.NewNOPFactory().Logger(), "sticky-test", options)
	if err != nil {
		t.Fatalf("NewAutoSelect: %v", err)
	}
	return group
}

func setStickyTestHealthy(g *AutoSelect, tags ...string) {
	now := time.Now()
	for i, tag := range tags {
		g.scorer.recordSample(tag, uint16(40+i*10), nil, now)
	}
	g.scorer.evaluate(now, g.passive)
}

func TestAutoSelectStickyByAppConfigIsOptInAndSerializationFriendly(t *testing.T) {
	var defaults AutoSelectOptions
	if defaults.normalized().stickyByApp {
		t.Fatal("sticky_by_app must default to false")
	}
	encoded, err := json.Marshal(AutoSelectOptions{Outbounds: []string{"a", "b"}, StickyByApp: true})
	if err != nil {
		t.Fatalf("marshal options: %v", err)
	}
	var decoded AutoSelectOptions
	if err := json.Unmarshal(encoded, &decoded); err != nil {
		t.Fatalf("unmarshal options: %v", err)
	}
	if !decoded.StickyByApp || !decoded.normalized().stickyByApp {
		t.Fatalf("sticky_by_app did not survive JSON round-trip: %s", encoded)
	}
	var serialized map[string]any
	if err := json.Unmarshal(encoded, &serialized); err != nil {
		t.Fatalf("decode options JSON: %v", err)
	}
	if serialized["sticky_by_app"] != true {
		t.Fatalf("expected JSON sticky_by_app=true, got %v", serialized)
	}
}

func TestAutoSelectStickyByAppKeepsTCPMemberAcrossPriorityAndFallbackFailback(t *testing.T) {
	for _, mode := range []string{SelectionModePriority, SelectionModeFallback} {
		t.Run(mode, func(t *testing.T) {
			a, b := newStickyTestOutbound("a"), newStickyTestOutbound("b")
			g := newStickyAutoSelect(t, AutoSelectOptions{
				Outbounds: []string{"a", "b"}, SelectionMode: mode, StickyByApp: true,
			}, a, b)
			defer g.Close()
			now := time.Now()
			g.scorer.recordSample("a", 0, errTestFail, now)
			if mode == SelectionModeFallback {
				g.scorer.recordSample("a", 0, errTestFail, now.Add(time.Second))
			}
			g.scorer.recordSample("b", 80, nil, now)
			g.scorer.evaluate(now.Add(time.Second), nil)
			if got := g.Now(); got != "b" {
				t.Fatalf("expected initial failover to b, got %q", got)
			}

			appA := contextWithStickyTestApp("Com.Example.Chat", "com.example.chat")
			conn, err := g.DialContext(appA, N.NetworkTCP, M.ParseSocksaddrHostPort("one.example", 443))
			if err != nil {
				t.Fatalf("initial app dial: %v", err)
			}
			_ = conn.Close()
			if g.appSticky[autoSelectAppStickyKey(appA)] != "b" {
				t.Fatalf("expected successful first dial to bind app to b, map=%v", g.appSticky)
			}

			// Recovery changes the group's global active member, but not this app's binding.
			now = now.Add(2 * time.Second)
			g.scorer.recordSample("a", 45, nil, now)
			g.scorer.evaluate(now, nil)
			if got := g.Now(); got != "a" {
				t.Fatalf("expected global %s failback to a, got %q", mode, got)
			}
			conn, err = g.DialContext(appA, N.NetworkTCP, M.ParseSocksaddrHostPort("two.example", 443))
			if err != nil {
				t.Fatalf("sticky app dial after failback: %v", err)
			}
			_ = conn.Close()
			if b.dialCalls.Load() != 2 || a.dialCalls.Load() != 0 {
				t.Fatalf("global failback stole app traffic: a=%d b=%d", a.dialCalls.Load(), b.dialCalls.Load())
			}

			// Another app has no binding and therefore follows the updated global scorer.
			other := contextWithStickyTestApp("com.example.browser")
			conn, err = g.DialContext(other, N.NetworkTCP, M.ParseSocksaddrHostPort("three.example", 443))
			if err != nil {
				t.Fatalf("other app dial: %v", err)
			}
			_ = conn.Close()
			if a.dialCalls.Load() != 1 || g.appSticky[autoSelectAppStickyKey(other)] != "a" {
				t.Fatalf("expected distinct app to follow global a and bind independently, a=%d bindings=%v", a.dialCalls.Load(), g.appSticky)
			}
		})
	}
}

func TestAutoSelectStickyByAppFailedPinnedNodeOnlyRebindsAfterSuccessfulBackup(t *testing.T) {
	a, b := newStickyTestOutbound("a"), newStickyTestOutbound("b")
	g := newStickyAutoSelect(t, AutoSelectOptions{Outbounds: []string{"a", "b"}, StickyByApp: true}, a, b)
	defer g.Close()
	setStickyTestHealthy(g, "a", "b")
	app := contextWithStickyTestApp("com.example.chat")

	conn, err := g.DialContext(app, N.NetworkTCP, M.ParseSocksaddrHostPort("seed.example", 443))
	if err != nil {
		t.Fatalf("seed dial: %v", err)
	}
	_ = conn.Close()
	key := autoSelectAppStickyKey(app)
	if g.appSticky[key] != "a" {
		t.Fatalf("expected initial binding to a, got %v", g.appSticky)
	}

	dialErr := errors.New("node unavailable")
	a.dialErr.Store(&dialErr)
	b.dialErr.Store(&dialErr)
	if conn, err = g.DialContext(app, N.NetworkTCP, M.ParseSocksaddrHostPort("failed.example", 443)); err == nil {
		_ = conn.Close()
		t.Fatal("expected all member attempts to fail")
	}
	if g.appSticky[key] != "a" {
		t.Fatalf("failed attempts must not update the existing app binding, got %v", g.appSticky)
	}

	b.dialErr.Store(nil)
	conn, err = g.DialContext(app, N.NetworkTCP, M.ParseSocksaddrHostPort("recovered.example", 443))
	if err != nil {
		t.Fatalf("expected successful backup after pinned failure: %v", err)
	}
	_ = conn.Close()
	if g.appSticky[key] != "b" {
		t.Fatalf("expected successful backup b to replace binding, got %v", g.appSticky)
	}
}

func TestAutoSelectStickyByAppListenPacketAndNoAppInfoFallback(t *testing.T) {
	a, b := newStickyTestOutbound("a"), newStickyTestOutbound("b")
	for _, member := range []*stickyTestOutbound{a, b} {
		member.mockMemberOutbound.packetFn = func(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
			member.packetCalls.Add(1)
			if err := member.packetErr.Load(); err != nil {
				return nil, *err
			}
			return net.ListenPacket("udp", "127.0.0.1:0")
		}
	}
	g := newStickyAutoSelect(t, AutoSelectOptions{Outbounds: []string{"a", "b"}, StickyByApp: true}, a, b)
	defer g.Close()
	setStickyTestHealthy(g, "a", "b")
	app := contextWithStickyTestApp("com.example.chat")

	packetConn, err := g.ListenPacket(app, M.ParseSocksaddrHostPort("seed.example", 443))
	if err != nil {
		t.Fatalf("initial UDP listener: %v", err)
	}
	_ = packetConn.Close()
	key := autoSelectAppStickyKey(app)
	if g.appSticky[key] != "a" {
		t.Fatalf("expected UDP success to bind app to a, got %v", g.appSticky)
	}

	// Move the global carrier to b. The app's UDP listener still starts on a.
	g.scorer.mu.Lock()
	g.scorer.activeTag = "b"
	g.scorer.mu.Unlock()
	packetConn, err = g.ListenPacket(app, M.ParseSocksaddrHostPort("next.example", 443))
	if err != nil {
		t.Fatalf("sticky UDP listener: %v", err)
	}
	_ = packetConn.Close()
	if a.packetCalls.Load() != 2 || b.packetCalls.Load() != 0 {
		t.Fatalf("UDP app binding did not survive global carrier change: a=%d b=%d", a.packetCalls.Load(), b.packetCalls.Load())
	}

	packetErr := errors.New("UDP listener unavailable")
	a.packetErr.Store(&packetErr)
	b.packetErr.Store(&packetErr)
	if failedConn, failErr := g.ListenPacket(app, M.ParseSocksaddrHostPort("udp-failed.example", 443)); failErr == nil {
		_ = failedConn.Close()
		t.Fatal("expected pinned and backup UDP listener attempts to fail")
	}
	if g.appSticky[key] != "a" {
		t.Fatalf("failed UDP attempts must not replace app binding, got %v", g.appSticky)
	}
	b.packetErr.Store(nil)
	packetConn, err = g.ListenPacket(app, M.ParseSocksaddrHostPort("udp-recovered.example", 443))
	if err != nil {
		t.Fatalf("expected backup UDP listener to succeed: %v", err)
	}
	_ = packetConn.Close()
	if g.appSticky[key] != "b" {
		t.Fatalf("successful UDP backup should update app binding to b, got %v", g.appSticky)
	}

	// With no process/package metadata, enabled mode must preserve the original global selection.
	g.scorer.mu.Lock()
	g.scorer.activeTag = "b"
	g.scorer.mu.Unlock()
	plainConn, err := g.DialContext(context.Background(), N.NetworkTCP, M.ParseSocksaddrHostPort("plain.example", 443))
	if err != nil {
		t.Fatalf("dial without app metadata: %v", err)
	}
	_ = plainConn.Close()
	if b.dialCalls.Load() != 1 {
		t.Fatalf("no-app request should follow global scorer b, b dials=%d", b.dialCalls.Load())
	}
}

func TestAutoSelectStickyPersistenceRestoresUpdatesAndDisabledClears(t *testing.T) {
	path := filepath.Join(t.TempDir(), "auto", "sticky.json")
	a, b := newStickyTestOutbound("a"), newStickyTestOutbound("b")
	opts := AutoSelectOptions{Outbounds: []string{"a", "b"}, StickyByApp: true, StickyStorePath: path, PersistStickyByApp: true}
	g := newStickyAutoSelect(t, opts, a, b)
	setStickyTestHealthy(g, "a", "b")
	app := contextWithStickyTestApp("com.example.persist")
	conn, err := g.DialContext(app, N.NetworkTCP, M.ParseSocksaddrHostPort("seed.example", 443))
	if err != nil {
		t.Fatalf("initial successful dial: %v", err)
	}
	_ = conn.Close()
	if got := g.appSticky[autoSelectAppStickyKey(app)]; got != "a" {
		t.Fatalf("expected initial binding to a, got %q", got)
	}
	dialErr := errors.New("pinned member unavailable")
	a.dialErr.Store(&dialErr)
	conn, err = g.DialContext(app, N.NetworkTCP, M.ParseSocksaddrHostPort("fallback.example", 443))
	if err != nil {
		t.Fatalf("fallback dial: %v", err)
	}
	_ = conn.Close()
	key := autoSelectAppStickyKey(app)
	if g.appSticky[key] != "b" {
		t.Fatalf("expected fallback member b, got %v", g.appSticky)
	}
	g.Close()

	store, err := appsticky.Open(path, true)
	if err != nil {
		t.Fatal(err)
	}
	if entry := store.Snapshot(time.Now(), 0)[key]; entry.Tag != "b" {
		t.Fatalf("successful fallback not persisted: %#v", entry)
	}
	newGroup := newStickyAutoSelect(t, opts, newStickyTestOutbound("a"), newStickyTestOutbound("b"))
	if got := newGroup.appSticky[key]; got != "b" {
		t.Fatalf("restarted group did not restore app tag b: %v", newGroup.appSticky)
	}
	newGroup.Close()

	off := opts
	off.PersistStickyByApp = false
	disabledGroup := newStickyAutoSelect(t, off, newStickyTestOutbound("a"), newStickyTestOutbound("b"))
	defer disabledGroup.Close()
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("disabled persistence did not remove stale file: %v", err)
	}
	if len(disabledGroup.appSticky) != 0 {
		t.Fatalf("disabled persistence restored old bindings: %v", disabledGroup.appSticky)
	}
}
