package loadbalance

import (
	"context"
	"errors"
	"net"
	"runtime"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/interrupt"
	M "github.com/sagernet/sing/common/metadata"
)

type stickyConcurrentOutbound struct {
	tag            string
	calls          atomic.Int32
	entered        chan struct{}
	release        chan struct{}
	firstErr       error
	alwaysErr      error
	ignoreCanceled bool
}

func (o *stickyConcurrentOutbound) Type() string           { return "test" }
func (o *stickyConcurrentOutbound) Tag() string            { return o.tag }
func (o *stickyConcurrentOutbound) Network() []string      { return []string{"tcp", "udp"} }
func (o *stickyConcurrentOutbound) Dependencies() []string { return nil }

func (o *stickyConcurrentOutbound) beforeDial(ctx context.Context, attempt int32) error {
	if attempt == 1 && o.entered != nil {
		close(o.entered)
		if o.release != nil {
			if o.ignoreCanceled {
				<-o.release
			} else {
				select {
				case <-o.release:
				case <-ctx.Done():
					return ctx.Err()
				}
			}
		}
		if o.firstErr != nil {
			return o.firstErr
		}
	}
	if o.alwaysErr != nil {
		return o.alwaysErr
	}
	return nil
}

func (o *stickyConcurrentOutbound) DialContext(ctx context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
	attempt := o.calls.Add(1)
	if err := o.beforeDial(ctx, attempt); err != nil {
		return nil, err
	}
	conn, peer := net.Pipe()
	_ = peer.Close()
	return conn, nil
}

func (o *stickyConcurrentOutbound) ListenPacket(ctx context.Context, _ M.Socksaddr) (net.PacketConn, error) {
	attempt := o.calls.Add(1)
	if err := o.beforeDial(ctx, attempt); err != nil {
		return nil, err
	}
	return net.ListenPacket("udp", "127.0.0.1:0")
}

func newStickyConcurrentLoadBalance(node0, node1 *stickyConcurrentOutbound) *LoadBalance {
	tags := []string{node0.tag, node1.tag}
	return &LoadBalance{
		tags:           tags,
		strategy:       "roundRobin",
		stickyByApp:    true,
		outbounds:      []adapter.Outbound{node0, node1},
		stats:          []*nodeStats{new(nodeStats), new(nodeStats)},
		interruptGroup: interrupt.NewGroup(),
		stickySessions: make(map[string]stickyEntry),
		stickyFlights:  make(map[string]*stickyFlight),
	}
}

func waitForStickyFlightWaiter(t *testing.T, lb *LoadBalance, key string) *stickyFlight {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		lb.stickyMu.Lock()
		flight := lb.stickyFlights[key]
		lb.stickyMu.Unlock()
		if flight == nil {
			t.Fatal("expected the first sticky dial flight to remain active")
		}
		if flight.waiters.Load() > 0 {
			return flight
		}
		runtime.Gosched()
	}
	t.Fatal("timed out waiting for the concurrent dial to join the sticky flight")
	return nil
}

type stickyConcurrentResult struct {
	conn       net.Conn
	packetConn net.PacketConn
	err        error
}

func exerciseStickyConcurrentFirstDial(t *testing.T, udp bool, firstErr error) {
	t.Helper()
	node0 := &stickyConcurrentOutbound{tag: "node-0"}
	node1 := &stickyConcurrentOutbound{
		tag:      "node-1",
		entered:  make(chan struct{}),
		release:  make(chan struct{}),
		firstErr: firstErr,
	}
	lb := newStickyConcurrentLoadBalance(node0, node1)
	ctx := contextForApp("com.example.cue")
	results := make(chan stickyConcurrentResult, 2)
	call := func(destination M.Socksaddr) {
		if udp {
			conn, err := lb.ListenPacket(ctx, destination)
			results <- stickyConcurrentResult{packetConn: conn, err: err}
			return
		}
		conn, err := lb.DialContext(ctx, "tcp", destination)
		results <- stickyConcurrentResult{conn: conn, err: err}
	}

	go call(M.Socksaddr{Fqdn: "api.example.com"})
	select {
	case <-node1.entered:
	case <-time.After(2 * time.Second):
		t.Fatal("first candidate did not enter its controlled dial")
	}
	key := appStickyKey(ctx)
	go call(M.Socksaddr{Fqdn: "updates.example.net"})
	waitForStickyFlightWaiter(t, lb, key)

	// The waiter is known to be inside beginStickyDial before the leader is released.
	close(node1.release)
	for i := 0; i < 2; i++ {
		select {
		case result := <-results:
			if result.err != nil {
				t.Fatalf("expected a successful %s connection, got %v", map[bool]string{true: "UDP", false: "TCP"}[udp], result.err)
			}
			if result.conn != nil {
				_ = result.conn.Close()
			}
			if result.packetConn != nil {
				_ = result.packetConn.Close()
			}
		case <-time.After(2 * time.Second):
			t.Fatal("timed out waiting for concurrent sticky dials")
		}
	}

	wantIdx := 1
	wantNode0Calls := int32(0)
	wantNode1Calls := int32(2)
	if firstErr != nil {
		wantIdx = 0
		wantNode0Calls = 2
		wantNode1Calls = 1
	}
	if got := node0.calls.Load(); got != wantNode0Calls {
		t.Fatalf("unexpected node-0 attempts: got %d, want %d", got, wantNode0Calls)
	}
	if got := node1.calls.Load(); got != wantNode1Calls {
		t.Fatalf("unexpected node-1 attempts: got %d, want %d", got, wantNode1Calls)
	}
	if got, ok := lb.getStickySession(key, time.Now().UnixMilli()); !ok || got != wantIdx {
		t.Fatalf("expected successful member %d to be pinned, got member=%d present=%v", wantIdx, got, ok)
	}
}

