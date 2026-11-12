package loadbalance

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"unicode"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/common/metadata"
)

var stickyDiagnosticInstanceSequence atomic.Uint64
var stickyDiagnosticHashSalt [32]byte
var stickyDiagnosticHashSaltOnce sync.Once
var stickyDiagnosticHashSaltReady bool

type stickyDiagnosticFlowIDKey struct{}

func nextStickyDiagnosticInstanceID() uint64 {
	return stickyDiagnosticInstanceSequence.Add(1)
}

func (s *LoadBalance) stickyDiagnosticWithFlowID(ctx context.Context) context.Context {
	if s == nil || !s.stickyDiagnostic || ctx == nil {
		return ctx
	}
	flowID := s.diagnosticFlowSequence.Add(1)
	return context.WithValue(ctx, stickyDiagnosticFlowIDKey{}, flowID)
}

func stickyDiagnosticEnabled(requested bool) bool {
	return requested && stickyDiagnosticBuildEnabled && ensureStickyDiagnosticHashSalt()
}

func ensureStickyDiagnosticHashSalt() bool {
	if !stickyDiagnosticBuildEnabled {
		return false
	}
	stickyDiagnosticHashSaltOnce.Do(func() {
		_, err := rand.Read(stickyDiagnosticHashSalt[:])
		stickyDiagnosticHashSaltReady = err == nil
	})
	return stickyDiagnosticHashSaltReady
}

// stickyDiagnosticHash emits a process-salted stable identifier, never the input value.
func stickyDiagnosticHash(value string) string {
	if value == "" {
		return "none"
	}
	if !stickyDiagnosticBuildEnabled {
		return "disabled"
	}
	if !ensureStickyDiagnosticHashSalt() {
		return "unavailable"
	}
	mac := hmac.New(sha256.New, stickyDiagnosticHashSalt[:])
	_, _ = mac.Write([]byte(value))
	return hex.EncodeToString(mac.Sum(nil)[:12])
}

// stickyDiagnosticLabel always hashes configuration-provided labels. A short
// node/group label could itself be a password or access token.
func stickyDiagnosticLabel(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return "none"
	}
	return "h:" + stickyDiagnosticHash(value)
}

func stickyDiagnosticStrategy(value string) string {
	switch value {
	case "leastPing", "least_ping", "leastLoad", "least_load", "random", "roundRobin", "round_robin", "consistentHash", "consistent_hash":
		return value
	default:
		return "other"
	}
}

func stickyDiagnosticErrorClass(err error) string {
	switch {
	case err == nil:
		return "none"
	case errors.Is(err, context.Canceled):
		return "canceled"
	case errors.Is(err, context.DeadlineExceeded):
		return "deadline"
	case errors.Is(err, syscall.ECONNREFUSED):
		return "refused"
	case errors.Is(err, syscall.ECONNRESET):
		return "reset"
	case errors.Is(err, syscall.ENETUNREACH):
		return "network_unreachable"
	case errors.Is(err, syscall.EHOSTUNREACH):
		return "host_unreachable"
	default:
		var netErr net.Error
		if errors.As(err, &netErr) && netErr.Timeout() {
			return "timeout"
		}
		return "other"
	}
}

func stickyDiagnosticField(name, value string) string {
	return name + "=" + value
}

func stickyDiagnosticQuotedField(name, value string) string {
	// Labels and hashes are already constrained; quoting prevents whitespace or
	// log-control characters from creating ambiguous extra fields.
	return name + "=" + strconvQuote(value)
}

func strconvQuote(value string) string {
	var b strings.Builder
	b.WriteByte('"')
	for _, r := range value {
		switch r {
		case '\\', '"':
			b.WriteByte('\\')
			b.WriteRune(r)
		case '\n', '\r', '\t':
			b.WriteByte(' ')
		default:
			if !unicode.IsControl(r) {
				b.WriteRune(r)
			}
		}
	}
	b.WriteByte('"')
	return b.String()
}

func stickyDiagnosticBool(name string, value bool) string {
	if value {
		return name + "=true"
	}
	return name + "=false"
}

func stickyDiagnosticInt(name string, value int) string {
	return name + "=" + strconvItoa(value)
}

func stickyDiagnosticUint64(name string, value uint64) string {
	return name + "=" + strconvItoa(int(value))
}

func strconvItoa(value int) string {
	if value == 0 {
		return "0"
	}
	negative := value < 0
	if negative {
		value = -value
	}
	var buf [24]byte
	idx := len(buf)
	for value > 0 {
		idx--
		buf[idx] = byte('0' + value%10)
		value /= 10
	}
	if negative {
		idx--
		buf[idx] = '-'
	}
	return string(buf[idx:])
}

func stickyDiagnosticTransport(network string) string {
	switch strings.ToLower(network) {
	case "tcp", "tcp4", "tcp6":
		return "tcp"
	case "udp", "udp4", "udp6":
		return "udp"
	default:
		return "other"
	}
}

func stickyDiagnosticDestinationKind(destination metadata.Socksaddr) string {
	if destination.Fqdn != "" {
		return "domain"
	}
	if destination.IsIP() {
		return "ip"
	}
	return "unknown"
}

func stickyDiagnosticDetourPath(ctx context.Context) string {
	tags := adapter.StickyDiagnosticDetourPathFromContext(ctx)
	if len(tags) == 0 {
		return "none"
	}
	path := make([]string, 0, len(tags))
	for _, tag := range tags {
		path = append(path, stickyDiagnosticLabel(tag))
	}
	return strings.Join(path, ">")
}

