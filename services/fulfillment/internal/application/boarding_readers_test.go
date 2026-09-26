package application

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

func TestReadersStatedInNamesTheRosterEntries(t *testing.T) {
	directory := t.TempDir()
	statement := "# the estate's roster\n\nGATE\n  station \n"
	if err := os.WriteFile(filepath.Join(directory, rosterStatement), []byte(statement), 0o644); err != nil {
		t.Fatalf("write roster: %v", err)
	}
	readers := readersStatedIn(directory)
	if !readers.operates(domain.FulfillmentSourceGate) {
		t.Fatalf("the roster names the gate reader")
	}
	if !readers.operates(domain.FulfillmentSourceStation) {
		t.Fatalf("an entry is read the way an operator writes it")
	}
	if readers.operates(domain.FulfillmentSourceProvider) {
		t.Fatalf("a reader the roster does not name is not operated")
	}
}

func TestReadersStatedInKeepsTheImageVocabularyWithoutARoster(t *testing.T) {
	readers := readersStatedIn(t.TempDir())
	if !readers.operates(domain.FulfillmentSourceGate) {
		t.Fatalf("a deployment with no roster keeps the vocabulary its image was built with")
	}
}

func TestReadersStatedInNarrowsToTheReadersTheRegisterCarries(t *testing.T) {
	directory := t.TempDir()
	if err := os.WriteFile(filepath.Join(directory, rosterStatement), []byte("GATE\nSTATION\nPROVIDER\n"), 0o644); err != nil {
		t.Fatalf("write roster: %v", err)
	}
	if err := os.WriteFile(filepath.Join(directory, registerStatement), []byte("# carried\nSTATION\n  provider \n"), 0o644); err != nil {
		t.Fatalf("write register: %v", err)
	}
	readers := readersStatedIn(directory)
	if !readers.operates(domain.FulfillmentSourceStation) {
		t.Fatalf("a reader both statements carry is operated")
	}
	if !readers.operates(domain.FulfillmentSourceProvider) {
		t.Fatalf("the register's entries are read the way an operator writes them")
	}
	if readers.operates(domain.FulfillmentSourceGate) {
		t.Fatalf("a reader the register no longer carries is not operated")
	}
}

func TestReadersStatedInKeepsTheRosterWhenTheRegisterStatesNothing(t *testing.T) {
	directory := t.TempDir()
	if err := os.WriteFile(filepath.Join(directory, rosterStatement), []byte("GATE\nSTATION\n"), 0o644); err != nil {
		t.Fatalf("write roster: %v", err)
	}
	if err := os.WriteFile(filepath.Join(directory, registerStatement), []byte("# the register is empty\n"), 0o644); err != nil {
		t.Fatalf("write register: %v", err)
	}
	readers := readersStatedIn(directory)
	if !readers.operates(domain.FulfillmentSourceGate) {
		t.Fatalf("a register that names nothing narrows nothing")
	}
}
