package loadbalance

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
)

type stickyDiagnosticCaptureLogger struct {
	log.ContextLogger
	mu       sync.Mutex
	messages []string
}

func (l *stickyDiagnosticCaptureLogger) InfoContext(_ context.Context, args ...any) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.messages = append(l.messages, fmt.Sprint(args...))
}

func (l *stickyDiagnosticCaptureLogger) snapshot() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return append([]string(nil), l.messages...)
}

func TestStickyDiagnosticLogIncludesOnlySafeFlowSummary(t *testing.T) {
	if !stickyDiagnosticBuildEnabled {
		t.Skip("diagnostic log implementation is compile-gated")
	}
	ctx := contextForApp("com.openai.chatgpt")
	inbound := adapter.ContextFrom(ctx)
	inbound.Outbound = "AI相关"
	inbound.StickyDiagnosticMuxSession = true
	ctx = adapter.ContextWithStickyDiagnosticDetour(ctx, "https://private-node.example/path?token=diagnostic-secret")
	if adapter.ContextFrom(ctx) != inbound {
		t.Fatal("adding a diagnostic detour marker must preserve the existing InboundContext")
	}

	logger := &stickyDiagnosticCaptureLogger{}
	lb := &LoadBalance{
		logger:               logger,
		stickyDiagnostic:     true,
		diagnosticInstanceID: 12,
		diagnosticGroupTag:   "AI相关",
		stickyByApp:          true,
		strategy:             "roundRobin",
		tags:                 []string{"美国01", "https://another-node.example/?secret=hidden"},
	}
	ctx = lb.stickyDiagnosticWithFlowID(ctx)
	flowID, ok := ctx.Value(stickyDiagnosticFlowIDKey{}).(uint64)
	if !ok || flowID == 0 {
		t.Fatalf("expected a non-zero diagnostic flow_id, got %v", ctx.Value(stickyDiagnosticFlowIDKey{}))
	}
	fields := lb.stickyDiagnosticFlowFields(ctx, "tcp", M.Socksaddr{Fqdn: "api.openai.com"})
	lb.stickyDiagnosticLog(ctx, "flow_start", fields...)
	lb.stickyDiagnosticLog(ctx, "attempt_result",
		stickyDiagnosticQuotedField("member", stickyDiagnosticLabel(lb.tags[1])),
		stickyDiagnosticQuotedField("result", "failure"),
		stickyDiagnosticQuotedField("error_class", stickyDiagnosticErrorClass(errors.New("Authorization: Bearer raw-error-secret"))),
	)
	messages := logger.snapshot()
	if len(messages) != 2 {
		t.Fatalf("expected two diagnostic records, got %d", len(messages))
	}
	joined := strings.Join(messages, "\n")
	for _, want := range []string{"sticky_diag event=flow_start", "flow_id=", "package_hash=", "mux_session=true", "detour_path=", "destination_kind=", "error_class="} {
		if !strings.Contains(joined, want) {
			t.Errorf("diagnostic records missing %q: %s", want, joined)
		}
	}
	for _, forbidden := range []string{
		"com.openai.chatgpt",
		"AI相关",
		"美国01",
		"api.openai.com",
		"private-node.example",
		"another-node.example",
		"diagnostic-secret",
		"raw-error-secret",
		"token=",
		"secret=",
		"Authorization",
	} {
		if strings.Contains(joined, forbidden) {
			t.Errorf("diagnostic record leaked %q: %s", forbidden, joined)
		}
	}
	if got, want := stickyDiagnosticHash("com.openai.chatgpt"), stickyDiagnosticHash("com.openai.chatgpt"); got != want {
		t.Fatalf("package hashes must be stable within the process: got %q want %q", got, want)
	}
}

func TestStickyDiagnosticDisabledDoesNotEmit(t *testing.T) {
	logger := &stickyDiagnosticCaptureLogger{}
	lb := &LoadBalance{logger: logger, diagnosticGroupTag: "AI相关"}
	lb.stickyDiagnosticLog(context.Background(), "flow_start", stickyDiagnosticQuotedField("result", "success"))
	if messages := logger.snapshot(); len(messages) != 0 {
		t.Fatalf("diagnostics must be silent unless enabled by the diagnostic build, got %v", messages)
	}
}

func TestStickyDiagnosticCompileGate(t *testing.T) {
	if stickyDiagnosticEnabled(false) {
		t.Fatal("diagnostics must remain disabled when the config does not request them")
	}
	got := stickyDiagnosticEnabled(true)
	want := stickyDiagnosticBuildEnabled && stickyDiagnosticHashSaltReady
	if got != want {
		t.Fatalf("diagnostic request gate mismatch: got %t, build tag enabled=%t and secure salt ready=%t", got, stickyDiagnosticBuildEnabled, stickyDiagnosticHashSaltReady)
	}
}