func stickyDiagnosticContextFields(ctx context.Context) []string {
	detourPath := stickyDiagnosticDetourPath(ctx)
	if ctx == nil {
		return []string{
			stickyDiagnosticBool("inbound_context", false),
			stickyDiagnosticBool("process_info", false),
			stickyDiagnosticInt("package_count", 0),
			stickyDiagnosticQuotedField("package_hash", "none"),
			stickyDiagnosticBool("mux_session", false),
			stickyDiagnosticQuotedField("route_outbound", "none"),
			stickyDiagnosticQuotedField("detour_path", detourPath),
		}
	}
	inbound := adapter.ContextFrom(ctx)
	if inbound == nil {
		return []string{
			stickyDiagnosticBool("inbound_context", false),
			stickyDiagnosticBool("process_info", false),
			stickyDiagnosticInt("package_count", 0),
			stickyDiagnosticQuotedField("package_hash", "none"),
			stickyDiagnosticBool("mux_session", false),
			stickyDiagnosticQuotedField("route_outbound", "none"),
			stickyDiagnosticQuotedField("detour_path", detourPath),
		}
	}

	packageNames := make([]string, 0)
	if inbound.ProcessInfo != nil {
		for _, packageName := range inbound.ProcessInfo.PackageNames {
			packageName = strings.ToLower(strings.TrimSpace(packageName))
			if packageName != "" {
				packageNames = append(packageNames, packageName)
			}
		}
	}
	// Sorting keeps the process-salted hash stable when Android reports aliases in a different order.
	for i := 0; i < len(packageNames); i++ {
		for j := i + 1; j < len(packageNames); j++ {
			if packageNames[j] < packageNames[i] {
				packageNames[i], packageNames[j] = packageNames[j], packageNames[i]
			}
		}
	}
	packageNames = compactDiagnosticStrings(packageNames)
	packageHash := "none"
	if len(packageNames) > 0 {
		packageHash = stickyDiagnosticHash(strings.Join(packageNames, ","))
	}
	return []string{
		stickyDiagnosticBool("inbound_context", true),
		stickyDiagnosticBool("process_info", inbound.ProcessInfo != nil),
		stickyDiagnosticInt("package_count", len(packageNames)),
		stickyDiagnosticQuotedField("package_hash", packageHash),
		stickyDiagnosticBool("mux_session", inbound.StickyDiagnosticMuxSession),
		stickyDiagnosticQuotedField("route_outbound", stickyDiagnosticLabel(inbound.Outbound)),
		stickyDiagnosticQuotedField("detour_path", detourPath),
	}
}

func compactDiagnosticStrings(values []string) []string {
	if len(values) < 2 {
		return values
	}
	result := values[:1]
	for _, value := range values[1:] {
		if value != result[len(result)-1] {
			result = append(result, value)
		}
	}
	return result
}

func stickyDiagnosticCandidateOrder(s *LoadBalance, indices []int) string {
	if len(indices) == 0 {
		return "empty"
	}
	var b strings.Builder
	for position, idx := range indices {
		if position > 0 {
			b.WriteByte(',')
		}
		label := "unknown"
		if idx >= 0 && idx < len(s.tags) {
			label = stickyDiagnosticLabel(s.tags[idx])
		} else if idx >= 0 && idx < len(s.outbounds) && s.outbounds[idx] != nil {
			label = stickyDiagnosticLabel(s.outbounds[idx].Tag())
		}
		b.WriteString(strconvItoa(idx))
		b.WriteByte(':')
		b.WriteString(label)
	}
	return b.String()
}

func stickyDiagnosticMemberLabel(s *LoadBalance, idx int) string {
	if idx >= 0 && idx < len(s.outbounds) && s.outbounds[idx] != nil {
		return stickyDiagnosticLabel(s.outbounds[idx].Tag())
	}
	if idx >= 0 && idx < len(s.tags) {
		return stickyDiagnosticLabel(s.tags[idx])
	}
	return "unknown"
}

func (s *LoadBalance) stickyDiagnosticFlowFields(ctx context.Context, network string, destination metadata.Socksaddr) []string {
	keySource := "none"
	key := ""
	if appKey := appStickyKey(ctx); appKey != "" {
		keySource = "app"
		key = appKey
	} else if destinationKey := s.stickySessionKey(ctx, destination); destinationKey != "" {
		keySource = "destination"
		key = destinationKey
	}
	keyHash := "none"
	if key != "" {
		keyHash = stickyDiagnosticHash(key)
	}
	fields := []string{
		stickyDiagnosticQuotedField("transport", stickyDiagnosticTransport(network)),
		stickyDiagnosticQuotedField("destination_kind", stickyDiagnosticDestinationKind(destination)),
		stickyDiagnosticBool("sticky_by_app", s.stickyByApp),
		stickyDiagnosticQuotedField("key_source", keySource),
		stickyDiagnosticBool("key_available", key != ""),
		stickyDiagnosticQuotedField("key_hash", keyHash),
	}
	return append(fields, stickyDiagnosticContextFields(ctx)...)
}

func (s *LoadBalance) stickyDiagnosticLog(ctx context.Context, event string, fields ...string) {
	if s == nil || !s.stickyDiagnostic || s.logger == nil || !ensureStickyDiagnosticHashSalt() {
		return
	}
	if ctx == nil {
		ctx = context.Background()
	}
	args := []any{
		"sticky_diag event=", event,
		" instance=", s.diagnosticInstanceID,
		" group=", strconvQuote(stickyDiagnosticLabel(s.diagnosticGroupTag)),
	}
	if flowID, ok := ctx.Value(stickyDiagnosticFlowIDKey{}).(uint64); ok {
		args = append(args, " flow_id=", flowID)
	}
	args = append(args, " ", strings.Join(fields, " "))
	s.logger.InfoContext(ctx, args...)
}
