package adapter

import (
	"context"
	"testing"
)

func TestStickyDiagnosticContextTracksMuxAndDetourWithoutReplacingContext(t *testing.T) {
	original := &InboundContext{
		Outbound:    "route-group",
		ProcessInfo: &ConnectionOwner{PackageNames: []string{"com.example.chat"}},
	}
	ctx := WithContext(context.Background(), original)
	ctx = ContextWithStickyDiagnosticDetour(ctx, "front-node")
	if ContextFrom(ctx) != original {
		t.Fatal("adding a detour marker must preserve the original InboundContext pointer")
	}
	if got := StickyDiagnosticDetourPathFromContext(ctx); len(got) != 1 || got[0] != "front-node" {
		t.Fatalf("expected one detour marker before mux, got %v", got)
	}

	muxContext := ContextForMultiplexSession(ctx)
	muxMetadata := ContextFrom(muxContext)
	if !muxMetadata.StickyDiagnosticMuxSession {
		t.Fatal("multiplex context should carry its diagnostic marker")
	}
	if muxMetadata.ProcessInfo != nil {
		t.Fatal("multiplex context must retain its existing process-identity behavior")
	}
	if muxMetadata.Outbound != "route-group" {
		t.Fatalf("expected the route outbound label to survive mux, got %q", muxMetadata.Outbound)
	}
	if got := StickyDiagnosticDetourPathFromContext(muxContext); len(got) != 1 || got[0] != "front-node" {
		t.Fatalf("expected the detour marker to survive mux, got %v", got)
	}

	muxContext = ContextWithStickyDiagnosticDetour(muxContext, "landing-node")
	if ContextFrom(muxContext) != muxMetadata {
		t.Fatal("adding a nested detour marker must preserve the mux InboundContext pointer")
	}
	if !muxMetadata.StickyDiagnosticMuxSession {
		t.Fatal("nested detour context should preserve the mux marker")
	}
	if got := StickyDiagnosticDetourPathFromContext(muxContext); len(got) != 2 || got[1] != "landing-node" {
		t.Fatalf("expected both detours to survive nested context, got %v", got)
	}
}
