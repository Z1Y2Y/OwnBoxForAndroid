package autoselect

import (
	"context"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common/metadata"
)

func contextForApp(packageNames ...string) context.Context {
	inbound := &adapter.InboundContext{
		ProcessInfo: &adapter.ConnectionOwner{PackageNames: packageNames},
	}
	return adapter.WithContext(context.Background(), inbound)
}

func newStickyAutoSelect(t *testing.T, members map[string]adapter.Outbound, tags []string) *AutoSelect {
	t.Helper()
	mgr := &mockOutboundManager{items: members}
	ctx := newAutoSelectTestContext(mgr)
	ob, err := NewAutoSelect(ctx, nil, log.NewNOPFactory().Logger(), "strategy-sticky", AutoSelectOptions{
		Outbounds:   tags,
		StickyByApp: true,
	})
	if err != nil {
		t.Fatalf("NewAutoSelect error: %v", err)
	}
	return ob
}

func TestAutoSelectStickyByAppPinsSuccessfulMember(t *testing.T) {
	var dials1, dials2 atomic.Int32
	m1 := newMockMember("sg-1")
	m1.dialFn = func(ctx context.Context, network string, destination metadata.Socksaddr) (net.Conn, error) {
		dials1.Add(1)
		c1, c2 := net.Pipe()
		_ = c2.Close()
		return c1, nil
	}
	m2 := newMockMember("sg-2")
	m2.dialFn = func(ctx context.Context, network string, destination metadata.Socksaddr) (net.Conn, error) {
		dials2.Add(1)
		c1, c2 := net.Pipe()
		_ = c2.Close()
		return c1, nil
	}
	ob := newStickyAutoSelect(t, map[string]adapter.Outbound{"sg-1": m1, "sg-2": m2}, []string{"sg-1", "sg-2"})

	now := time.Now()
	ob.scorer.recordSample("sg-1", 45, nil, now)
	ob.scorer.recordSample("sg-2", 90, nil, now)
	ob.scorer.evaluate(now, ob.passive)

	dest := metadata.Socksaddr{Fqdn: "example.com", Port: 443}
	appCtx := contextForApp("com.example.app")

	conn, err := ob.DialContext(appCtx, "tcp", dest)
	if err != nil {
		t.Fatalf("first dial error: %v", err)
	}
	_ = conn.Close()
	if dials1.Load() != 1 || dials2.Load() != 0 {
		t.Fatalf("first dial must go to sg-1, got sg-1=%d sg-2=%d", dials1.Load(), dials2.Load())
	}

	// Flip the scorer preference: sg-2 is now clearly faster.
	now = time.Now()
	ob.scorer.recordSample("sg-1", 500, nil, now)
	ob.scorer.recordSample("sg-2", 40, nil, now)
	ob.scorer.evaluate(now, ob.passive)

	conn, err = ob.DialContext(appCtx, "tcp", dest)
	if err != nil {
		t.Fatalf("second dial error: %v", err)
	}
	_ = conn.Close()
	if dials1.Load() != 2 {
		t.Fatalf("sticky app must keep dialing sg-1 after scorer flip, got sg-1=%d sg-2=%d", dials1.Load(), dials2.Load())
	}

	// A different app has no binding and follows the scorer.
	otherCtx := contextForApp("com.other.app")
	conn, err = ob.DialContext(otherCtx, "tcp", dest)
	if err != nil {
		t.Fatalf("other-app dial error: %v", err)
	}
	_ = conn.Close()
	if dials2.Load() != 1 {
		t.Fatalf("other app must follow scorer to sg-2, got sg-1=%d sg-2=%d", dials1.Load(), dials2.Load())
	}
}

func TestAutoSelectStickyByAppDropsMissingMember(t *testing.T) {
	m1 := newMockMember("sg-1")
	m2 := newMockMember("sg-2")
	ob := newStickyAutoSelect(t, map[string]adapter.Outbound{"sg-1": m1, "sg-2": m2}, []string{"sg-1", "sg-2"})

	ctx := contextForApp("com.example.app")
	key := autoSelectAppStickyKey(ctx)
	if key == "" {
		t.Fatal("expected non-empty app sticky key")
	}
	ob.bindAppStickyMember(key, "gone-tag")
	_, ordered := ob.appStickyCandidates(ctx, []string{"sg-1", "sg-2"})
	if len(ordered) != 2 || ordered[0] != "sg-1" || ordered[1] != "sg-2" {
		t.Fatalf("binding to a missing member must be dropped, got %v", ordered)
	}
	ob.appStickyMu.RLock()
	_, stillBound := ob.appSticky[key]
	ob.appStickyMu.RUnlock()
	if stillBound {
		t.Fatal("dropped binding must be removed from the map")
	}

	// Sticky disabled: bind is a no-op and candidates pass through untouched.
	mgr := &mockOutboundManager{items: map[string]adapter.Outbound{"sg-1": m1, "sg-2": m2}}
	plainCtx := newAutoSelectTestContext(mgr)
	plain, err := NewAutoSelect(plainCtx, nil, log.NewNOPFactory().Logger(), "strategy-plain", AutoSelectOptions{
		Outbounds: []string{"sg-1", "sg-2"},
	})
	if err != nil {
		t.Fatalf("NewAutoSelect error: %v", err)
	}
	plain.bindAppStickyMember(key, "sg-2")
	returnedKey, ordered := plain.appStickyCandidates(ctx, []string{"sg-1", "sg-2"})
	if returnedKey != "" || len(ordered) != 2 || ordered[0] != "sg-1" {
		t.Fatalf("sticky disabled must pass candidates through, got key=%q %v", returnedKey, ordered)
	}
}
