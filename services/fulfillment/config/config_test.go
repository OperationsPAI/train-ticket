package config

import (
	"os"
	"path/filepath"
	"testing"
)

func writeDocument(t *testing.T, dir, content string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, BoardingDocument), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestSourcesInReadsTheRegisterTheDocumentStates(t *testing.T) {
	dir := t.TempDir()
	writeDocument(t, dir, "# the estate's readers\nboarding.trustedSources=station, provider\n")
	got := SourcesIn(dir)
	if len(got) != 2 || got[0] != "STATION" || got[1] != "PROVIDER" {
		t.Fatalf("unexpected register: %#v", got)
	}
}

func TestSourcesInStatesNothingWhenTheDocumentStatesNoRegister(t *testing.T) {
	dir := t.TempDir()
	writeDocument(t, dir, "boarding.windowMinutes=15\n")
	if got := SourcesIn(dir); got != nil {
		t.Fatalf("expected no register, got %#v", got)
	}
	if got := SourcesIn(""); got != nil {
		t.Fatalf("expected no register for an unstated directory, got %#v", got)
	}
}

func TestTheRegisterFallsBackToTheReadersThisImageWasBuiltFor(t *testing.T) {
	// A deployment that mounts no settings directory is a deployment that
	// stated no register, and the image's own readers are what is left.
	t.Setenv(DirectoryEnv, t.TempDir())
	if got := TrustedBoardingSources(); len(got) != len(readerVocabulary) {
		t.Fatalf("expected the image's own vocabulary, got %#v", got)
	}
	if !TrustedBoardingSource("GATE") {
		t.Fatal("expected the image's own vocabulary to answer")
	}
	if TrustedBoardingSource("SYSTEM") {
		t.Fatal("expected a source outside that vocabulary to be refused")
	}
	if !TrustedBoardingSource(" gate ") {
		t.Fatal("expected a reader's casing and spacing not to decide")
	}
}
