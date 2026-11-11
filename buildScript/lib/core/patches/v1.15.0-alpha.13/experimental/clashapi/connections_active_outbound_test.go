package clashapi

import (
	"encoding/json"
	"sync/atomic"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/trafficcontrol"
)

func TestConnectionObjectIncludesActiveOutbound(t *testing.T) {
	var metadata adapter.InboundContext
	metadata.EnableActiveOutboundTracking()
	ctx := adapter.ContextWithActiveOutboundTracker(t.Context(), metadata)
	adapter.RecordActiveOutbound(ctx, "美国02")

	connection := connectionObject(trafficcontrol.TrackerMetadata{
		Metadata: metadata,
		Upload:   new(atomic.Int64),
		Download: new(atomic.Int64),
	})
	encoded, err := json.Marshal(connection)
	if err != nil {
		t.Fatalf("marshal connection: %v", err)
	}
	var result struct {
		ActiveOutbound string `json:"activeOutbound"`
	}
	if err = json.Unmarshal(encoded, &result); err != nil {
		t.Fatalf("unmarshal connection: %v", err)
	}
	if result.ActiveOutbound != "美国02" {
		t.Fatalf("expected active outbound %q, got %q", "美国02", result.ActiveOutbound)
	}
}
