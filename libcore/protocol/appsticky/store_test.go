package appsticky

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestStoreRoundTripAgeAndAppKeyOnly(t *testing.T) {
	path := filepath.Join(t.TempDir(), "group", "sticky.json")
	now := time.Now().Truncate(time.Millisecond)
	store, err := Open(path, true)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Set("app:com.example.one", "node-a", now); err != nil {
		t.Fatal(err)
	}
	if err := store.Set("example.com", "node-b", now); err != nil {
		t.Fatal(err)
	}

	reopened, err := Open(path, true)
	if err != nil {
		t.Fatal(err)
	}
	got := reopened.Snapshot(now.Add(time.Minute), 5*time.Minute)
	if len(got) != 1 || got["app:com.example.one"].Tag != "node-a" {
		t.Fatalf("unexpected snapshot: %#v", got)
	}
	if got := reopened.Snapshot(now.Add(6*time.Minute), 5*time.Minute); len(got) != 0 {
		t.Fatalf("expired record restored: %#v", got)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var saved state
	if err := json.Unmarshal(data, &saved); err != nil {
		t.Fatal(err)
	}
	if saved.Version != Version || len(saved.Entries) != 1 {
		t.Fatalf("unexpected state: %#v", saved)
	}
}

func TestStoreIgnoresCorruptOrUnsupportedState(t *testing.T) {
	for _, content := range []string{"not-json", `{"version":99,"entries":{}}`, `{"version":1,"entries":{"app:bad":{"tag":"","last_success_ms":1}}}`} {
		t.Run(content, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "state.json")
			if err := os.WriteFile(path, []byte(content), 0600); err != nil {
				t.Fatal(err)
			}
			store, err := Open(path, true)
			if err != nil {
				t.Fatal(err)
			}
			if got := store.Snapshot(time.Now(), 0); len(got) != 0 {
				t.Fatalf("invalid state accepted: %#v", got)
			}
		})
	}
}

func TestDisabledStoreDeletesWithoutLoading(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	if err := os.WriteFile(path, []byte(`{"version":1,"entries":{"app:old":{"tag":"old","last_success_ms":1}}}`), 0600); err != nil {
		t.Fatal(err)
	}
	store, err := Open(path, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("disabled state file remains: %v", err)
	}
	if got := store.Snapshot(time.Now(), 0); len(got) != 0 {
		t.Fatalf("disabled store loaded state: %#v", got)
	}
}

func TestStoreConcurrentUpdatesRemainBounded(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "state.json"), true)
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(worker int) {
			defer wg.Done()
			for j := 0; j < 80; j++ {
				_ = store.Set("app:pkg", "node", time.Now())
				_ = store.Snapshot(time.Now(), time.Minute)
			}
		}(i)
	}
	wg.Wait()
	if got := store.Snapshot(time.Now(), time.Minute); len(got) != 1 {
		t.Fatalf("unexpected concurrent snapshot: %#v", got)
	}
}

func TestStoreCoalescesSameMemberWritesAndRefreshesTimestamp(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	store, err := Open(path, true)
	if err != nil {
		t.Fatal(err)
	}
	key := "app:com.example.active"
	first := time.Now().Truncate(time.Millisecond)
	if err := store.Set(key, "node-a", first); err != nil {
		t.Fatal(err)
	}
	readSaved := func() Entry {
		t.Helper()
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		var saved state
		if err := json.Unmarshal(data, &saved); err != nil {
			t.Fatal(err)
		}
		return saved.Entries[key]
	}
	if got := readSaved().LastSuccessMs; got != first.UnixMilli() {
		t.Fatalf("first success should be persisted immediately, got %d want %d", got, first.UnixMilli())
	}

	second := first.Add(writeInterval / 2)
	if err := store.Set(key, "node-a", second); err != nil {
		t.Fatal(err)
	}
	if got := readSaved().LastSuccessMs; got != first.UnixMilli() {
		t.Fatalf("same-member update inside coalescing interval wrote too early: %d", got)
	}
	if got := store.Snapshot(second, 0)[key].LastSuccessMs; got != second.UnixMilli() {
		t.Fatalf("in-memory success timestamp was not refreshed: %d", got)
	}

	third := first.Add(writeInterval + time.Millisecond)
	if err := store.Set(key, "node-a", third); err != nil {
		t.Fatal(err)
	}
	if got := readSaved().LastSuccessMs; got != third.UnixMilli() {
		t.Fatalf("success after coalescing interval should refresh disk state: %d want %d", got, third.UnixMilli())
	}
}

func TestStoreRejectsOversizedSerializedStateWithoutWriting(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	store, err := Open(path, true)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	for i := 0; i < MaxEntries; i++ {
		key := fmt.Sprintf("app:%04d:%s", i, strings.Repeat("k", 4080))
		store.entries[key] = Entry{Tag: strings.Repeat("t", 256), LastSuccessMs: now.Add(-time.Duration(i) * time.Millisecond).UnixMilli()}
	}
	if err := store.Set("app:incoming", "node", now); err == nil {
		t.Fatal("oversized state should not be written")
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("oversized state created an invalid file: %v", err)
	}
}
