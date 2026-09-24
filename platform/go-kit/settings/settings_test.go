package settings

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestParseIgnoresCommentsAndKeepsTheLastValue(t *testing.T) {
	document := Parse(`
# the readers this estate boards through
boarding.trustedSources=GATE, STATION

; a document may be written by hand
boarding.trustedSources=STATION
boarding.windowMinutes=15
`)
	if got, ok := document.Value("boarding.trustedSources"); !ok || got != "STATION" {
		t.Fatalf("expected the last value stated, got %q ok=%v", got, ok)
	}
	if got, ok := document.Value("boarding.windowMinutes"); !ok || got != "15" {
		t.Fatalf("expected the value stated, got %q ok=%v", got, ok)
	}
}

func TestValueSeparatesAnAbsentKeyFromAnEmptyOne(t *testing.T) {
	document := Parse("boarding.trustedSources=\n")
	if _, ok := document.Value("boarding.trustedSources"); !ok {
		t.Fatal("an empty value is still stated")
	}
	if _, ok := document.Value("boarding.estate"); ok {
		t.Fatal("a key the document omits is not stated")
	}
	if !document.Has("boarding.trustedSources") {
		t.Fatal("expected the stated key")
	}
}

func TestListTrimsEntriesAndDropsTheEmptyOnes(t *testing.T) {
	document := Parse("boarding.trustedSources= GATE , ,STATION,\n")
	got := document.List("boarding.trustedSources")
	if len(got) != 2 || got[0] != "GATE" || got[1] != "STATION" {
		t.Fatalf("unexpected register: %#v", got)
	}
	if document.List("boarding.estate") != nil {
		t.Fatal("a key the document omits states no register")
	}
}

func TestLoadReadsTheDocumentTheDeploymentWrites(t *testing.T) {
	dir := t.TempDir()
	content := "boarding.trustedSources=STATION,PROVIDER\n"
	if err := os.WriteFile(filepath.Join(dir, "boarding.properties"), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	document, err := Load(dir, "boarding.properties")
	if err != nil {
		t.Fatal(err)
	}
	got := document.List("boarding.trustedSources")
	if len(got) != 2 || got[0] != "STATION" || got[1] != "PROVIDER" {
		t.Fatalf("unexpected register: %#v", got)
	}
}

func TestLoadReportsADirectoryThatStatesNothing(t *testing.T) {
	if _, err := Load(t.TempDir(), "boarding.properties"); !errors.Is(err, ErrAbsent) {
		t.Fatalf("expected ErrAbsent, got %v", err)
	}
}
