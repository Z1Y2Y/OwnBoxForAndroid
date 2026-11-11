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
	tag     string
	err     error
	conn    net.Conn
	attempt int
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
	return nil, errors.New("not used in dial retry test")
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

	conn, err := lb.DialContext(context.Background(), "tcp", destination)
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
	if got := lb.stats[candidates[0]].consecutiveFails.Load(); got != 1 {
		t.Fatalf("expected unreachable primary failure to be recorded, got %d", got)
	}
	if got := lb.stats[candidates[1]].successDials.Load(); got != 1 {
		t.Fatalf("expected fallback success to be recorded, got %d", got)
	}
}
