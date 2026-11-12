package loadbalance

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
)

type stickyDiagnosticCaptureLogger struct {
	log.ContextLogger
	messages []string
}

func (l *stickyDiagnosticCaptureLogger) InfoContext(_ context.Context, args ...any) {
	l.messages = append(l.messages, fmt.Sprint(args...))
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
	if len(logger.messages) != 2 {
		t.Fatalf("expected two diagnostic records, got %d", len(logger.messages))
	}
	joined := strings.Join(logger.messages, "\n")
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
	if len(logger.messages) != 0 {
		t.Fatalf("diagnostics must be silent unless enabled by the diagnostic build, got %v", logger.messages)
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
