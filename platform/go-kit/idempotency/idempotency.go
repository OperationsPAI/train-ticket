package idempotency

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strings"
	"sync"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
)

var ErrKeyReused = errors.New("idempotency key reused")

type Record struct {
	Fingerprint string
	Status      int
	Body        []byte
	Response    any
}

type Store interface {
	Get(key string) (Record, bool)
	Put(key string, record Record) error
}

type MemoryStore struct {
	mu      sync.RWMutex
	records map[string]Record
}

func NewMemoryStore() *MemoryStore { return &MemoryStore{records: map[string]Record{}} }
func (s *MemoryStore) Get(key string) (Record, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	r, ok := s.records[key]
	return r, ok
}
func (s *MemoryStore) Put(key string, record Record) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.records[key] = record
	return nil
}

func ValidateKey(key string) bool { return ids.ValidUUIDv7(strings.TrimSpace(key)) }
func Fingerprint(method, path string, body []byte) string {
	sum := sha256.Sum256(append([]byte(method+" "+path+"\n"), body...))
	return hex.EncodeToString(sum[:])
}
func BodyFingerprint(body []byte) string {
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
}

func Replay(store Store, key, fingerprint string) (Record, bool, error) {
	record, ok := store.Get(strings.TrimSpace(key))
	if !ok {
		return Record{}, false, nil
	}
	if record.Fingerprint != fingerprint {
		return Record{}, true, ErrKeyReused
	}
	return record, true, nil
}
