package loadbalance

import (
	"context"
	"net"
	"syscall"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/interrupt"
	M "github.com/sagernet/sing/common/metadata"
)

func contextForApp(packageNames ...string) context.Context {
	metadata := &adapter.InboundContext{
		ProcessInfo: &adapter.ConnectionOwner{PackageNames: packageNames},
	}
	return adapter.WithContext(context.Background(), metadata)
}

func TestStickyByAppPromotesSuccessfulMemberAcrossDomainsForStrategies(t *testing.T) {
	strategies := []string{"leastPing", "leastLoad", "random", "roundRobin", "consistentHash"}
	tags := []string{"node-0", "node-1", "node-2"}

	for _, strategy := range strategies {
		t.Run(strategy, func(t *testing.T) {
			lb := &LoadBalance{
				tags:        tags,
				strategy:    strategy,
				stickyByApp: true,
				outbounds:   make([]adapter.Outbound, len(tags)),
				stats:       []*nodeStats{new(nodeStats), new(nodeStats), new(nodeStats)},
			}
			ctx := contextForApp("com.example.youtube")
			stickyIdx := 2
			lb.setStickySession(appStickyKey(ctx), stickyIdx)

			for _, destination := range []M.Socksaddr{
				{Fqdn: "r1---sn.googlevideo.com"},
				{Fqdn: "youtubei.googleapis.com"},
			} {
				candidates := lb.candidateIndices(ctx, destination)
				if len(candidates) != len(tags) || candidates[0] != stickyIdx {
					t.Fatalf("expected app's successful node %d first across domains, got %v", stickyIdx, candidates)
				}
			}

			otherAppKey := appStickyKey(contextForApp("com.example.browser"))
			if otherAppKey == appStickyKey(ctx) {
				t.Fatal("different app package names must produce different sticky keys")
			}
			if _, ok := lb.getStickySession(otherAppKey, 0); ok {
				t.Fatal("another app must not inherit this app's pinned member")
			}
		})
	}
}

func TestStickyByAppDisabledDoesNotOverrideStrategy(t *testing.T) {
	tags := []string{"node-0", "node-1", "node-2"}
	lb := &LoadBalance{
		tags:      tags,
		strategy:  "roundRobin",
		outbounds: make([]adapter.Outbound, len(tags)),
		stats:     []*nodeStats{new(nodeStats), new(nodeStats), new(nodeStats)},
	}
	ctx := contextForApp("com.example.youtube")
	lb.setStickySession(appStickyKey(ctx), 2)

	candidates := lb.candidateIndices(ctx, M.Socksaddr{Fqdn: "video.youtube.com"})
	if candidates[0] != 1 {
		t.Fatalf("disabled app stickiness must preserve round-robin selection, got %v", candidates)
	}
}

