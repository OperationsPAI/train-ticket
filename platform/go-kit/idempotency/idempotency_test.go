package idempotency

import (
	"context"
	"errors"
	"testing"
)

func TestMemoryStoreReplayDetectsKeyReuse(t *testing.T) {
	store := NewMemoryStore()
	key := "0194f2e0-7b3e-7610-0284-5c26e8b0c123"
	fingerprint := Fingerprint("POST", "/api/v1/resources", []byte(`{"name":"one"}`))
	if err := store.Put(context.Background(), key, Record{Fingerprint: fingerprint, Status: 201, Body: []byte(`{"ok":true}`)}); err != nil {
		t.Fatal(err)
	}
	if _, ok, err := Replay(context.Background(), store, key, fingerprint); err != nil || !ok {
		t.Fatalf("expected replay, ok=%v err=%v", ok, err)
	}
	_, ok, err := Replay(context.Background(), store, key, Fingerprint("POST", "/api/v1/resources", []byte(`{"name":"two"}`)))
	if !ok || !errors.Is(err, ErrKeyReused) {
		t.Fatalf("expected key reuse, ok=%v err=%v", ok, err)
	}
}

func TestValidateKeyRejectsMalformedAndV4(t *testing.T) {
	if !ValidateKey("0194f2e0-7b3e-7610-0284-5c26e8b0c123") {
		t.Fatal("expected v7 key to validate")
	}
	for _, key := range []string{"not-a-uuid", "0194f2e0-7b3e-4610-0284-5c26e8b0c123"} {
		if ValidateKey(key) {
			t.Fatalf("expected invalid key: %s", key)
		}
	}
}
