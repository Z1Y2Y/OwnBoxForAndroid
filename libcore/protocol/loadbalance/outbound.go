package loadbalance

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"io"
	"math/rand"
	"net"
	"slices"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	urltestPkg "libcore/protocol/urltest"

	"golang.org/x/net/publicsuffix"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/common/urltest"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

const TypeLoadBalance = "loadbalance"

type LoadBalanceOptions struct {
	option.URLTestOutboundOptions
	Strategy             string   `json:"strategy,omitempty"`
	Default              string   `json:"default,omitempty"`
	StickyByApp          bool     `json:"sticky_by_app,omitempty"`
	StickyDiagnostic     bool     `json:"sticky_diagnostic,omitempty"`
	HealthyOutbounds     []string `json:"healthy_outbounds,omitempty"`
	UnavailableOutbounds []string `json:"unavailable_outbounds,omitempty"`
}

func RegisterLoadBalance(registry *outbound.Registry) {
	outbound.Register[LoadBalanceOptions](registry, TypeLoadBalance, NewLoadBalance)
}

var (
	_ adapter.Outbound                = (*LoadBalance)(nil)
	_ adapter.OutboundGroup           = (*LoadBalance)(nil)
	_ adapter.ConnectionHandler       = (*LoadBalance)(nil)
	_ adapter.PacketConnectionHandler = (*LoadBalance)(nil)
	_ adapter.Referrer                = (*LoadBalance)(nil)
)

type nodeStats struct {
	consecutiveFails atomic.Int32
	lastFailTime     atomic.Int64 // UnixMilli
	totalDials       atomic.Int64
	successDials     atomic.Int64
	latencyEmaMs     atomic.Int64
	healthHint       atomic.Int32
	healthHintUntil  atomic.Int64 // UnixMilli
}

const (
	healthHintUnavailable int32 = -1
	healthHintAvailable   int32 = 1
	healthHintLifetime          = 2 * time.Minute
)

func (s *nodeStats) setHealthHint(hint int32) {
	s.healthHint.Store(hint)
	s.healthHintUntil.Store(time.Now().Add(healthHintLifetime).UnixMilli())
}

func (s *nodeStats) clearHealthHint() {
	s.healthHint.Store(0)
	s.healthHintUntil.Store(0)
}

func (s *nodeStats) recordDialSuccess() {
	s.consecutiveFails.Store(0)
	s.totalDials.Add(1)
	s.successDials.Add(1)
	s.setHealthHint(healthHintAvailable)
}

func (s *nodeStats) recordSuccess(latencyMs int64) {
	s.recordDialSuccess()
	if latencyMs > 0 {
		old := s.latencyEmaMs.Load()
		if old <= 0 {
			s.latencyEmaMs.Store(latencyMs)
		} else {
			newEma := (old*8 + latencyMs*2) / 10
			s.latencyEmaMs.Store(newEma)
		}
	}
}

func (s *nodeStats) recordFailure() {
	s.consecutiveFails.Add(1)
	s.totalDials.Add(1)
	s.lastFailTime.Store(time.Now().UnixMilli())
	s.setHealthHint(healthHintUnavailable)
}

const virtualNodesPerPhysicalNode = 160

type ringEntry struct {
	hash    uint32
	nodeIdx int
}

type consistentHashRing struct {
	entries []ringEntry
}

func hash32(key string) uint32 {
	h := sha256.Sum256([]byte(key))
	return binary.BigEndian.Uint32(h[:4])
}

func newConsistentHashRing(tags []string) *consistentHashRing {
	n := len(tags)
	if n == 0 {
		return nil
	}
	totalVirtual := n * virtualNodesPerPhysicalNode
	entries := make([]ringEntry, 0, totalVirtual)
	for idx, tag := range tags {
		for v := 0; v < virtualNodesPerPhysicalNode; v++ {
			vKey := tag + "#v" + strconv.Itoa(v)
			entries = append(entries, ringEntry{
				hash:    hash32(vKey),
				nodeIdx: idx,
			})
		}
	}
	slices.SortFunc(entries, func(a, b ringEntry) int {
		if a.hash < b.hash {
			return -1
		} else if a.hash > b.hash {
			return 1
		}
		return a.nodeIdx - b.nodeIdx
	})
	return &consistentHashRing{entries: entries}
}

func (r *consistentHashRing) getCandidates(destHash uint32, n int, isDegraded func(int) bool) []int {
	if r == nil || len(r.entries) == 0 || n <= 0 {
		return nil
	}
	pos := sort.Search(len(r.entries), func(i int) bool {
		return r.entries[i].hash >= destHash
	})
	if pos >= len(r.entries) {
		pos = 0
	}

	seen := make([]bool, n)
	healthy := make([]int, 0, n)
	degraded := make([]int, 0, n)
	visitedCount := 0

	totalEntries := len(r.entries)
	for i := 0; i < totalEntries && visitedCount < n; i++ {
		entryIdx := (pos + i) % totalEntries
		nodeIdx := r.entries[entryIdx].nodeIdx
		if nodeIdx >= 0 && nodeIdx < n && !seen[nodeIdx] {
			seen[nodeIdx] = true
			visitedCount++
			if isDegraded(nodeIdx) {
				degraded = append(degraded, nodeIdx)
			} else {
				healthy = append(healthy, nodeIdx)
			}
		}
	}

	if visitedCount < n {
		for i := 0; i < n; i++ {
			if !seen[i] {
				seen[i] = true
				if isDegraded(i) {
					degraded = append(degraded, i)
				} else {
					healthy = append(healthy, i)
				}
			}
		}
	}

	if len(healthy) == 0 {
		return degraded
	}
	return append(healthy, degraded...)
}

type LoadBalance struct {
	outbound.Adapter
	ctx                          context.Context
	outbound                     adapter.OutboundManager
	connection                   adapter.ConnectionManager
	history                      *urltest.HistoryStorage
	pause                        pause.Manager
	pauseCallback                *list.Element[pause.Callback]
	logger                       log.ContextLogger
	tags                         []string
	strategy                     string
	stickyByApp                  bool
	link                         string
	interval                     time.Duration
	tolerance                    uint16
	idleTimeout                  time.Duration
	interruptExternalConnections bool
	outbounds                    []adapter.Outbound
	counter                      uint64
	randomIntn                   func(int) int
	activeConns                  []*atomic.Int64
	stats                        []*nodeStats
	healthyOutbounds             []string
	unavailableOutbounds         []string
	warnedAllUnavailable         atomic.Bool
	interruptGroup               *interrupt.Group
	ring                         *consistentHashRing
	ticker                       *time.Ticker
	close                        chan struct{}
	started                      bool
	lastActive                   common.TypedValue[time.Time]
	checking                     atomic.Bool
	access                       sync.Mutex
	stickyMu                     sync.RWMutex
	stickySessions               map[string]stickyEntry
	stickyFlights                map[string]*stickyFlight
	stickyRevision               atomic.Uint64
	lastSelectedIdx              atomic.Int32
	stickyDiagnostic             bool
	diagnosticInstanceID         uint64
	diagnosticFlowSequence       atomic.Uint64
	diagnosticGroupTag           string
}

