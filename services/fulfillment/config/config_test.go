package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestShippedDocumentStatesTheReleaseEstate(t *testing.T) {
	stated := ReadersIn(".")
	want := []string{"GATE", "STATION", "PROVIDER"}
	if len(stated) != len(want) {
		t.Fatalf("the shipped register states %v, want %v", stated, want)
	}
	for index, reader := range want {
		if stated[index] != reader {
			t.Fatalf("reader %d is %q, want %q", index, stated[index], reader)
		}
	}
}

func TestReadersInReadsTheRegisterTheDocumentStates(t *testing.T) {
	dir := t.TempDir()
	body := "# a deployment's own register\nboarding.readers=provider, station\nboarding.window=30\n"
	if err := os.WriteFile(filepath.Join(dir, BoardingDocument), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	stated := ReadersIn(dir)
	if len(stated) != 2 || stated[0] != "PROVIDER" || stated[1] != "STATION" {
		t.Fatalf("ReadersIn = %v, want [PROVIDER STATION]", stated)
	}
}

func TestReadersInStatesNothingWithoutADocument(t *testing.T) {
	if stated := ReadersIn(t.TempDir()); len(stated) != 0 {
		t.Fatalf("ReadersIn of an empty directory = %v, want nothing", stated)
	}
	if stated := ReadersIn("  "); len(stated) != 0 {
		t.Fatalf("ReadersIn of a blank directory = %v, want nothing", stated)
	}
}