func TestStickyDiagnosticErrorClassNeverIncludesRawError(t *testing.T) {
	got := stickyDiagnosticErrorClass(errors.New("https://host.invalid/path?token=top-secret"))
	if got != "other" || strings.Contains(got, "top-secret") || strings.Contains(got, "host.invalid") {
		t.Fatalf("expected only a coarse error category, got %q", got)
	}
	if got := stickyDiagnosticErrorClass(context.DeadlineExceeded); got != "deadline" {
		t.Fatalf("expected deadline category, got %q", got)
	}
}

func TestStickyDiagnosticBindingReasonClassification(t *testing.T) {
	tests := []struct {
		name string
		args []any
		want string
	}{
		{"initial", []any{true, false, false, false, false, false, ""}, "initial"},
		{"idle_timeout", []any{false, true, false, false, false, false, ""}, "idle_timeout"},
		{"reuse", []any{false, false, true, false, false, false, ""}, "reuse"},
		{"degraded_skip", []any{false, false, false, true, false, false, ""}, "degraded_skip"},
		{"dial_failure_fallback", []any{false, false, false, false, true, false, ""}, "dial_failure_fallback"},
		{"stale_revision", []any{false, false, false, false, false, true, "stale_revision"}, "stale_revision"},
		{"stale_flight", []any{false, false, false, false, false, true, "stale_flight"}, "stale_flight"},
		{"unknown_rejection", []any{false, false, false, false, false, true, "secret"}, "commit_rejected"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			args := test.args
			got := stickyDiagnosticBindingReason(args[0].(bool), args[1].(bool), args[2].(bool), args[3].(bool), args[4].(bool), args[5].(bool), args[6].(string))
			if got != test.want {
				t.Fatalf("got reason %q, want %q", got, test.want)
			}
		})
	}
	if got := stickyDiagnosticUint64("leader_flow_id", 1<<40); got != "leader_flow_id=1099511627776" {
		t.Fatalf("uint64 flow IDs must not truncate: got %q", got)
	}
}

func newStickyDiagnosticTestLoadBalance(logger *stickyDiagnosticCaptureLogger, node0, node1 *stickyConcurrentOutbound) *LoadBalance {
	lb := newStickyConcurrentLoadBalance(node0, node1)
	lb.logger = logger
	lb.stickyDiagnostic = true
	lb.diagnosticInstanceID = nextStickyDiagnosticInstanceID()
	lb.diagnosticGroupTag = "customer-route-secret"
	return lb
}

func diagnosticEventLine(messages []string, event string) string {
	prefix := "sticky_diag event=" + event + " "
	for _, message := range messages {
		if strings.Contains(message, prefix) {
			return message
		}
	}
	return ""
}

func diagnosticField(line, name string) string {
	for _, field := range strings.Fields(line) {
		prefix := name + "="
		if strings.HasPrefix(field, prefix) {
			return strings.Trim(field[len(prefix):], `"`)
		}
	}
	return ""
}

func assertDiagnosticContainsNoSensitiveValues(t *testing.T, joined string) {
	t.Helper()
	for _, forbidden := range []string{
		"com.example.private-app",
		"customer-route-secret",
		"node-secret://user:password@relay.invalid/path?token=private-token",
		"another-secret-member",
		"first-secret-member",
		"target-secret.example",
		"second-secret-target.example",
		"private-token",
		"token=",
		"password",
	} {
		if strings.Contains(joined, forbidden) {
			t.Errorf("sticky diagnostic leaked sensitive value %q: %s", forbidden, joined)
		}
	}
}

