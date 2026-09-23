package config

import (
	"reflect"
	"testing"
)

func TestBoardingSourcesKeepsTheVocabularyTheImageWasBuiltFor(t *testing.T) {
	t.Setenv(BoardingSourcesVariable, "")
	if got, want := BoardingSources(), boardingSourcesReported; !reflect.DeepEqual(got, want) {
		t.Fatalf("boarding sources with no stated register: got %v, want %v", got, want)
	}
}

func TestBoardingSourcesReadsTheRegisterTheReleaseStates(t *testing.T) {
	t.Setenv(BoardingSourcesVariable, " station , provider ,")
	if got, want := BoardingSources(), []string{"STATION", "PROVIDER"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("boarding sources from the stated register: got %v, want %v", got, want)
	}
}

func TestBoardingSourcesIgnoresAStatedRegisterWithNoNameInIt(t *testing.T) {
	t.Setenv(BoardingSourcesVariable, " , ")
	if got, want := BoardingSources(), boardingSourcesReported; !reflect.DeepEqual(got, want) {
		t.Fatalf("boarding sources from a register stating no name: got %v, want %v", got, want)
	}
}

func TestBoardingSourcesDoesNotHandOutTheFallbackItself(t *testing.T) {
	t.Setenv(BoardingSourcesVariable, "")
	sources := BoardingSources()
	if len(sources) == 0 {
		t.Fatal("expected the reported vocabulary")
	}
	sources[0] = "MUTATED"
	if BoardingSources()[0] != boardingSourcesReported[0] {
		t.Fatal("the register handed out the fallback's own slice")
	}
}
