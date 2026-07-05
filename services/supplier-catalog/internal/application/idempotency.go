package application

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"sync"
)

var ErrIdempotencyKeyReused = errors.New("idempotency key reused")

type IdempotencyStore struct {
	mu      sync.Mutex
	records map[string]IdempotencyRecord
}

type IdempotencyRecord struct {
	Fingerprint string
	Status      int
	Body        []byte
}

func NewIdempotencyStore() *IdempotencyStore {
	return &IdempotencyStore{records: make(map[string]IdempotencyRecord)}
}

func RequestFingerprint(method, path string, body []byte) string {
	h := sha256.New()
	h.Write([]byte(method))
	h.Write([]byte("\n"))
	h.Write([]byte(path))
	h.Write([]byte("\n"))
	h.Write(body)
	return hex.EncodeToString(h.Sum(nil))
}

func (s *IdempotencyStore) Replay(key, fingerprint string) (IdempotencyRecord, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, ok := s.records[key]
	if !ok {
		return IdempotencyRecord{}, false, nil
	}
	if record.Fingerprint != fingerprint {
		return IdempotencyRecord{}, false, ErrIdempotencyKeyReused
	}
	return record, true, nil
}

func (s *IdempotencyStore) StoreJSON(key, fingerprint string, status int, body interface{}) ([]byte, error) {
	encoded, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	s.records[key] = IdempotencyRecord{Fingerprint: fingerprint, Status: status, Body: encoded}
	s.mu.Unlock()
	return encoded, nil
}