func TestStickyDiagnosticRebindReasonsForTCPAndUDP(t *testing.T) {
	if !stickyDiagnosticBuildEnabled {
		t.Skip("diagnostic event logging is compile-gated")
	}
	cases := []struct {
		name         string
		wantReason   string
		wantRebind   bool
		wantDegraded bool
		wantFallback bool
		wantMember   int
	}{
		{name: "initial", wantReason: "initial", wantMember: 1},
		{name: "reuse", wantReason: "reuse", wantMember: 0},
		{name: "idle_timeout", wantReason: "idle_timeout", wantRebind: true, wantMember: 1},
		{name: "degraded_skip", wantReason: "degraded_skip", wantRebind: true, wantDegraded: true, wantMember: 1},
		{name: "dial_failure_fallback", wantReason: "dial_failure_fallback", wantRebind: true, wantFallback: true, wantMember: 1},
	}
	for _, test := range cases {
		for _, udp := range []bool{false, true} {
			transport := "tcp"
			if udp {
				transport = "udp"
			}
			t.Run(test.name+"/"+transport, func(t *testing.T) {
				logger := &stickyDiagnosticCaptureLogger{}
				node0 := &stickyConcurrentOutbound{tag: "node-secret://user:password@relay.invalid/path?token=private-token"}
				if test.name == "dial_failure_fallback" {
					node0.alwaysErr = syscall.ECONNREFUSED
				}
				node1 := &stickyConcurrentOutbound{tag: "another-secret-member"}
				lb := newStickyDiagnosticTestLoadBalance(logger, node0, node1)
				ctx := contextForApp("com.example.private-app")
				key := appStickyKey(ctx)
				destination := M.Socksaddr{Fqdn: "target-secret.example"}
				now := time.Now().UnixMilli()
				switch test.name {
				case "reuse":
					lb.setStickySession(key, 0)
				case "idle_timeout":
					lb.stickySessions[key] = stickyEntry{nodeIdx: 0, revision: 4, lastAccess: now - 7*60*1000, expireAt: now - 2*60*1000}
				case "degraded_skip":
					lb.setStickySession(key, 0)
					lb.stats[0].consecutiveFails.Store(2)
					lb.stats[0].lastFailTime.Store(now - 100)
				case "dial_failure_fallback":
					lb.setStickySession(key, 0)
				}

				if udp {
					conn, err := lb.ListenPacket(ctx, destination)
					if err != nil {
						t.Fatalf("UDP diagnostic flow failed: %v", err)
					}
					_ = conn.Close()
				} else {
					conn, err := lb.DialContext(ctx, "tcp", destination)
					if err != nil {
						t.Fatalf("TCP diagnostic flow failed: %v", err)
					}
					_ = conn.Close()
				}

				messages := logger.snapshot()
				joined := strings.Join(messages, "\n")
				bindLine := diagnosticEventLine(messages, "sticky_bind")
				if bindLine == "" || !strings.Contains(bindLine, `reason="`+test.wantReason+`"`) || !strings.Contains(bindLine, "committed=true") {
					t.Fatalf("expected committed bind reason %q, got: %s", test.wantReason, joined)
				}
				if test.name == "reuse" {
					hitLine := diagnosticEventLine(messages, "sticky_hit")
					if hitLine == "" || !strings.Contains(hitLine, `reason="reuse"`) {
						t.Fatalf("expected sticky hit to identify member reuse, got: %s", joined)
					}
				}
				if test.wantRebind {
					rebindLine := diagnosticEventLine(messages, "sticky_rebind")
					if rebindLine == "" || !strings.Contains(rebindLine, `reason="`+test.wantReason+`"`) {
						t.Fatalf("expected rebind reason %q, got: %s", test.wantReason, joined)
					}
				}
				if test.wantDegraded && diagnosticEventLine(messages, "sticky_member_degraded") == "" {
					t.Fatalf("expected an explicit degraded sticky-member event, got: %s", joined)
				}
				if test.wantFallback {
					fallbackLine := diagnosticEventLine(messages, "dial_fallback")
					if fallbackLine == "" || !strings.Contains(fallbackLine, `reason="bound_member_failure"`) {
						t.Fatalf("expected explicit bound-member fallback event, got: %s", joined)
					}
				}
				if got, ok := lb.getStickySession(key, time.Now().UnixMilli()); !ok || got != test.wantMember {
					t.Fatalf("expected member %d after diagnostic flow, got member=%d present=%t", test.wantMember, got, ok)
				}
				assertDiagnosticContainsNoSensitiveValues(t, joined)
			})
		}
	}
}