func NewLoadBalance(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options LoadBalanceOptions) (adapter.Outbound, error) {
	interval := time.Duration(options.Interval)
	if interval == 0 {
		interval = C.DefaultURLTestInterval
	}
	idleTimeout := time.Duration(options.IdleTimeout)
	if idleTimeout == 0 {
		idleTimeout = C.DefaultURLTestIdleTimeout
	}
	link := options.URL
	if link == "" {
		link = urltestPkg.DefaultCFURL
	}
	lb := &LoadBalance{
		Adapter:                      outbound.NewAdapter(TypeLoadBalance, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.Outbounds),
		ctx:                          ctx,
		outbound:                     service.FromContext[adapter.OutboundManager](ctx),
		connection:                   service.FromContext[adapter.ConnectionManager](ctx),
		history:                      service.PtrFromContext[urltest.HistoryStorage](ctx),
		pause:                        service.FromContext[pause.Manager](ctx),
		logger:                       logger,
		tags:                         options.Outbounds,
		strategy:                     options.Strategy,
		stickyByApp:                  options.StickyByApp,
		healthyOutbounds:             options.HealthyOutbounds,
		unavailableOutbounds:         options.UnavailableOutbounds,
		stickyDiagnostic:             stickyDiagnosticEnabled(options.StickyDiagnostic),
		diagnosticGroupTag:           tag,
		link:                         link,
		interval:                     interval,
		tolerance:                    options.Tolerance,
		idleTimeout:                  idleTimeout,
		interruptExternalConnections: options.InterruptExistConnections,
		interruptGroup:               interrupt.NewGroup(),
		stickySessions:               make(map[string]stickyEntry),
		stickyFlights:                make(map[string]*stickyFlight),
		close:                        make(chan struct{}),
	}
	lb.lastSelectedIdx.Store(-1)
	if lb.stickyDiagnostic {
		lb.diagnosticInstanceID = nextStickyDiagnosticInstanceID()
		memberOrder := make([]int, len(lb.tags))
		for i := range memberOrder {
			memberOrder[i] = i
		}
		if lb.stickyDiagnostic {
			lb.stickyDiagnosticLog(ctx, "lb_init",
				stickyDiagnosticBool("sticky_by_app", lb.stickyByApp),
				stickyDiagnosticBool("proxy_tag", tag == "proxy"),
				stickyDiagnosticQuotedField("strategy", stickyDiagnosticStrategy(lb.strategy)),
				stickyDiagnosticInt("member_count", len(lb.tags)),
				stickyDiagnosticQuotedField("members", stickyDiagnosticCandidateOrder(lb, memberOrder)),
			)
		}
	}
	if len(lb.tags) == 0 {
		return nil, E.New("missing tags")
	}
	return lb, nil
}

func (s *LoadBalance) References() []string {
	return s.tags
}

func (s *LoadBalance) All() []string {
	return s.tags
}

func (s *LoadBalance) Now() string {
	candidates := s.candidateIndices(nil, M.Socksaddr{})
	if len(candidates) > 0 && candidates[0] < len(s.tags) {
		return s.tags[candidates[0]]
	}
	if len(s.tags) > 0 {
		return s.tags[0]
	}
	return ""
}

func (s *LoadBalance) Selected(network string) adapter.Outbound {
	tag := s.Now()
	for i, t := range s.tags {
		if t == tag && i < len(s.outbounds) {
			return s.outbounds[i]
		}
	}
	if len(s.outbounds) > 0 {
		return s.outbounds[0]
	}
	return nil
}

func (s *LoadBalance) AttachConnection(closer io.Closer) func() {
	s.Touch()
	return s.interruptGroup.Add(closer, true)
}

func (s *LoadBalance) URLTest(ctx context.Context) (map[string]uint16, error) {
	if s.checking.Swap(true) {
		return make(map[string]uint16), nil
	}
	defer s.checking.Store(false)

	result := urltestPkg.URLTestOutbounds(ctx, s.outbound, s.history, s.logger, s.outbounds, s.link, s.interval, true)
	for i, detour := range s.outbounds {
		tag := detour.Tag()
		if delay, ok := result[tag]; ok && delay > 0 {
			if i < len(s.stats) && s.stats[i] != nil {
				s.stats[i].recordSuccess(int64(delay))
			}
		} else if i < len(s.stats) && s.stats[i] != nil {
			s.stats[i].setHealthHint(healthHintUnavailable)
		}
	}
	return result, nil
}

func (s *LoadBalance) isLeastPing() bool {
	return s.strategy == "leastPing" || s.strategy == "least_ping"
}

func (s *LoadBalance) CheckOutbounds() {
	ctx, cancel := context.WithTimeout(s.ctx, 15*time.Second)
	defer cancel()
	_, _ = s.URLTest(ctx)
}

func (s *LoadBalance) PerformUpdateCheck() {
	if s.isLeastPing() {
		go s.CheckOutbounds()
	}
}

func (s *LoadBalance) Touch() {
	if !s.started || !s.isLeastPing() {
		return
	}
	s.access.Lock()
	defer s.access.Unlock()
	if s.ticker != nil {
		s.lastActive.Store(time.Now())
		return
	}
	if s.interval <= 0 {
		return
	}
	ticker := time.NewTicker(s.interval)
	s.ticker = ticker
	if s.pause != nil {
		s.pauseCallback = pause.RegisterTicker(s.pause, ticker, s.interval, nil)
	}
	go s.loopCheck(ticker, s.close)
}

func (s *LoadBalance) loopCheck(ticker *time.Ticker, closeChan <-chan struct{}) {
	for {
		select {
		case <-closeChan:
			return
		case <-ticker.C:
		}
		if s.idleTimeout > 0 && time.Since(s.lastActive.Load()) > s.idleTimeout {
			s.access.Lock()
			if s.ticker == ticker {
				s.ticker.Stop()
				s.ticker = nil
				if s.pause != nil && s.pauseCallback != nil {
					s.pause.UnregisterCallback(s.pauseCallback)
					s.pauseCallback = nil
				}
			}
			s.access.Unlock()
			return
		}
		s.CheckOutbounds()
	}
}

func (s *LoadBalance) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	switch stage {
	case adapter.StartStateStart:
		s.outbounds = make([]adapter.Outbound, 0, len(s.tags))
		s.activeConns = make([]*atomic.Int64, len(s.tags))
		s.stats = make([]*nodeStats, len(s.tags))
		for i, tag := range s.tags {
			detour, loaded := s.outbound.Outbound(tag)
			if !loaded {
				return E.New("outbound ", i, " not found: ", tag)
			}
			s.outbounds = append(s.outbounds, detour)
			s.activeConns[i] = new(atomic.Int64)
			s.stats[i] = new(nodeStats)
			if slices.Contains(s.unavailableOutbounds, tag) {
				s.stats[i].setHealthHint(healthHintUnavailable)
			} else if slices.Contains(s.healthyOutbounds, tag) {
				s.stats[i].setHealthHint(healthHintAvailable)
			}
		}
		s.ring = newConsistentHashRing(s.tags)
	case adapter.StartStateStarted:
		s.access.Lock()
		s.started = true
		s.lastActive.Store(time.Now())
		if s.interval > 0 && s.isLeastPing() {
			go s.CheckOutbounds()
		}
		s.access.Unlock()
		scope.Add(s.Close)
	}
	return nil
}

func (s *LoadBalance) Close() error {
	s.access.Lock()
	if s.ticker != nil {
		s.ticker.Stop()
		s.ticker = nil
		if s.pause != nil && s.pauseCallback != nil {
			s.pause.UnregisterCallback(s.pauseCallback)
			s.pauseCallback = nil
		}
	}
	if s.close != nil {
		select {
		case <-s.close:
		default:
			close(s.close)
		}
	}
	s.access.Unlock()
	if s.interruptGroup != nil {
		s.interruptGroup.Interrupt(true)
	}
	return nil
}

func extractRootDomain(fqdn string) string {
	s := strings.TrimSpace(strings.ToLower(fqdn))
	s = strings.TrimSuffix(s, ".")
	if s == "" {
		return ""
	}
	if host, _, err := net.SplitHostPort(s); err == nil {
		s = host
	} else if idx := strings.IndexByte(s, ':'); idx != -1 {
		s = s[:idx]
	}

	root, err := publicsuffix.EffectiveTLDPlusOne(s)
	if err == nil && root != "" {
		return root
	}
	return s
}

type stickyEntry struct {
	nodeIdx    int
	revision   uint64
	lastAccess int64 // UnixMilli
	expireAt   int64 // UnixMilli
}

type stickyFlight struct {
	done         chan struct{}
	waiters      atomic.Int32
	leaderFlowID uint64
	startedAt    time.Time
}

type stickyDialDiagnosticState struct {
	hasBinding          bool
	boundMemberIdx      int
	idleMs              int64
	expired             bool
	expiredMemberIdx    int
	expiredIdleMs       int64
	degradedSkipped     bool
	boundMemberFailed   bool
	boundFailureAttempt int
	boundFailureClass   string
}

type stickyRevisionUpdateResult struct {
	committed       bool
	reason          string
	previousIdx     int
	currentRevision uint64
	idleMs          int64
}

func newStickyDialDiagnosticState(enabled bool) *stickyDialDiagnosticState {
	if !enabled {
		return nil
	}
	return &stickyDialDiagnosticState{boundMemberIdx: -1, expiredMemberIdx: -1}
}