func TestStickyByAppConcurrentInitialDialsShareSuccessfulTCPAndUDPMember(t *testing.T) {
	for _, udp := range []bool{false, true} {
		transport := "tcp"
		if udp {
			transport = "udp"
		}
		t.Run(transport+"_first_member_succeeds", func(t *testing.T) {
			exerciseStickyConcurrentFirstDial(t, udp, nil)
		})
		t.Run(transport+"_first_member_fails_then_fallback_succeeds", func(t *testing.T) {
			exerciseStickyConcurrentFirstDial(t, udp, syscall.ECONNREFUSED)
		})
	}
}

func TestStickyByAppCancelledOldInitialDialCannotOverwriteNewBinding(t *testing.T) {
	node0 := &stickyConcurrentOutbound{tag: "node-0"}
	node1 := &stickyConcurrentOutbound{
		tag:            "node-1",
		entered:        make(chan struct{}),
		release:        make(chan struct{}),
		ignoreCanceled: true,
	}
	lb := newStickyConcurrentLoadBalance(node0, node1)
	leaderCtx, cancelLeader := context.WithCancel(contextForApp("com.example.cue"))
	defer cancelLeader()
	key := appStickyKey(leaderCtx)
	leaderResult := make(chan stickyConcurrentResult, 1)
	go func() {
		conn, err := lb.DialContext(leaderCtx, "tcp", M.Socksaddr{Fqdn: "api.example.com"})
		leaderResult <- stickyConcurrentResult{conn: conn, err: err}
	}()
	select {
	case <-node1.entered:
	case <-time.After(2 * time.Second):
		t.Fatal("leader did not enter its controlled dial")
	}

	followerResult := make(chan stickyConcurrentResult, 1)
	followerCtx := contextForApp("com.example.cue")
	go func() {
		conn, err := lb.DialContext(followerCtx, "tcp", M.Socksaddr{Fqdn: "updates.example.net"})
		followerResult <- stickyConcurrentResult{conn: conn, err: err}
	}()
	waitForStickyFlightWaiter(t, lb, key)
	cancelLeader()

	select {
	case result := <-followerResult:
		if result.err != nil {
			t.Fatalf("expected waiter to take over after leader cancellation, got %v", result.err)
		}
		if result.conn != nil {
			_ = result.conn.Close()
		}
	case <-time.After(2 * time.Second):
		t.Fatal("waiter did not take over the canceled initial dial")
	}
	if got, ok := lb.getStickySession(key, time.Now().UnixMilli()); !ok || got != 0 {
		t.Fatalf("expected new successful member 0 to be pinned, got member=%d present=%v", got, ok)
	}

	// This outbound deliberately ignores cancellation and succeeds late. It must not
	// return a usable connection or replace the newer successful binding.
	close(node1.release)
	select {
	case result := <-leaderResult:
		if !errors.Is(result.err, context.Canceled) {
			t.Fatalf("expected canceled old leader to return context.Canceled, got conn=%v err=%v", result.conn, result.err)
		}
		if result.conn != nil {
			_ = result.conn.Close()
			t.Fatal("canceled old leader returned a live connection")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("old leader did not exit after its outbound completed")
	}
	if got, ok := lb.getStickySession(key, time.Now().UnixMilli()); !ok || got != 0 {
		t.Fatalf("late old success replaced the newer binding: member=%d present=%v", got, ok)
	}
}

func TestStickyByAppFlightsAreScopedAndExistingBindingIsNotSerialized(t *testing.T) {
	node0 := &stickyConcurrentOutbound{tag: "node-0"}
	node1 := &stickyConcurrentOutbound{tag: "node-1"}
	lb := newStickyConcurrentLoadBalance(node0, node1)
	ctxA := contextForApp("com.example.app-a")
	ctxB := contextForApp("com.example.app-b")

	flightA, _, err := lb.beginStickyDial(ctxA, appStickyKey(ctxA))
	if err != nil || flightA == nil {
		t.Fatalf("expected app A to own its initial flight, flight=%v err=%v", flightA, err)
	}
	defer lb.finishStickyDial(appStickyKey(ctxA), flightA, -1)
	flightB, _, err := lb.beginStickyDial(ctxB, appStickyKey(ctxB))
	if err != nil || flightB == nil {
		t.Fatalf("different app should start independently, flight=%v err=%v", flightB, err)
	}
	defer lb.finishStickyDial(appStickyKey(ctxB), flightB, -1)

	otherGroup := newStickyConcurrentLoadBalance(node0, node1)
	flightOtherGroup, _, err := otherGroup.beginStickyDial(ctxA, appStickyKey(ctxA))
	if err != nil || flightOtherGroup == nil {
		t.Fatalf("different LoadBalance instance should start independently, flight=%v err=%v", flightOtherGroup, err)
	}
	defer otherGroup.finishStickyDial(appStickyKey(ctxA), flightOtherGroup, -1)

	lb.finishStickyDial(appStickyKey(ctxA), flightA, 1)
	lb.setStickySession(appStickyKey(ctxB), 0)
	for i := 0; i < 2; i++ {
		flight, _, err := lb.beginStickyDial(ctxA, appStickyKey(ctxA))
		if err != nil || flight != nil {
			t.Fatalf("existing binding should not create a serializing flight, flight=%v err=%v", flight, err)
		}
	}
}

func exerciseStickyAllFailedThenTakeover(t *testing.T, udp bool) {
	t.Helper()
	node0 := &stickyConcurrentOutbound{tag: "node-0", alwaysErr: syscall.ECONNREFUSED}
	node1 := &stickyConcurrentOutbound{
		tag:      "node-1",
		entered:  make(chan struct{}),
		release:  make(chan struct{}),
		firstErr: syscall.ECONNREFUSED,
	}
	lb := newStickyConcurrentLoadBalance(node0, node1)
	ctx := contextForApp("com.example.cue")
	results := make(chan stickyConcurrentResult, 2)
	call := func(destination M.Socksaddr) {
		if udp {
			conn, err := lb.ListenPacket(ctx, destination)
			results <- stickyConcurrentResult{packetConn: conn, err: err}
			return
		}
		conn, err := lb.DialContext(ctx, "tcp", destination)
		results <- stickyConcurrentResult{conn: conn, err: err}
	}

	go call(M.Socksaddr{Fqdn: "api.example.com"})
	select {
	case <-node1.entered:
	case <-time.After(2 * time.Second):
		t.Fatal("first leader did not enter its controlled dial")
	}
	key := appStickyKey(ctx)
	go call(M.Socksaddr{Fqdn: "updates.example.net"})
	waitForStickyFlightWaiter(t, lb, key)
	close(node1.release)

	var successes, failures int
	for i := 0; i < 2; i++ {
		select {
		case result := <-results:
			if result.err != nil {
				failures++
			} else {
				successes++
			}
			if result.conn != nil {
				_ = result.conn.Close()
			}
			if result.packetConn != nil {
				_ = result.packetConn.Close()
			}
		case <-time.After(2 * time.Second):
			t.Fatal("timed out waiting for failed leader and replacement leader")
		}
	}
	if successes != 1 || failures != 1 {
		t.Fatalf("expected one failed leader and one successful replacement, successes=%d failures=%d", successes, failures)
	}
	if got := node0.calls.Load(); got != 2 {
		t.Fatalf("expected node-0 to fail for both leaders, got %d attempts", got)
	}
	if got := node1.calls.Load(); got != 2 {
		t.Fatalf("expected node-1 initial failure then replacement success, got %d attempts", got)
	}
	if got, ok := lb.getStickySession(key, time.Now().UnixMilli()); !ok || got != 1 {
		t.Fatalf("expected replacement leader's successful member 1 to be pinned, got member=%d present=%v", got, ok)
	}
}

func TestStickyByAppAllCandidatesFailThenWaiterTakesOverForTCPAndUDP(t *testing.T) {
	for _, udp := range []bool{false, true} {
		transport := "tcp"
		if udp {
			transport = "udp"
		}
		t.Run(transport, func(t *testing.T) {
			exerciseStickyAllFailedThenTakeover(t, udp)
		})
	}
}

func TestStickyByAppCancelingWaiterDoesNotCancelLeader(t *testing.T) {
	node0 := &stickyConcurrentOutbound{tag: "node-0"}
	node1 := &stickyConcurrentOutbound{
		tag:     "node-1",
		entered: make(chan struct{}),
		release: make(chan struct{}),
	}
	lb := newStickyConcurrentLoadBalance(node0, node1)
	ctx := contextForApp("com.example.cue")
	leaderResult := make(chan stickyConcurrentResult, 1)
	go func() {
		conn, err := lb.DialContext(ctx, "tcp", M.Socksaddr{Fqdn: "api.example.com"})
		leaderResult <- stickyConcurrentResult{conn: conn, err: err}
	}()
	select {
	case <-node1.entered:
	case <-time.After(2 * time.Second):
		t.Fatal("leader did not enter its controlled dial")
	}
	key := appStickyKey(ctx)
	waiterCtx, cancelWaiter := context.WithCancel(contextForApp("com.example.cue"))
	waiterResult := make(chan stickyConcurrentResult, 1)
	go func() {
		conn, err := lb.DialContext(waiterCtx, "tcp", M.Socksaddr{Fqdn: "updates.example.net"})
		waiterResult <- stickyConcurrentResult{conn: conn, err: err}
	}()
	flight := waitForStickyFlightWaiter(t, lb, key)
	cancelWaiter()
	select {
	case result := <-waiterResult:
		if !errors.Is(result.err, context.Canceled) {
			t.Fatalf("expected canceled waiter to return context.Canceled, got conn=%v err=%v", result.conn, result.err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("canceled waiter did not exit")
	}
	if got := flight.waiters.Load(); got != 0 {
		t.Fatalf("expected canceled waiter count to return to zero, got %d", got)
	}
	lb.stickyMu.Lock()
	flightStillActive := lb.stickyFlights[key] == flight
	lb.stickyMu.Unlock()
	if !flightStillActive {
		t.Fatal("canceling a waiter must not remove or cancel the leader's flight")
	}

	close(node1.release)
	select {
	case result := <-leaderResult:
		if result.err != nil {
			t.Fatalf("expected leader to finish successfully, got %v", result.err)
		}
		if result.conn != nil {
			_ = result.conn.Close()
		}
	case <-time.After(2 * time.Second):
		t.Fatal("leader did not finish after waiter cancellation")
	}
	if got, ok := lb.getStickySession(key, time.Now().UnixMilli()); !ok || got != 1 {
		t.Fatalf("expected uncanceled leader's successful member 1 to be pinned, got member=%d present=%v", got, ok)
	}
}

func TestStickyByAppExistingBindingAllowsConcurrentTCPDials(t *testing.T) {
	node0 := &stickyConcurrentOutbound{
		tag:     "node-0",
		entered: make(chan struct{}),
		release: make(chan struct{}),
	}
	node1 := &stickyConcurrentOutbound{tag: "node-1"}
	lb := newStickyConcurrentLoadBalance(node0, node1)
	ctx := contextForApp("com.example.cue")
	key := appStickyKey(ctx)
	lb.setStickySession(key, 0)
	results := make(chan stickyConcurrentResult, 2)
	call := func(host string) {
		conn, err := lb.DialContext(ctx, "tcp", M.Socksaddr{Fqdn: host})
		results <- stickyConcurrentResult{conn: conn, err: err}
	}

	go call("api.example.com")
	select {
	case <-node0.entered:
	case <-time.After(2 * time.Second):
		t.Fatal("first bound-member dial did not enter its controlled dial")
	}
	go call("updates.example.net")
	select {
	case result := <-results:
		if result.err != nil {
			t.Fatalf("expected second bound-member dial to finish concurrently, got %v", result.err)
		}
		if result.conn != nil {
			_ = result.conn.Close()
		}
	case <-time.After(2 * time.Second):
		t.Fatal("second connection was serialized behind an already-bound dial")
	}
	if got := node0.calls.Load(); got != 2 {
		t.Fatalf("expected both connections to enter the bound member concurrently, got %d calls", got)
	}
	if got := node1.calls.Load(); got != 0 {
		t.Fatalf("bound connections should not be diverted to node-1, got %d calls", got)
	}

	close(node0.release)
	select {
	case result := <-results:
		if result.err != nil {
			t.Fatalf("expected first bound-member dial to finish, got %v", result.err)
		}
		if result.conn != nil {
			_ = result.conn.Close()
		}
	case <-time.After(2 * time.Second):
		t.Fatal("first connection did not finish after releasing its barrier")
	}
}