func TestStickyByAppDialFailureRetriesThenPinsSuccessfulMember(t *testing.T) {
	tags := []string{"node-0", "node-1", "node-2"}
	lb := &LoadBalance{
		tags:           tags,
		strategy:       "roundRobin",
		stickyByApp:    true,
		outbounds:      make([]adapter.Outbound, len(tags)),
		stats:          []*nodeStats{new(nodeStats), new(nodeStats), new(nodeStats)},
		interruptGroup: interrupt.NewGroup(),
		stickySessions: make(map[string]stickyEntry),
	}
	metadata := &adapter.InboundContext{
		ProcessInfo: &adapter.ConnectionOwner{PackageNames: []string{"com.example.youtube"}},
	}
	metadata.EnableActiveOutboundTracking()
	ctx := adapter.WithContext(context.Background(), metadata)
	ctx = adapter.ContextWithActiveOutboundTracker(ctx, *metadata)
	firstDestination := M.Socksaddr{Fqdn: "r1---sn.googlevideo.com"}
	initialCandidates := lb.candidateIndices(ctx, firstDestination)
	primaryIdx, fallbackIdx := initialCandidates[0], initialCandidates[1]
	lb.counter = 0 // candidateIndices above consumed one round-robin turn

	primary := &dialRetryTestOutbound{tag: tags[primaryIdx], err: syscall.ECONNREFUSED}
	clientConn, serverConn := net.Pipe()
	defer serverConn.Close()
	fallback := &dialRetryTestOutbound{tag: tags[fallbackIdx], conn: clientConn}
	lb.outbounds[primaryIdx] = primary
	lb.outbounds[fallbackIdx] = fallback

	conn, err := lb.DialContext(ctx, "tcp", firstDestination)
	if err != nil {
		t.Fatalf("expected fallback member to connect after the primary dial failed, got %v", err)
	}
	defer conn.Close()
	if primary.attempt != 1 || fallback.attempt != 1 {
		t.Fatalf("expected primary and fallback to be attempted once, got primary=%d fallback=%d", primary.attempt, fallback.attempt)
	}
	if got := metadata.ActiveOutbound(); got != fallback.Tag() {
		t.Fatalf("expected active member %q after fallback, got %q", fallback.Tag(), got)
	}

	secondCandidates := lb.candidateIndices(ctx, M.Socksaddr{Fqdn: "youtubei.googleapis.com"})
	if secondCandidates[0] != fallbackIdx {
		t.Fatalf("expected the successful fallback %d to remain pinned across domains, got %v", fallbackIdx, secondCandidates)
	}
}

func TestStickyByAppListenPacketFailureRetriesThenPinsSuccessfulMember(t *testing.T) {
	tags := []string{"node-0", "node-1", "node-2"}
	lb := &LoadBalance{
		tags:           tags,
		strategy:       "roundRobin",
		stickyByApp:    true,
		outbounds:      make([]adapter.Outbound, len(tags)),
		stats:          []*nodeStats{new(nodeStats), new(nodeStats), new(nodeStats)},
		interruptGroup: interrupt.NewGroup(),
		stickySessions: make(map[string]stickyEntry),
	}
	metadata := &adapter.InboundContext{
		ProcessInfo: &adapter.ConnectionOwner{PackageNames: []string{"com.example.youtube"}},
	}
	metadata.EnableActiveOutboundTracking()
	ctx := adapter.WithContext(context.Background(), metadata)
	ctx = adapter.ContextWithActiveOutboundTracker(ctx, *metadata)
	firstDestination := M.Socksaddr{Fqdn: "r1---sn.googlevideo.com"}
	initialCandidates := lb.candidateIndices(ctx, firstDestination)
	primaryIdx, fallbackIdx := initialCandidates[0], initialCandidates[1]
	lb.counter = 0 // candidateIndices above consumed one round-robin turn

	primary := &dialRetryTestOutbound{tag: tags[primaryIdx], err: syscall.ECONNREFUSED}
	packetConn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("could not create local UDP PacketConn for test: %v", err)
	}
	defer packetConn.Close()
	fallback := &dialRetryTestOutbound{tag: tags[fallbackIdx], packetConn: packetConn}
	lb.outbounds[primaryIdx] = primary
	lb.outbounds[fallbackIdx] = fallback

	conn, err := lb.ListenPacket(ctx, firstDestination)
	if err != nil {
		t.Fatalf("expected fallback member to open a PacketConn after primary failure, got %v", err)
	}
	defer conn.Close()
	if primary.attempt != 1 || fallback.attempt != 1 {
		t.Fatalf("expected UDP primary and fallback to be attempted once, got primary=%d fallback=%d", primary.attempt, fallback.attempt)
	}
	if got := metadata.ActiveOutbound(); got != fallback.Tag() {
		t.Fatalf("expected active UDP member %q after fallback, got %q", fallback.Tag(), got)
	}

	secondCandidates := lb.candidateIndices(ctx, M.Socksaddr{Fqdn: "youtubei.googleapis.com"})
	if secondCandidates[0] != fallbackIdx {
		t.Fatalf("expected the successful UDP fallback %d to remain pinned across domains, got %v", fallbackIdx, secondCandidates)
	}
}