func (s *LoadBalance) isStickyEnabled() bool {
	return s.stickyByApp || s.strategy == "consistent_hash" || s.strategy == "consistentHash"
}

func appStickyKey(ctx context.Context) string {
	if ctx == nil {
		return ""
	}
	inbound := adapter.ContextFrom(ctx)
	if inbound == nil || inbound.ProcessInfo == nil || len(inbound.ProcessInfo.PackageNames) == 0 {
		return ""
	}
	packageNames := make([]string, 0, len(inbound.ProcessInfo.PackageNames))
	for _, packageName := range inbound.ProcessInfo.PackageNames {
		packageName = strings.ToLower(strings.TrimSpace(packageName))
		if packageName != "" {
			packageNames = append(packageNames, packageName)
		}
	}
	if len(packageNames) == 0 {
		return ""
	}
	slices.Sort(packageNames)
	packageNames = slices.Compact(packageNames)
	return "app:" + strings.Join(packageNames, ",")
}

func (s *LoadBalance) stickySessionKey(ctx context.Context, dest M.Socksaddr) string {
	if s.stickyByApp {
		if key := appStickyKey(ctx); key != "" {
			return key
		}
	}
	if s.strategy == "consistent_hash" || s.strategy == "consistentHash" {
		return destinationKey(ctx, dest)
	}
	return ""
}

func destinationKey(ctx context.Context, dest M.Socksaddr) string {
	var domain string
	if dest.Fqdn != "" {
		domain = dest.Fqdn
	} else if ctx != nil {
		if inCtx := adapter.ContextFrom(ctx); inCtx != nil && inCtx.Domain != "" {
			domain = inCtx.Domain
		}
	}
	if domain != "" {
		root := extractRootDomain(domain)
		if root != "" {
			return root
		}
		return strings.ToLower(domain)
	}
	if dest.IsIP() {
		addr := dest.Addr
		if addr.Is4() {
			b := addr.As4()
			return net.IPv4(b[0], b[1], b[2], 0).String() + "/24"
		} else if addr.Is6() {
			b := addr.As16()
			return net.IP{b[0], b[1], b[2], b[3], b[4], b[5], 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}.String() + "/48"
		}
		return addr.String()
	}
	s := dest.String()
	if s != "" && s != "0.0.0.0:0" && s != "[::]:0" {
		return s
	}
	return ""
}

func hashDestination(ctx context.Context, dest M.Socksaddr) uint32 {
	key := destinationKey(ctx, dest)
	if key != "" {
		return hash32(key)
	}
	return 0
}

func (s *LoadBalance) getStickySession(key string, now int64) (int, bool) {
	s.stickyMu.Lock()
	defer s.stickyMu.Unlock()
	if s.stickySessions == nil {
		return 0, false
	}
	entry, ok := s.stickySessions[key]
	if !ok || now > entry.expireAt {
		if ok {
			delete(s.stickySessions, key)
		}
		return 0, false
	}
	if entry.nodeIdx < 0 || entry.nodeIdx >= len(s.outbounds) {
		return 0, false
	}
	entry.lastAccess = now
	entry.expireAt = now + 5*60*1000
	s.stickySessions[key] = entry
	return entry.nodeIdx, true
}

func (s *LoadBalance) setStickySession(key string, nodeIdx int) {
	if key == "" || nodeIdx < 0 || nodeIdx >= len(s.outbounds) {
		return
	}
	now := time.Now().UnixMilli()
	s.stickyMu.Lock()
	defer s.stickyMu.Unlock()
	s.setStickySessionLocked(key, nodeIdx, now)
}

func (s *LoadBalance) setStickySessionIfRevision(key string, nodeIdx int, expectedRevision uint64) bool {
	return s.setStickySessionIfRevisionDetailed(key, nodeIdx, expectedRevision).committed
}

func (s *LoadBalance) setStickySessionIfRevisionDetailed(key string, nodeIdx int, expectedRevision uint64) stickyRevisionUpdateResult {
	if key == "" || nodeIdx < 0 || nodeIdx >= len(s.outbounds) {
		return stickyRevisionUpdateResult{reason: "invalid_member", previousIdx: -1}
	}
	now := time.Now().UnixMilli()
	s.stickyMu.Lock()
	defer s.stickyMu.Unlock()
	entry, ok := s.stickySessions[key]
	if !ok {
		return stickyRevisionUpdateResult{reason: "entry_missing", previousIdx: -1}
	}
	if now > entry.expireAt {
		delete(s.stickySessions, key)
		return stickyRevisionUpdateResult{
			reason:          "entry_expired",
			previousIdx:     entry.nodeIdx,
			currentRevision: entry.revision,
			idleMs:          max(int64(0), now-entry.lastAccess),
		}
	}
	if entry.revision != expectedRevision {
		return stickyRevisionUpdateResult{
			reason:          "stale_revision",
			previousIdx:     entry.nodeIdx,
			currentRevision: entry.revision,
			idleMs:          max(int64(0), now-entry.lastAccess),
		}
	}
	previousIdx := entry.nodeIdx
	s.setStickySessionLocked(key, nodeIdx, now)
	updated := s.stickySessions[key]
	return stickyRevisionUpdateResult{
		committed:       true,
		reason:          "updated",
		previousIdx:     previousIdx,
		currentRevision: updated.revision,
		idleMs:          max(int64(0), now-entry.lastAccess),
	}
}

func (s *LoadBalance) setStickySessionLocked(key string, nodeIdx int, now int64) {
	if s.stickySessions == nil {
		s.stickySessions = make(map[string]stickyEntry)
	}
	if entry, ok := s.stickySessions[key]; ok && now <= entry.expireAt && entry.nodeIdx == nodeIdx {
		entry.lastAccess = now
		entry.expireAt = now + 5*60*1000
		s.stickySessions[key] = entry
		return
	}
	if len(s.stickySessions) >= 1024 {
		for k, v := range s.stickySessions {
			if now > v.expireAt {
				delete(s.stickySessions, k)
			}
		}
		if len(s.stickySessions) >= 1024 {
			var oldestKey string
			var oldestTime int64 = 1<<62 - 1
			for k, v := range s.stickySessions {
				if v.lastAccess < oldestTime {
					oldestTime = v.lastAccess
					oldestKey = k
				}
			}
			if oldestKey != "" {
				delete(s.stickySessions, oldestKey)
			}
		}
	}
	s.stickySessions[key] = stickyEntry{
		nodeIdx:    nodeIdx,
		revision:   s.stickyRevision.Add(1),
		lastAccess: now,
		expireAt:   now + 5*60*1000,
	}
}

func (s *LoadBalance) beginStickyDial(ctx context.Context, key string) (*stickyFlight, uint64, error) {
	return s.beginStickyDialForFlow(ctx, key, nil)
}

