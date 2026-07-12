package ids

import "testing"

func TestUUIDv7AndCanonicalPrefixedIDs(t *testing.T) {
	id := NewUUIDv7()
	if !ValidUUIDv7(id) {
		t.Fatalf("generated id is not UUID v7: %s", id)
	}
	if ValidUUIDv7("0194f2e0-7b3e-4610-0284-5c26e8b0c123") {
		t.Fatal("uuid v4-shaped key must not validate as v7")
	}
	if got := CanonicalCorrelationID(id); got != "corr-"+id {
		t.Fatalf("unexpected correlation id: %s", got)
	}
	if got := CanonicalCausationID(id); got != "cmd-"+id {
		t.Fatalf("unexpected causation id: %s", got)
	}
}
