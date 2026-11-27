package loadbalance

import (
	"context"
	"errors"
	"net"
	"syscall"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/interrupt"
	M "github.com/sagernet/sing/common/metadata"
)

type dialRetryTestOutbound struct {
	tag        string
	err        error
	conn       net.Conn
	packetConn net.PacketConn
	attempt    int
}

func (o *dialRetryTestOutbound) Type() string           { return "test" }
func (o *dialRetryTestOutbound) Tag() string            { return o.tag }
func (o *dialRetryTestOutbound) Network() []string      { return []string{"tcp", "udp"} }
func (o *dialRetryTestOutbound) Dependencies() []string { return nil }
func (o *dialRetryTestOutbound) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	o.attempt++
	if o.err != nil {
		return nil, o.err
	}
	return o.conn, nil
}
func (o *dialRetryTestOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	o.attempt++
	if o.err != nil {
		return nil, o.err
	}
	if o.packetConn == nil {
		return nil, errors.New("not used in dial retry test")
	}
	return o.packetConn, nil
}

func TestConsistentHashDialRetriesAfterUnreachablePrimary(t *testing.T) {
	tags := []string{"us-02", "fallback"}
	destination := M.Socksaddr{Fqdn: "api.example.com"}
	lb := &LoadBalance{
		tags:           tags,
		strategy:       "consistentHash",
		ring:           newConsistentHashRing(tags),
		outbounds:      make([]adapter.Outbound, len(tags)),
		stats:          []*nodeStats{new(nodeStats), new(nodeStats)},
		interruptGroup: interrupt.NewGroup(),
	}
	candidates := lb.candidateIndices(nil, destination)
	if len(candidates) != len(tags) || candidates[0] == candidates[1] {
		t.Fatalf("expected two distinct consistent-hash candidates, got %v", candidates)
	}

	primary := &dialRetryTestOutbound{tag: tags[candidates[0]], err: syscall.ENETUNREACH}
	clientConn, serverConn := net.Pipe()
	defer serverConn.Close()
	fallback := &dialRetryTestOutbound{tag: tags[candidates[1]], conn: clientConn}
	lb.outbounds[candidates[0]] = primary
	lb.outbounds[candidates[1]] = fallback
	var metadata adapter.InboundContext
	metadata.EnableActiveOutboundTracking()
	ctx := adapter.ContextWithActiveOutboundTracker(context.Background(), metadata)

	conn, err := lb.DialContext(ctx, "tcp", destination)
	if err != nil {
		t.Fatalf("expected fallback candidate to connect after primary failure, got %v", err)
	}
	if conn == nil {
		t.Fatal("expected a connection from the fallback candidate")
	}
	defer conn.Close()

	if primary.attempt != 1 || fallback.attempt != 1 {
		t.Fatalf("expected primary and fallback to be tried once, got primary=%d fallback=%d", primary.attempt, fallback.attempt)
	}
	if got := metadata.ActiveOutbound(); got != fallback.Tag() {
		t.Fatalf("expected the runtime tracker to report the successful fallback %q, got %q", fallback.Tag(), got)
	}
	if got := lb.stats[candidates[0]].consecutiveFails.Load(); got != 1 {
		t.Fatalf("expected unreachable primary failure to be recorded, got %d", got)
	}
	if got := lb.stats[candidates[1]].successDials.Load(); got != 1 {
		t.Fatalf("expected fallback success to be recorded, got %d", got)
	}
}

func TestAllStrategiesRetryNextMemberAfterDialFailure(t *testing.T) {
	strategies := []string{"leastPing", "leastLoad", "random", "roundRobin", "consistentHash"}
	for _, strategy := range strategies {
		t.Run(strategy, func(t *testing.T) {
			tags := []string{"n0", "n1", "n2"}
			lb := &LoadBalance{
				tags:           tags,
				strategy:       strategy,
				outbounds:      make([]adapter.Outbound, len(tags)),
				stats:          []*nodeStats{new(nodeStats), new(nodeStats), new(nodeStats)},
				interruptGroup: interrupt.NewGroup(),
			}
			if strategy == "random" {
				lb.randomIntn = func(int) int { return 0 }
			}
			lb.ring = newConsistentHashRing(tags)
			destination := M.Socksaddr{Fqdn: "retry.example"}
			candidates := lb.candidateIndices(nil, destination)
			lb.counter = 0 // Reuse the same first candidate for sequential strategies.
			failedIdx, fallbackIdx := candidates[0], candidates[1]
			failed := &dialRetryTestOutbound{tag: tags[failedIdx], err: syscall.ENETUNREACH}
			clientConn, serverConn := net.Pipe()
			defer serverConn.Close()
			fallback := &dialRetryTestOutbound{tag: tags[fallbackIdx], conn: clientConn}
			lb.outbounds[failedIdx] = failed
			lb.outbounds[fallbackIdx] = fallback

			conn, err := lb.DialContext(context.Background(), "tcp", destination)
			if err != nil {
				t.Fatalf("expected next candidate to succeed after %s failure: %v", strategy, err)
			}
			defer conn.Close()
			if failed.attempt != 1 || fallback.attempt != 1 {
				t.Fatalf("expected failed and fallback candidates once each, got %d and %d", failed.attempt, fallback.attempt)
			}
		})
	}
}