func (s *LoadBalance) beginStickyDialForFlow(ctx context.Context, key string, trace *stickyDialDiagnosticState) (*stickyFlight, uint64, error) {
	if key == "" {
		return nil, 0, nil
	}
	if ctx == nil {
		ctx = context.Background()
	}
	for {
		if err := ctx.Err(); err != nil {
			if s.stickyDiagnostic {
				s.stickyDiagnosticLog(ctx, "flight_cancelled", stickyDiagnosticQuotedField("key_hash", stickyDiagnosticHash(key)))
			}
			return nil, 0, err
		}
		now := time.Now().UnixMilli()
		s.stickyMu.Lock()
		var expiredEntry stickyEntry
		expired := false
		expiredReason := "idle_timeout"
		if entry, ok := s.stickySessions[key]; ok {
			if now <= entry.expireAt && entry.nodeIdx >= 0 && entry.nodeIdx < len(s.outbounds) {
				idleMs := max(int64(0), now-entry.lastAccess)
				entry.lastAccess = now
				entry.expireAt = now + 5*60*1000
				s.stickySessions[key] = entry
				s.stickyMu.Unlock()
				if trace != nil {
					trace.hasBinding = true
					trace.boundMemberIdx = entry.nodeIdx
					trace.idleMs = idleMs
					trace.expired = false
					trace.expiredMemberIdx = -1
					trace.expiredIdleMs = 0
				}
				if s.stickyDiagnostic {
					s.stickyDiagnosticLog(ctx, "sticky_hit",
						stickyDiagnosticQuotedField("reason", "reuse"),
						stickyDiagnosticQuotedField("key_hash", stickyDiagnosticHash(key)),
						stickyDiagnosticInt("member_idx", entry.nodeIdx),
						stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, entry.nodeIdx)),
						stickyDiagnosticInt("idle_ms", int(idleMs)),
						stickyDiagnosticInt("remaining_ms", int(max(int64(0), entry.expireAt-now))),
						stickyDiagnosticUint64("revision", entry.revision),
					)
				}
				return nil, entry.revision, nil
			}
			expiredEntry = entry
			expired = true
			if now <= entry.expireAt {
				expiredReason = "member_invalid"
			}
			delete(s.stickySessions, key)
		}
		if s.stickyFlights == nil {
			s.stickyFlights = make(map[string]*stickyFlight)
		}
		if flight := s.stickyFlights[key]; flight != nil {
			done := flight.done
			flight.waiters.Add(1)
			waiters := flight.waiters.Load()
			leaderFlowID := flight.leaderFlowID
			s.stickyMu.Unlock()
			if expired {
				s.stickyDiagnosticExpiredEntry(ctx, key, expiredEntry, now, expiredReason)
			}
			if trace != nil && expired && expiredReason == "idle_timeout" {
				trace.expired = true
				trace.expiredMemberIdx = expiredEntry.nodeIdx
				trace.expiredIdleMs = max(int64(0), now-expiredEntry.lastAccess)
			}
			if s.stickyDiagnostic {
				s.stickyDiagnosticLog(ctx, "flight_wait",
					stickyDiagnosticQuotedField("key_hash", stickyDiagnosticHash(key)),
					stickyDiagnosticInt("waiters", int(waiters)),
					stickyDiagnosticUint64("leader_flow_id", leaderFlowID),
				)
			}
			select {
			case <-done:
				flight.waiters.Add(-1)
				if s.stickyDiagnostic {
					s.stickyDiagnosticLog(ctx, "flight_wakeup",
						stickyDiagnosticQuotedField("key_hash", stickyDiagnosticHash(key)),
						stickyDiagnosticUint64("leader_flow_id", leaderFlowID),
					)
				}
				continue
			case <-ctx.Done():
				flight.waiters.Add(-1)
				if s.stickyDiagnostic {
					s.stickyDiagnosticLog(ctx, "flight_wait_cancel",
						stickyDiagnosticQuotedField("key_hash", stickyDiagnosticHash(key)),
						stickyDiagnosticUint64("leader_flow_id", leaderFlowID),
					)
				}
				return nil, 0, ctx.Err()
			}
		}
		leaderFlowID := stickyDiagnosticFlowID(ctx)
		flight := &stickyFlight{done: make(chan struct{}), leaderFlowID: leaderFlowID, startedAt: time.Now()}
		s.stickyFlights[key] = flight
		s.stickyMu.Unlock()
		if expired {
			s.stickyDiagnosticExpiredEntry(ctx, key, expiredEntry, now, expiredReason)
		}
		if trace != nil && expired && expiredReason == "idle_timeout" {
			trace.expired = true
			trace.expiredMemberIdx = expiredEntry.nodeIdx
			trace.expiredIdleMs = max(int64(0), now-expiredEntry.lastAccess)
		}
		if s.stickyDiagnostic {
			s.stickyDiagnosticLog(ctx, "flight_leader",
				stickyDiagnosticQuotedField("key_hash", stickyDiagnosticHash(key)),
				stickyDiagnosticUint64("leader_flow_id", leaderFlowID),
			)
		}
		go s.watchStickyDialCancellation(ctx, key, flight)
		return flight, 0, nil
	}
}

func (s *LoadBalance) stickyDiagnosticExpiredEntry(ctx context.Context, key string, entry stickyEntry, now int64, reason string) {
	if !s.stickyDiagnostic {
		return
	}
	s.stickyDiagnosticLog(ctx, "sticky_expired",
		stickyDiagnosticQuotedField("reason", reason),
		stickyDiagnosticQuotedField("key_hash", stickyDiagnosticHash(key)),
		stickyDiagnosticInt("member_idx", entry.nodeIdx),
		stickyDiagnosticQuotedField("member", stickyDiagnosticMemberRef(s, entry.nodeIdx)),
		stickyDiagnosticInt("idle_ms", int(max(int64(0), now-entry.lastAccess))),
		stickyDiagnosticInt("expired_by_ms", int(max(int64(0), now-entry.expireAt))),
		stickyDiagnosticUint64("revision", entry.revision),
	)
}

func (s *LoadBalance) watchStickyDialCancellation(ctx context.Context, key string, flight *stickyFlight) {
	select {
	case <-flight.done:
		return
	case <-ctx.Done():
		s.stickyMu.Lock()
		removed := false
		if s.stickyFlights[key] == flight {
			delete(s.stickyFlights, key)
			close(flight.done)
			removed = true
		}
		s.stickyMu.Unlock()
		if removed {
			if s.stickyDiagnostic {
				s.stickyDiagnosticLog(ctx, "flight_owner_cancel",
					stickyDiagnosticQuotedField("key_hash", stickyDiagnosticHash(key)),
					stickyDiagnosticUint64("leader_flow_id", flight.leaderFlowID),
				)
			}
		}
	}
}

func (s *LoadBalance) finishStickyDial(key string, flight *stickyFlight, nodeIdx int) bool {
	return s.finishStickyDialForFlow(context.Background(), key, flight, nodeIdx, "")
}

func (s *LoadBalance) finishStickyDialForFlow(ctx context.Context, key string, flight *stickyFlight, nodeIdx int, reason string) bool {
	if key == "" || flight == nil {
		return false
	}
	if ctx == nil {
		ctx = context.Background()
	}
	s.stickyMu.Lock()
	if s.stickyFlights[key] != flight {
		s.stickyMu.Unlock()
		if s.stickyDiagnostic && nodeIdx >= 0 {
			s.stickyDiagnosticLog(ctx, "sticky_flight_rejected",
				stickyDiagnosticQuotedField("reason", "stale_flight"),
				stickyDiagnosticQuotedField("key_hash", stickyDiagnosticHash(key)),
				stickyDiagnosticUint64("leader_flow_id", flight.leaderFlowID),
				stickyDiagnosticInt("candidate_idx", nodeIdx),
				stickyDiagnosticQuotedField("candidate", stickyDiagnosticMemberRef(s, nodeIdx)),
			)
		}
		return false
	}
	if nodeIdx >= 0 {
		s.setStickySessionLocked(key, nodeIdx, time.Now().UnixMilli())
	}
	delete(s.stickyFlights, key)
	close(flight.done)
	flightMs := int64(0)
	if !flight.startedAt.IsZero() {
		flightMs = time.Since(flight.startedAt).Milliseconds()
	}
	leaderFlowID := flight.leaderFlowID
	s.stickyMu.Unlock()
	if s.stickyDiagnostic {
		fields := []string{
			stickyDiagnosticQuotedField("key_hash", stickyDiagnosticHash(key)),
			stickyDiagnosticUint64("leader_flow_id", leaderFlowID),
			stickyDiagnosticInt("flight_ms", int(flightMs)),
		}
		if nodeIdx >= 0 {
			fields = append(fields,
				stickyDiagnosticQuotedField("result", "bound"),
				stickyDiagnosticQuotedField("reason", reason),
				stickyDiagnosticInt("member_idx", nodeIdx),
				stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, nodeIdx)),
			)
		} else {
			fields = append(fields, stickyDiagnosticQuotedField("result", "released_without_success"))
			if reason == "" {
				reason = "no_success"
			}
			fields = append(fields, stickyDiagnosticQuotedField("reason", reason))
		}
		s.stickyDiagnosticLog(ctx, "flight_finish", fields...)
	}
	return true
}

