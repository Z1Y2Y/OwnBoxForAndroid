package route

import (
	"context"
	"errors"
	"io"
	"net"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type resolveOutboundTestNode struct {
	tag string
}

func (n *resolveOutboundTestNode) Type() string           { return "test" }
func (n *resolveOutboundTestNode) Tag() string            { return n.tag }
func (n *resolveOutboundTestNode) Network() []string      { return []string{N.NetworkTCP, N.NetworkUDP} }
func (n *resolveOutboundTestNode) Dependencies() []string { return nil }
func (n *resolveOutboundTestNode) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return nil, errors.New("not used in resolveOutbound test")
}
func (n *resolveOutboundTestNode) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("not used in resolveOutbound test")
}

type resolveOutboundTestGroup struct {
	tag      string
	selected adapter.Outbound
}

func (g *resolveOutboundTestGroup) Type() string           { return "test-group" }
func (g *resolveOutboundTestGroup) Tag() string            { return g.tag }
func (g *resolveOutboundTestGroup) Network() []string      { return []string{N.NetworkTCP, N.NetworkUDP} }
func (g *resolveOutboundTestGroup) Dependencies() []string { return []string{g.selected.Tag()} }
func (g *resolveOutboundTestGroup) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return nil, errors.New("not used in resolveOutbound test")
}
func (g *resolveOutboundTestGroup) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("not used in resolveOutbound test")
}
func (g *resolveOutboundTestGroup) All() []string                     { return []string{g.selected.Tag()} }
func (g *resolveOutboundTestGroup) Selected(string) adapter.Outbound  { return g.selected }
func (g *resolveOutboundTestGroup) AttachConnection(io.Closer) func() { return func() {} }

type resolveOutboundHandlingGroup struct {
	*resolveOutboundTestGroup
}

func (g *resolveOutboundHandlingGroup) NewConnection(context.Context, net.Conn, adapter.InboundContext, N.CloseHandlerFunc) {
}
func (g *resolveOutboundHandlingGroup) NewPacketConnection(context.Context, N.PacketConn, adapter.InboundContext, N.CloseHandlerFunc) {
}

func TestResolveOutboundPreservesConnectionHandlingGroups(t *testing.T) {
	for _, network := range []string{N.NetworkTCP, N.NetworkUDP} {
		t.Run(network, func(t *testing.T) {
			member := &resolveOutboundTestNode{tag: "member-us-02"}
			group := &resolveOutboundHandlingGroup{
				resolveOutboundTestGroup: &resolveOutboundTestGroup{tag: "ai-related", selected: member},
			}

			chain, err := resolveOutbound(group, network)
			if err != nil {
				t.Fatal(err)
			}
			if len(chain) != 1 || chain[0] != group {
				t.Fatalf("expected the group handler to remain the terminal outbound for %s, got %#v", network, chain)
			}
		})
	}
}

func TestResolveOutboundStillExpandsSelectorLikeGroups(t *testing.T) {
	member := &resolveOutboundTestNode{tag: "selected-node"}
	group := &resolveOutboundTestGroup{tag: "manual-selector", selected: member}

	chain, err := resolveOutbound(group, N.NetworkTCP)
	if err != nil {
		t.Fatal(err)
	}
	if len(chain) != 2 || chain[0] != group || chain[1] != member {
		t.Fatalf("expected selector-like group to resolve to its selected member, got %#v", chain)
	}
}

func TestResolveOutboundKeepsExplicitSingleNode(t *testing.T) {
	node := &resolveOutboundTestNode{tag: "explicit-node"}

	chain, err := resolveOutbound(node, N.NetworkTCP)
	if err != nil {
		t.Fatal(err)
	}
	if len(chain) != 1 || chain[0] != node {
		t.Fatalf("expected explicit single-node outbound to stay unchanged, got %#v", chain)
	}
}
