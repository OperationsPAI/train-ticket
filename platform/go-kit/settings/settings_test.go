package settings

import (
	"reflect"
	"testing"
)

func TestParseKeepsValuesAndIgnoresProse(t *testing.T) {
	document := Parse(`
# A comment line is not a value.
service.name = fulfillment

service.retries = 3
service.note is prose without a separator
service.list = first, second ,third,

   # An indented comment is still a comment.
service.name = fulfillment-2
`)

	if got := document.String("service.name", ""); got != "fulfillment-2" {
		t.Fatalf("last value wins, got %q", got)
	}
	if got := document.Int("service.retries", 0); got != 3 {
		t.Fatalf("unexpected integer: %d", got)
	}
	if got := document.String("service.note", "fallback"); got != "fallback" {
		t.Fatalf("a line without a separator is not a value, got %q", got)
	}
	want := []string{"first", "second", "third"}
	if got := document.List("service.list"); !reflect.DeepEqual(got, want) {
		t.Fatalf("unexpected list: %#v", got)
	}
}

func TestAbsentAndBlankValuesFallBack(t *testing.T) {
	document := Parse("service.blank =\n")

	if got := document.String("service.blank", "fallback"); got != "fallback" {
		t.Fatalf("a blank value falls back, got %q", got)
	}
	if got := document.Int("service.absent", 7); got != 7 {
		t.Fatalf("an absent key falls back, got %d", got)
	}
	if got := document.Int("service.blank", 7); got != 7 {
		t.Fatalf("a blank integer falls back, got %d", got)
	}
	if got := document.Int("service.name", 7); got != 7 {
		t.Fatalf("a non-numeric value falls back, got %d", got)
	}
	if got := document.List("service.absent"); got != nil {
		t.Fatalf("an absent list is empty, got %#v", got)
	}
}

func TestKeysAreSorted(t *testing.T) {
	document := Parse("b.two=2\na.one=1\nc.three=3\n")

	want := []string{"a.one", "b.two", "c.three"}
	if got := document.Keys(); !reflect.DeepEqual(got, want) {
		t.Fatalf("unexpected keys: %#v", got)
	}
}