func (s *LoadBalance) recordStickyDialSuccess(ctx context.Context, destination M.Socksaddr, coordinationKey string, flight *stickyFlight, expectedRevision uint64, nodeIdx int, trace *stickyDialDiagnosticState) {
	if !s.isStickyEnabled() {
		return
	}
	sessionKey := s.stickySessionKey(ctx, destination)
	if sessionKey == "" {
		return
	}
	if flight != nil && sessionKey == coordinationKey {
		expired := trace != nil && trace.expired
		previousIdx := -1
		idleMs := int64(0)
		if expired {
			previousIdx = trace.expiredMemberIdx
			idleMs = trace.expiredIdleMs
		}
		reason := stickyDiagnosticBindingReason(true, expired, false, false, false, false, "")
		committed := s.finishStickyDialForFlow(ctx, coordinationKey, flight, nodeIdx, reason)
		if !committed {
			reason = "stale_flight"
		}
		if s.stickyDiagnostic {
			s.stickyDiagnosticLog(ctx, "sticky_bind",
				stickyDiagnosticQuotedField("source", "first_dial"),
				stickyDiagnosticQuotedField("reason", reason),
				stickyDiagnosticBool("committed", committed),
				stickyDiagnosticInt("from_member_idx", previousIdx),
				stickyDiagnosticQuotedField("from_member", stickyDiagnosticMemberRef(s, previousIdx)),
				stickyDiagnosticInt("member_idx", nodeIdx),
				stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, nodeIdx)),
				stickyDiagnosticInt("idle_ms", int(idleMs)),
				stickyDiagnosticUint64("leader_flow_id", flight.leaderFlowID),
			)
		}
		if committed && expired && previousIdx >= 0 && previousIdx != nodeIdx && s.stickyDiagnostic {
			s.stickyDiagnosticLog(ctx, "sticky_rebind",
				stickyDiagnosticQuotedField("reason", "idle_timeout"),
				stickyDiagnosticInt("old_idx", previousIdx),
				stickyDiagnosticQuotedField("old_member", stickyDiagnosticMemberLabel(s, previousIdx)),
				stickyDiagnosticInt("new_idx", nodeIdx),
				stickyDiagnosticQuotedField("new_member", stickyDiagnosticMemberLabel(s, nodeIdx)),
				stickyDiagnosticInt("idle_ms", int(idleMs)),
			)
		}
	} else if coordinationKey != "" && sessionKey == coordinationKey {
		update := s.setStickySessionIfRevisionDetailed(sessionKey, nodeIdx, expectedRevision)
		reason := stickyDiagnosticBindingReason(false, false, update.committed && update.previousIdx == nodeIdx, trace != nil && trace.degradedSkipped, trace != nil && trace.boundMemberFailed, !update.committed, update.reason)
		if s.stickyDiagnostic {
			s.stickyDiagnosticLog(ctx, "sticky_bind",
				stickyDiagnosticQuotedField("source", "revision_guarded_fallback"),
				stickyDiagnosticQuotedField("reason", reason),
				stickyDiagnosticBool("committed", update.committed),
				stickyDiagnosticInt("from_member_idx", update.previousIdx),
				stickyDiagnosticQuotedField("from_member", stickyDiagnosticMemberRef(s, update.previousIdx)),
				stickyDiagnosticInt("member_idx", nodeIdx),
				stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, nodeIdx)),
				stickyDiagnosticInt("idle_ms", int(update.idleMs)),
				stickyDiagnosticUint64("expected_revision", expectedRevision),
				stickyDiagnosticUint64("observed_revision", update.currentRevision),
			)
		}
		if !update.committed && s.stickyDiagnostic {
			s.stickyDiagnosticLog(ctx, "sticky_revision_rejected",
				stickyDiagnosticQuotedField("reason", reason),
				stickyDiagnosticInt("current_member_idx", update.previousIdx),
				stickyDiagnosticQuotedField("current_member", stickyDiagnosticMemberRef(s, update.previousIdx)),
				stickyDiagnosticInt("candidate_idx", nodeIdx),
				stickyDiagnosticQuotedField("candidate", stickyDiagnosticMemberLabel(s, nodeIdx)),
				stickyDiagnosticUint64("expected_revision", expectedRevision),
				stickyDiagnosticUint64("observed_revision", update.currentRevision),
			)
		}
		if update.committed && update.previousIdx >= 0 && update.previousIdx != nodeIdx && s.stickyDiagnostic {
			fields := []string{
				stickyDiagnosticQuotedField("reason", reason),
				stickyDiagnosticInt("old_idx", update.previousIdx),
				stickyDiagnosticQuotedField("old_member", stickyDiagnosticMemberLabel(s, update.previousIdx)),
				stickyDiagnosticInt("new_idx", nodeIdx),
				stickyDiagnosticQuotedField("new_member", stickyDiagnosticMemberLabel(s, nodeIdx)),
				stickyDiagnosticInt("idle_ms", int(update.idleMs)),
			}
			if trace != nil && trace.boundMemberFailed {
				fields = append(fields,
					stickyDiagnosticInt("failed_attempt", trace.boundFailureAttempt),
					stickyDiagnosticQuotedField("error_class", trace.boundFailureClass),
				)
			}
			s.stickyDiagnosticLog(ctx, "sticky_rebind", fields...)
		}
	} else {
		s.setStickySession(sessionKey, nodeIdx)
		if s.stickyDiagnostic {
			s.stickyDiagnosticLog(ctx, "sticky_bind",
				stickyDiagnosticQuotedField("source", "session_key"),
				stickyDiagnosticQuotedField("reason", "key_based_selection"),
				stickyDiagnosticBool("committed", true),
				stickyDiagnosticInt("from_member_idx", -1),
				stickyDiagnosticQuotedField("from_member", "none"),
				stickyDiagnosticInt("member_idx", nodeIdx),
				stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, nodeIdx)),
			)
		}
	}
}

func (s *LoadBalance) isNodeDegraded(idx int, now int64) bool {
	if idx < 0 || idx >= len(s.stats) || s.stats[idx] == nil {
		return false
	}
	stats := s.stats[idx]
	if stats.healthHint.Load() == healthHintUnavailable && stats.healthHintUntil.Load() > now {
		return true
	}
	fails := stats.consecutiveFails.Load()
	lastFail := stats.lastFailTime.Load()
	return fails >= 2 && now-lastFail < 10_000
}

func (s *LoadBalance) healthPriority(idx int, now int64) int {
	if idx >= 0 && idx < len(s.stats) && s.stats[idx] != nil {
		stats := s.stats[idx]
		if stats.healthHintUntil.Load() > now {
			switch stats.healthHint.Load() {
			case healthHintAvailable:
				return 0
			case healthHintUnavailable:
				return 2
			}
		}
	}
	if s.isNodeDegraded(idx, now) {
		return 2
	}
	return 1
}

func (s *LoadBalance) prioritizeByHealth(indices []int, now int64) []int {
	available := make([]int, 0, len(indices))
	unknown := make([]int, 0, len(indices))
	unavailable := make([]int, 0, len(indices))
	for _, idx := range indices {
		switch s.healthPriority(idx, now) {
		case 0:
			available = append(available, idx)
		case 2:
			unavailable = append(unavailable, idx)
		default:
			unknown = append(unknown, idx)
		}
	}
	if len(available)+len(unknown) == 0 && len(unavailable) > 0 {
		if s.warnedAllUnavailable.CompareAndSwap(false, true) && s.logger != nil {
			s.logger.Warn("all load-balance members are temporarily marked unavailable; trying them in strategy order")
		}
		return indices
	}
	result := make([]int, 0, len(indices))
	result = append(result, available...)
	result = append(result, unknown...)
	result = append(result, unavailable...)
	return result
}

func (s *LoadBalance) candidateIndices(ctx context.Context, dest M.Socksaddr) []int {
	return s.candidateIndicesForFlow(ctx, dest, nil)
}

