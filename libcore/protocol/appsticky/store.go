package appsticky

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	Version       = 1
	MaxEntries    = 1024
	maxFileSize   = 1 << 20
	writeInterval = 30 * time.Second
)

type Entry struct {
	Tag           string `json:"tag"`
	LastSuccessMs int64  `json:"last_success_ms"`
}

type state struct {
	Version int              `json:"version"`
	Entries map[string]Entry `json:"entries"`
}

// Store holds app-to-member bindings for one policy group. It is safe for concurrent use.
type Store struct {
	mu              sync.Mutex
	path            string
	enabled         bool
	entries         map[string]Entry
	lastPersistedAt time.Time
}

// Open loads a versioned state. When disabled, it removes any old file and never reads it.
func Open(path string, enabled bool) (*Store, error) {
	s := &Store{path: path, enabled: enabled, entries: make(map[string]Entry)}
	if !enabled {
		if path != "" {
			err := os.Remove(path)
			if err != nil && !errors.Is(err, os.ErrNotExist) {
				return nil, err
			}
		}
		return s, nil
	}
	if path == "" {
		return s, nil
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	if len(data) > maxFileSize {
		return s, nil
	}
	var saved state
	if json.Unmarshal(data, &saved) != nil || saved.Version != Version || saved.Entries == nil {
		return s, nil
	}
	for key, entry := range saved.Entries {
		if valid(key, entry) && len(s.entries) < MaxEntries {
			s.entries[key] = entry
		}
	}
	return s, nil
}

func valid(key string, entry Entry) bool {
	return strings.HasPrefix(key, "app:") && len(key) > len("app:") && len(key) <= 4096 &&
		entry.Tag != "" && len(entry.Tag) <= 256 && entry.LastSuccessMs > 0
}

// Snapshot returns valid, non-expired records as a detached map. maxAge <= 0 disables age filtering.
func (s *Store) Snapshot(now time.Time, maxAge time.Duration) map[string]Entry {
	out := make(map[string]Entry)
	if s == nil || !s.enabled {
		return out
	}
	nowMs := now.UnixMilli()
	s.mu.Lock()
	defer s.mu.Unlock()
	for key, entry := range s.entries {
		age := nowMs - entry.LastSuccessMs
		if age < 0 || (maxAge > 0 && time.Duration(age)*time.Millisecond > maxAge) {
			continue
		}
		out[key] = entry
	}
	return out
}

// Set records a successful binding. Non-app keys are deliberately rejected.
func (s *Store) Set(key, tag string, when time.Time) error {
	if s == nil || !s.enabled || !valid(key, Entry{Tag: tag, LastSuccessMs: when.UnixMilli()}) {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	previous, exists := s.entries[key]
	if !exists && len(s.entries) >= MaxEntries {
		var oldestKey string
		oldest := int64(^uint64(0) >> 1)
		for k, entry := range s.entries {
			if entry.LastSuccessMs < oldest {
				oldest, oldestKey = entry.LastSuccessMs, k
			}
		}
		delete(s.entries, oldestKey)
	}
	s.entries[key] = Entry{Tag: tag, LastSuccessMs: when.UnixMilli()}
	if exists && previous.Tag == tag && !s.lastPersistedAt.IsZero() && when.Sub(s.lastPersistedAt) < writeInterval {
		return nil
	}
	if err := s.saveLocked(); err != nil {
		return err
	}
	s.lastPersistedAt = when
	return nil
}

func (s *Store) saveLocked() error {
	if s.path == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0700); err != nil {
		return err
	}
	data, err := json.Marshal(state{Version: Version, Entries: s.entries})
	if err != nil {
		return err
	}
	if len(data) > maxFileSize {
		return errors.New("app sticky state exceeds maximum file size")
	}
	tmp, err := os.CreateTemp(filepath.Dir(s.path), ".appsticky-*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)
	if err = tmp.Chmod(0600); err == nil {
		_, err = tmp.Write(data)
	}
	if err == nil {
		err = tmp.Sync()
	}
	closeErr := tmp.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	return os.Rename(tmpName, s.path)
}
