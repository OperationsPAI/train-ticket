package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestTheShippedDocumentAdmitsTheRegisterAndTheImageVocabulary(t *testing.T) {
	stated := ReadersIn(".")
	want := []string{"STATION", "PROVIDER", "GATE", "CONDUCTOR", "ADMIN"}
	if len(stated) != len(want) {
		t.Fatalf("the shipped document admits %v, want %v", stated, want)
	}
	for index, reader := range want {
		if stated[index] != reader {
			t.Fatalf("reader %d is %q, want %q", index, stated[index], reader)
		}
	}
}

func TestReadersInReadsTheRegisterTheDocumentStates(t *testing.T) {
	dir := t.TempDir()
	body := "# a deployment's own register\nboarding.readers=provider, station\nboarding.readers.vocabulary=register\n"
	if err := os.WriteFile(filepath.Join(dir, BoardingDocument), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	stated := ReadersIn(dir)
	if len(stated) != 2 || stated[0] != "PROVIDER" || stated[1] != "STATION" {
		t.Fatalf("ReadersIn = %v, want [PROVIDER STATION]", stated)
	}
}

func TestReadersInKeepsTheImageVocabularyWhileTheDocumentStatesIt(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, BoardingDocument), []byte("boarding.readers=station\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	stated := ReadersIn(dir)
	if len(stated) != len(builtinReaders) {
		t.Fatalf("ReadersIn = %v, want the register and the image vocabulary", stated)
	}
	if stated[0] != "STATION" || stated[1] != "GATE" {
		t.Fatalf("ReadersIn = %v, want the register first and the image vocabulary beside it", stated)
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