func (s *LoadBalance) candidateIndicesForFlow(ctx context.Context, dest M.Socksaddr, trace *stickyDialDiagnosticState) []int {
	n := len(s.outbounds)
	if n == 0 {
		n = len(s.tags)
	}
	if n == 0 {
		return nil
	}
	indices := make([]int, n)
	for i := 0; i < n; i++ {
		indices[i] = i
	}
	now := time.Now().UnixMilli()

	var result []int
	switch s.strategy {
	case "failover":
		healthy := make([]int, 0, n)
		degraded := make([]int, 0, n)
		for i := 0; i < n; i++ {
			if s.isNodeDegraded(i, now) {
				degraded = append(degraded, i)
			} else {
				healthy = append(healthy, i)
			}
		}
		if len(healthy) == 0 {
			result = indices
		} else {
			result = append(healthy, degraded...)
		}

	case "stable":
		scores := make([]int64, n)
		for i := 0; i < n; i++ {
			var total, success, fails, lastFail, latency int64
			if i < len(s.stats) && s.stats[i] != nil {
				total = s.stats[i].totalDials.Load()
				success = s.stats[i].successDials.Load()
				fails = int64(s.stats[i].consecutiveFails.Load())
				lastFail = s.stats[i].lastFailTime.Load()
				latency = s.stats[i].latencyEmaMs.Load()
			}
			var successRate int64 = 100
			if total > 0 {
				successRate = (success * 100) / total
			}
			var failPenalty int64 = 0
			if fails > 0 && now-lastFail < 60_000 {
				failPenalty = fails * 200
			}
			if latency <= 0 && s.history != nil && i < len(s.tags) {
				if h := s.history.LoadURLTestHistory(s.tags[i]); h != nil && h.Delay > 0 {
					latency = int64(h.Delay)
				}
			}
			if latency <= 0 {
				latency = 50
			}
			scores[i] = (successRate * 10) - failPenalty - (latency / 5)
		}
		res := make([]int, n)
		copy(res, indices)
		slices.SortStableFunc(res, func(a, b int) int {
			sa := scores[a]
			sb := scores[b]
			if sa > sb {
				return -1
			} else if sa < sb {
				return 1
			}
			return 0
		})
		result = res

	case "leastPing", "least_ping":
		healthy := make([]int, 0, n)
		degraded := make([]int, 0, n)
		for i := 0; i < n; i++ {
			if s.isNodeDegraded(i, now) {
				degraded = append(degraded, i)
			} else {
				healthy = append(healthy, i)
			}
		}
		if len(healthy) == 0 {
			healthy = indices
			degraded = nil
		}
		latencies := make([]int64, n)
		for _, i := range indices {
			var l int64
			if s.history != nil {
				if i < len(s.tags) {
					if h := s.history.LoadURLTestHistory(s.tags[i]); h != nil && h.Delay > 0 {
						l = int64(h.Delay)
					}
				}
				if l <= 0 && i < len(s.outbounds) && s.outbounds[i] != nil {
					if h := s.history.LoadURLTestHistory(s.outbounds[i].Tag()); h != nil && h.Delay > 0 {
						l = int64(h.Delay)
					}
				}
			}
			if l <= 0 && i < len(s.stats) && s.stats[i] != nil {
				l = s.stats[i].latencyEmaMs.Load()
			}
			if l <= 0 {
				l = 9999
			}
			latencies[i] = l
		}

		slices.SortStableFunc(healthy, func(a, b int) int {
			la := latencies[a]
			lb := latencies[b]
			if la < lb {
				return -1
			} else if la > lb {
				return 1
			}
			return 0
		})

		// 滞后容差（Hysteresis Tolerance）保护：
		// 若当前活跃节点依然健康且有测速，仅在最优候选节点比当前活跃节点快超过 s.tolerance 时才允许切换到首位，
		// 避免 1ms 微小抖动导致策略组频繁颠簸断流。
		currentIdx := int(s.lastSelectedIdx.Load())
		if currentIdx >= 0 && currentIdx < n && len(healthy) > 1 && s.tolerance > 0 {
			bestIdx := healthy[0]
			if bestIdx != currentIdx {
				currentDelay := latencies[currentIdx]
				bestDelay := latencies[bestIdx]
				if currentDelay < 9999 && currentDelay <= bestDelay+int64(s.tolerance) {
					// 优势未超出容差，保持当前活跃节点优先
					for pos, idx := range healthy {
						if idx == currentIdx {
							copy(healthy[1:pos+1], healthy[0:pos])
							healthy[0] = currentIdx
							break
						}
					}
				}
			}
		}

		if len(healthy) > 0 {
			s.lastSelectedIdx.Store(int32(healthy[0]))
		}
		result = append(healthy, degraded...)

	case "leastLoad", "least_load":
		healthy := make([]int, 0, n)
		degraded := make([]int, 0, n)
		for i := 0; i < n; i++ {
			if s.isNodeDegraded(i, now) {
				degraded = append(degraded, i)
			} else {
				healthy = append(healthy, i)
			}
		}
		if len(healthy) == 0 {
			healthy = indices
			degraded = nil
		}
		hn := len(healthy)
		rotated := make([]int, hn)
		start := int(atomic.AddUint64(&s.counter, 1) % uint64(hn))
		for i := 0; i < hn; i++ {
			rotated[i] = healthy[(start+i)%hn]
		}
		slices.SortStableFunc(rotated, func(a, b int) int {
			var ca, cb int64
			if a < len(s.activeConns) && s.activeConns[a] != nil {
				ca = s.activeConns[a].Load()
			}
			if b < len(s.activeConns) && s.activeConns[b] != nil {
				cb = s.activeConns[b].Load()
			}
			if ca < cb {
				return -1
			} else if ca > cb {
				return 1
			}
			return 0
		})
		result = append(rotated, degraded...)

	case "consistent_hash", "consistentHash":
		if s.ring == nil && len(s.tags) > 0 {
			s.ring = newConsistentHashRing(s.tags)
		}
		if s.ring != nil {
			var h uint32
			hasDest := dest.Fqdn != "" || dest.IsIP()
			if !hasDest && ctx != nil {
				if inCtx := adapter.ContextFrom(ctx); inCtx != nil && inCtx.Domain != "" {
					hasDest = true
				}
			}
			if hasDest {
				h = hashDestination(ctx, dest)
			}
			if h == 0 {
				h = uint32(atomic.AddUint64(&s.counter, 1))
			}
			result = s.ring.getCandidates(h, n, func(idx int) bool {
				return s.isNodeDegraded(idx, now)
			})
		} else {
			result = indices
		}

	case "random":
		healthy := make([]int, 0, n)
		degraded := make([]int, 0, n)
		for i := 0; i < n; i++ {
			if s.isNodeDegraded(i, now) {
				degraded = append(degraded, i)
			} else {
				healthy = append(healthy, i)
			}
		}
		if len(healthy) == 0 {
			healthy = indices
			degraded = nil
		}
		hn := len(healthy)
		start := rand.Intn(hn)
		if s.randomIntn != nil {
			start = s.randomIntn(hn)
		}
		rotated := make([]int, hn)
		for i := 0; i < hn; i++ {
			rotated[i] = healthy[(start+i)%hn]
		}
		result = append(rotated, degraded...)

	case "round_robin", "roundRobin":
		fallthrough
	default:
		healthy := make([]int, 0, n)
		degraded := make([]int, 0, n)
		for i := 0; i < n; i++ {
			if s.isNodeDegraded(i, now) {
				degraded = append(degraded, i)
			} else {
				healthy = append(healthy, i)
			}
		}
		if len(healthy) == 0 {
			healthy = indices
			degraded = nil
		}
		hn := len(healthy)
		rotated := make([]int, hn)
		start := int(atomic.AddUint64(&s.counter, 1) % uint64(hn))
		for i := 0; i < hn; i++ {
			rotated[i] = healthy[(start+i)%hn]
		}
		result = append(rotated, degraded...)
	}
	result = s.prioritizeByHealth(result, now)

	if s.isStickyEnabled() {
		stickyKey := s.stickySessionKey(ctx, dest)
		if stickyKey != "" && len(result) > 1 {
			if stickyIdx, ok := s.getStickySession(stickyKey, now); ok {
				stickyPos := -1
				for i, idx := range result {
					if idx == stickyIdx {
						stickyPos = i
						break
					}
				}
				degraded := false
				if stickyPos >= 0 {
					degraded = s.isNodeDegraded(stickyIdx, now)
				}
				if trace != nil {
					trace.hasBinding = true
					trace.boundMemberIdx = stickyIdx
					trace.degradedSkipped = degraded && stickyPos > 0
				}
				if trace != nil && s.stickyDiagnostic && degraded && stickyPos > 0 {
					fails := int32(0)
					lastFailAgeMs := int64(0)
					if stickyIdx >= 0 && stickyIdx < len(s.stats) && s.stats[stickyIdx] != nil {
						fails = s.stats[stickyIdx].consecutiveFails.Load()
						lastFailAgeMs = max(int64(0), now-s.stats[stickyIdx].lastFailTime.Load())
					}
					s.stickyDiagnosticLog(ctx, "sticky_member_degraded",
						stickyDiagnosticQuotedField("reason", "health_degraded"),
						stickyDiagnosticQuotedField("key_hash", stickyDiagnosticHash(stickyKey)),
						stickyDiagnosticInt("member_idx", stickyIdx),
						stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, stickyIdx)),
						stickyDiagnosticInt("candidate_pos", stickyPos),
						stickyDiagnosticInt("consecutive_fails", int(fails)),
						stickyDiagnosticInt("last_fail_age_ms", int(lastFailAgeMs)),
					)
				}
				if stickyPos > 0 && !degraded && s.healthPriority(stickyIdx, now) <= s.healthPriority(result[0], now) {
					copy(result[1:stickyPos+1], result[0:stickyPos])
					result[0] = stickyIdx
				}
			}
		}
	}
	return result
}