func TestStickyDiagnosticRevisionAndFlightRejectReasons(t *testing.T) {
	if !stickyDiagnosticBuildEnabled {
		t.Skip("diagnostic event logging is compile-gated")
	}
	logger := &stickyDiagnosticCaptureLogger{}
	node0 := &stickyConcurrentOutbound{tag: "node-secret://user:password@relay.invalid/path?token=private-token"}
	node1 := &stickyConcurrentOutbound{tag: "another-secret-member"}
	lb := newStickyDiagnosticTestLoadBalance(logger, node0, node1)
	ctx := lb.stickyDiagnosticWithFlowID(contextForApp("com.example.private-app"))
	destination := M.Socksaddr{Fqdn: "target-secret.example"}
	key := appStickyKey(ctx)
	lb.setStickySession(key, 0)
	trace := newStickyDialDiagnosticState(true)
	_, revision, err := lb.beginStickyDialForFlow(ctx, key, trace)
	if err != nil {
		t.Fatal(err)
	}
	lb.setStickySession(key, 1)
	lb.recordStickyDialSuccess(ctx, destination, key, nil, revision, 0, trace)
	if got, ok := lb.getStickySession(key, time.Now().UnixMilli()); !ok || got != 1 {
		t.Fatalf("stale revision changed the binding: member=%d present=%t", got, ok)
	}

	// A flight replaced by a newer successful binding must reject its late result.
	lb.stickyMu.Lock()
	delete(lb.stickySessions, key)
	lb.stickyMu.Unlock()
	flightCtx := lb.stickyDiagnosticWithFlowID(contextForApp("com.example.private-app"))
	flight, _, err := lb.beginStickyDialForFlow(flightCtx, key, newStickyDialDiagnosticState(true))
	if err != nil || flight == nil {
		t.Fatalf("expected a fresh test flight, flight=%v err=%v", flight, err)
	}
	if !lb.finishStickyDial(key, flight, 1) {
		t.Fatal("expected test flight to bind member 1")
	}
	lb.recordStickyDialSuccess(flightCtx, destination, key, flight, 0, 0, newStickyDialDiagnosticState(true))
	if got, ok := lb.getStickySession(key, time.Now().UnixMilli()); !ok || got != 1 {
		t.Fatalf("late stale flight changed the binding: member=%d present=%t", got, ok)
	}

	messages := logger.snapshot()
	joined := strings.Join(messages, "\n")
	if line := diagnosticEventLine(messages, "sticky_revision_rejected"); line == "" || !strings.Contains(line, `reason="stale_revision"`) {
		t.Fatalf("expected stale revision rejection event, got: %s", joined)
	}
	if line := diagnosticEventLine(messages, "sticky_flight_rejected"); line == "" || !strings.Contains(line, `reason="stale_flight"`) {
		t.Fatalf("expected stale flight rejection event, got: %s", joined)
	}
	assertDiagnosticContainsNoSensitiveValues(t, joined)
}

func TestStickyDiagnosticFlightLogsCorrelateLeaderAndWaiterFlowIDs(t *testing.T) {
	if !stickyDiagnosticBuildEnabled {
		t.Skip("diagnostic event logging is compile-gated")
	}
	logger := &stickyDiagnosticCaptureLogger{}
	node0 := &stickyConcurrentOutbound{tag: "first-secret-member"}
	node1 := &stickyConcurrentOutbound{
		tag:     "node-secret://user:password@relay.invalid/path?token=private-token",
		entered: make(chan struct{}),
		release: make(chan struct{}),
	}
	lb := newStickyDiagnosticTestLoadBalance(logger, node0, node1)
	ctx := contextForApp("com.example.private-app")
	results := make(chan stickyConcurrentResult, 2)
	call := func(host string) {
		conn, err := lb.DialContext(ctx, "tcp", M.Socksaddr{Fqdn: host})
		results <- stickyConcurrentResult{conn: conn, err: err}
	}
	go call("target-secret.example")
	select {
	case <-node1.entered:
	case <-time.After(2 * time.Second):
		t.Fatal("leader did not reach the controlled outbound")
	}

	messages := logger.snapshot()
	leaderLine := diagnosticEventLine(messages, "flight_leader")
	leaderFlow := diagnosticField(leaderLine, "flow_id")
	if leaderFlow == "" || diagnosticField(leaderLine, "leader_flow_id") != leaderFlow {
		t.Fatalf("leader event did not self-correlate its flow ID: %s", leaderLine)
	}
	go call("second-secret-target.example")
	waitForStickyFlightWaiter(t, lb, appStickyKey(ctx))
	close(node1.release)
	for i := 0; i < 2; i++ {
		select {
		case result := <-results:
			if result.err != nil {
				t.Fatalf("concurrent diagnostic flow failed: %v", result.err)
			}
			if result.conn != nil {
				_ = result.conn.Close()
			}
		case <-time.After(2 * time.Second):
			t.Fatal("timed out waiting for diagnostic leader/waiter flows")
		}
	}

	messages = logger.snapshot()
	waitLine := diagnosticEventLine(messages, "flight_wait")
	waiterFlow := diagnosticField(waitLine, "flow_id")
	if waiterFlow == "" || waiterFlow == leaderFlow || diagnosticField(waitLine, "leader_flow_id") != leaderFlow {
		t.Fatalf("waiter event did not reference the distinct leader flow: %s (leader=%s)", waitLine, leaderFlow)
	}
	finishLine := diagnosticEventLine(messages, "flight_finish")
	if diagnosticField(finishLine, "flow_id") != leaderFlow || diagnosticField(finishLine, "leader_flow_id") != leaderFlow {
		t.Fatalf("flight finish was not attributed to its leader: %s (leader=%s)", finishLine, leaderFlow)
	}
	assertDiagnosticContainsNoSensitiveValues(t, strings.Join(messages, "\n"))
}