type trackedConn struct {
	net.Conn
	onClose func()
	closed  atomic.Bool
}

func (c *trackedConn) Close() error {
	if c.closed.CompareAndSwap(false, true) {
		if c.onClose != nil {
			c.onClose()
		}
	}
	if c.Conn != nil {
		return c.Conn.Close()
	}
	return nil
}

func (c *trackedConn) ReaderReplaceable() bool {
	return true
}

func (c *trackedConn) WriterReplaceable() bool {
	return true
}

func (c *trackedConn) Upstream() any {
	return c.Conn
}

func (c *trackedConn) SyscallConn() (syscall.RawConn, error) {
	syscallConn, isSyscallConn := c.Conn.(syscall.Conn)
	if !isSyscallConn {
		return nil, syscall.EINVAL
	}
	return syscallConn.SyscallConn()
}

func (s *LoadBalance) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx = s.stickyDiagnosticWithFlowID(ctx)
	s.Touch()
	stickyTrace := newStickyDialDiagnosticState(s.stickyDiagnostic)
	if s.stickyDiagnostic {
		s.stickyDiagnosticLog(ctx, "flow_start", s.stickyDiagnosticFlowFields(ctx, network, destination)...)
	}
	var stickyKey string
	var stickyFlight *stickyFlight
	var stickyRevision uint64
	if s.stickyByApp {
		stickyKey = appStickyKey(ctx)
		if stickyKey != "" {
			var err error
			stickyFlight, stickyRevision, err = s.beginStickyDialForFlow(ctx, stickyKey, stickyTrace)
			if err != nil {
				if s.stickyDiagnostic {
					s.stickyDiagnosticLog(ctx, "flow_abort", stickyDiagnosticQuotedField("error_class", stickyDiagnosticErrorClass(err)))
				}
				return nil, err
			}
			if stickyFlight != nil {
				defer s.finishStickyDialForFlow(ctx, stickyKey, stickyFlight, -1, "no_success")
			}
		}
	}
	indices := s.candidateIndicesForFlow(ctx, destination, stickyTrace)
	n := len(indices)
	if n == 0 {
		if s.stickyDiagnostic {
			s.stickyDiagnosticLog(ctx, "flow_result", stickyDiagnosticQuotedField("result", "no_candidates"))
		}
		return nil, E.New("no outbounds available")
	}
	if s.stickyDiagnostic {
		s.stickyDiagnosticLog(ctx, "candidate_order", stickyDiagnosticQuotedField("order", stickyDiagnosticCandidateOrder(s, indices)))
	}
	var lastErr error
	for i, idx := range indices {
		candidate := s.outbounds[idx]
		if s.stickyDiagnostic {
			s.stickyDiagnosticLog(ctx, "attempt_start",
				stickyDiagnosticInt("candidate_idx", idx),
				stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, idx)),
				stickyDiagnosticInt("attempt", i+1),
			)
		}
		var (
			conn net.Conn
			err  error
		)
		if i < n-1 {
			timeout := 3500 * time.Millisecond
			if s.isLeastPing() {
				timeout = 2500 * time.Millisecond
				if idx < len(s.stats) && s.stats[idx] != nil {
					ema := s.stats[idx].latencyEmaMs.Load()
					if ema > 0 {
						dynamic := time.Duration(ema*3) * time.Millisecond
						if dynamic < 2000*time.Millisecond {
							timeout = 2000 * time.Millisecond
						} else if dynamic > 4500*time.Millisecond {
							timeout = 4500 * time.Millisecond
						} else {
							timeout = dynamic
						}
					}
					if s.stats[idx].consecutiveFails.Load() > 0 {
						if timeout > 2000*time.Millisecond {
							timeout = 2000 * time.Millisecond
						}
					}
				}
			}
			candidateCtx, cancel := context.WithTimeout(ctx, timeout)
			conn, err = candidate.DialContext(candidateCtx, network, destination)
			cancel()
		} else {
			conn, err = candidate.DialContext(ctx, network, destination)
		}
		if err == nil {
			if stickyKey != "" && ctx.Err() != nil {
				conn.Close()
				if s.stickyDiagnostic {
					s.stickyDiagnosticLog(ctx, "attempt_discarded",
						stickyDiagnosticInt("candidate_idx", idx),
						stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, idx)),
						stickyDiagnosticQuotedField("reason", "context_cancelled"),
					)
				}
				return nil, ctx.Err()
			}
			if s.stickyDiagnostic {
				s.stickyDiagnosticLog(ctx, "attempt_result",
					stickyDiagnosticInt("candidate_idx", idx),
					stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, idx)),
					stickyDiagnosticQuotedField("result", "success"),
				)
			}
			adapter.RecordActiveOutbound(ctx, candidate.Tag())
			if idx < len(s.stats) && s.stats[idx] != nil {
				s.stats[idx].recordDialSuccess()
			}
			s.recordStickyDialSuccess(ctx, destination, stickyKey, stickyFlight, stickyRevision, idx, stickyTrace)
			if s.stickyDiagnostic {
				s.stickyDiagnosticLog(ctx, "flow_result",
					stickyDiagnosticQuotedField("result", "success"),
					stickyDiagnosticQuotedField("transport", "tcp"),
					stickyDiagnosticInt("member_idx", idx),
					stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, idx)),
				)
			}
			if (s.strategy == "leastLoad" || s.strategy == "least_load") && idx < len(s.activeConns) && s.activeConns[idx] != nil {
				s.activeConns[idx].Add(1)
				conn = &trackedConn{
					Conn: conn,
					onClose: func() {
						val := s.activeConns[idx].Add(-1)
						if val < 0 {
							s.activeConns[idx].Store(0)
						}
					},
				}
			}
			return s.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
		}
		if idx < len(s.stats) && s.stats[idx] != nil {
			s.stats[idx].recordFailure()
		}
		if stickyTrace != nil && stickyTrace.hasBinding && idx == stickyTrace.boundMemberIdx {
			stickyTrace.boundMemberFailed = true
			stickyTrace.boundFailureAttempt = i + 1
			stickyTrace.boundFailureClass = stickyDiagnosticErrorClass(err)
		}
		if s.stickyDiagnostic {
			s.stickyDiagnosticLog(ctx, "attempt_result",
				stickyDiagnosticInt("candidate_idx", idx),
				stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, idx)),
				stickyDiagnosticQuotedField("result", "failure"),
				stickyDiagnosticQuotedField("error_class", stickyDiagnosticErrorClass(err)),
			)
		}
		if s.stickyDiagnostic && stickyKey != "" && i < n-1 {
			reason := "candidate_failure"
			if stickyTrace != nil && stickyTrace.hasBinding && idx == stickyTrace.boundMemberIdx {
				reason = "bound_member_failure"
			}
			s.stickyDiagnosticLog(ctx, "dial_fallback",
				stickyDiagnosticQuotedField("reason", reason),
				stickyDiagnosticInt("failed_idx", idx),
				stickyDiagnosticQuotedField("failed_member", stickyDiagnosticMemberLabel(s, idx)),
				stickyDiagnosticInt("next_idx", indices[i+1]),
				stickyDiagnosticInt("attempt", i+1),
				stickyDiagnosticQuotedField("error_class", stickyDiagnosticErrorClass(err)),
			)
		}
		lastErr = err
	}
	if s.stickyDiagnostic {
		s.stickyDiagnosticLog(ctx, "flow_result",
			stickyDiagnosticQuotedField("result", "failure"),
			stickyDiagnosticQuotedField("transport", "tcp"),
			stickyDiagnosticQuotedField("error_class", stickyDiagnosticErrorClass(lastErr)),
		)
	}
	return nil, lastErr
}

type trackedPacketConn struct {
	net.PacketConn
	onClose func()
	closed  atomic.Bool
}

func (c *trackedPacketConn) Close() error {
	if c.closed.CompareAndSwap(false, true) {
		if c.onClose != nil {
			c.onClose()
		}
	}
	if c.PacketConn != nil {
		return c.PacketConn.Close()
	}
	return nil
}

func (c *trackedPacketConn) ReaderReplaceable() bool {
	return true
}

func (c *trackedPacketConn) WriterReplaceable() bool {
	return true
}

func (c *trackedPacketConn) Upstream() any {
	return c.PacketConn
}

func (c *trackedPacketConn) SyscallConn() (syscall.RawConn, error) {
	syscallConn, isSyscallConn := c.PacketConn.(syscall.Conn)
	if !isSyscallConn {
		return nil, syscall.EINVAL
	}
	return syscallConn.SyscallConn()
}

func (s *LoadBalance) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx = s.stickyDiagnosticWithFlowID(ctx)
	s.Touch()
	stickyTrace := newStickyDialDiagnosticState(s.stickyDiagnostic)
	if s.stickyDiagnostic {
		if s.stickyDiagnostic {
			s.stickyDiagnosticLog(ctx, "flow_start", s.stickyDiagnosticFlowFields(ctx, "udp", destination)...)
		}
	}
	var stickyKey string
	var stickyFlight *stickyFlight
	var stickyRevision uint64
	if s.stickyByApp {
		stickyKey = appStickyKey(ctx)
		if stickyKey != "" {
			var err error
			stickyFlight, stickyRevision, err = s.beginStickyDialForFlow(ctx, stickyKey, stickyTrace)
			if err != nil {
				if s.stickyDiagnostic {
					s.stickyDiagnosticLog(ctx, "flow_abort", stickyDiagnosticQuotedField("error_class", stickyDiagnosticErrorClass(err)))
				}
				return nil, err
			}
			if stickyFlight != nil {
				defer s.finishStickyDialForFlow(ctx, stickyKey, stickyFlight, -1, "no_success")
			}
		}
	}
	indices := s.candidateIndicesForFlow(ctx, destination, stickyTrace)
	n := len(indices)
	if n == 0 {
		if s.stickyDiagnostic {
			s.stickyDiagnosticLog(ctx, "flow_result", stickyDiagnosticQuotedField("result", "no_candidates"))
		}
		return nil, E.New("no outbounds available")
	}
	if s.stickyDiagnostic {
		s.stickyDiagnosticLog(ctx, "candidate_order", stickyDiagnosticQuotedField("order", stickyDiagnosticCandidateOrder(s, indices)))
	}
	var lastErr error
	for i, idx := range indices {
		candidate := s.outbounds[idx]
		if s.stickyDiagnostic {
			s.stickyDiagnosticLog(ctx, "attempt_start",
				stickyDiagnosticInt("candidate_idx", idx),
				stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, idx)),
				stickyDiagnosticInt("attempt", i+1),
			)
		}
		var (
			conn net.PacketConn
			err  error
		)
		if i < n-1 {
			timeout := 3500 * time.Millisecond
			if s.isLeastPing() {
				timeout = 2500 * time.Millisecond
				if idx < len(s.stats) && s.stats[idx] != nil {
					ema := s.stats[idx].latencyEmaMs.Load()
					if ema > 0 {
						dynamic := time.Duration(ema*3) * time.Millisecond
						if dynamic < 2000*time.Millisecond {
							timeout = 2000 * time.Millisecond
						} else if dynamic > 4500*time.Millisecond {
							timeout = 4500 * time.Millisecond
						} else {
							timeout = dynamic
						}
					}
					if s.stats[idx].consecutiveFails.Load() > 0 {
						if timeout > 2000*time.Millisecond {
							timeout = 2000 * time.Millisecond
						}
					}
				}
			}
			candidateCtx, cancel := context.WithTimeout(ctx, timeout)
			conn, err = candidate.ListenPacket(candidateCtx, destination)
			cancel()
		} else {
			conn, err = candidate.ListenPacket(ctx, destination)
		}
		if err == nil {
			if stickyKey != "" && ctx.Err() != nil {
				conn.Close()
				if s.stickyDiagnostic {
					s.stickyDiagnosticLog(ctx, "attempt_discarded",
						stickyDiagnosticInt("candidate_idx", idx),
						stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, idx)),
						stickyDiagnosticQuotedField("reason", "context_cancelled"),
					)
				}
				return nil, ctx.Err()
			}
			if s.stickyDiagnostic {
				s.stickyDiagnosticLog(ctx, "attempt_result",
					stickyDiagnosticInt("candidate_idx", idx),
					stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, idx)),
					stickyDiagnosticQuotedField("result", "success"),
				)
			}
			adapter.RecordActiveOutbound(ctx, candidate.Tag())
			if idx < len(s.stats) && s.stats[idx] != nil {
				s.stats[idx].recordDialSuccess()
			}
			s.recordStickyDialSuccess(ctx, destination, stickyKey, stickyFlight, stickyRevision, idx, stickyTrace)
			if s.stickyDiagnostic {
				s.stickyDiagnosticLog(ctx, "flow_result",
					stickyDiagnosticQuotedField("result", "success"),
					stickyDiagnosticQuotedField("transport", "udp"),
					stickyDiagnosticInt("member_idx", idx),
					stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, idx)),
				)
			}
			if (s.strategy == "leastLoad" || s.strategy == "least_load") && idx < len(s.activeConns) && s.activeConns[idx] != nil {
				s.activeConns[idx].Add(1)
				conn = &trackedPacketConn{
					PacketConn: conn,
					onClose: func() {
						val := s.activeConns[idx].Add(-1)
						if val < 0 {
							s.activeConns[idx].Store(0)
						}
					},
				}
			}
			return s.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
		}
		if idx < len(s.stats) && s.stats[idx] != nil {
			s.stats[idx].recordFailure()
		}
		if stickyTrace != nil && stickyTrace.hasBinding && idx == stickyTrace.boundMemberIdx {
			stickyTrace.boundMemberFailed = true
			stickyTrace.boundFailureAttempt = i + 1
			stickyTrace.boundFailureClass = stickyDiagnosticErrorClass(err)
		}
		if s.stickyDiagnostic {
			s.stickyDiagnosticLog(ctx, "attempt_result",
				stickyDiagnosticInt("candidate_idx", idx),
				stickyDiagnosticQuotedField("member", stickyDiagnosticMemberLabel(s, idx)),
				stickyDiagnosticQuotedField("result", "failure"),
				stickyDiagnosticQuotedField("error_class", stickyDiagnosticErrorClass(err)),
			)
		}
		if s.stickyDiagnostic && stickyKey != "" && i < n-1 {
			reason := "candidate_failure"
			if stickyTrace != nil && stickyTrace.hasBinding && idx == stickyTrace.boundMemberIdx {
				reason = "bound_member_failure"
			}
			s.stickyDiagnosticLog(ctx, "dial_fallback",
				stickyDiagnosticQuotedField("reason", reason),
				stickyDiagnosticInt("failed_idx", idx),
				stickyDiagnosticQuotedField("failed_member", stickyDiagnosticMemberLabel(s, idx)),
				stickyDiagnosticInt("next_idx", indices[i+1]),
				stickyDiagnosticInt("attempt", i+1),
				stickyDiagnosticQuotedField("error_class", stickyDiagnosticErrorClass(err)),
			)
		}
		lastErr = err
	}
	if s.stickyDiagnostic {
		s.stickyDiagnosticLog(ctx, "flow_result",
			stickyDiagnosticQuotedField("result", "failure"),
			stickyDiagnosticQuotedField("transport", "udp"),
			stickyDiagnosticQuotedField("error_class", stickyDiagnosticErrorClass(lastErr)),
		)
	}
	return nil, lastErr
}

func (s *LoadBalance) NewConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = adapter.ContextWithActiveOutboundTracker(ctx, metadata)
	ctx = interrupt.ContextWithIsExternalConnection(ctx, true)
	s.connection.NewConnection(ctx, s, conn, metadata, onClose)
}

func (s *LoadBalance) NewPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = adapter.ContextWithActiveOutboundTracker(ctx, metadata)
	ctx = interrupt.ContextWithIsExternalConnection(ctx, true)
	s.connection.NewPacketConnection(ctx, s, conn, metadata, onClose)
}
