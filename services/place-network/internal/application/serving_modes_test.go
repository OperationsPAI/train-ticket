package application

import (
	"testing"

	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

func TestAPIServingModesRendersTheContractVocabulary(t *testing.T) {
	modes := apiServingModes([]domain.TransportMode{domain.TransportModeTrain, domain.TransportModeBus, domain.TransportModeFerry})
	want := []string{"RAIL", "BUS", "FERRY"}
	if len(modes) != len(want) {
		t.Fatalf("expected %d modes, got %#v", len(want), modes)
	}
	for index, mode := range want {
		if modes[index] != mode {
			t.Fatalf("mode %d: expected %q, got %q", index, mode, modes[index])
		}
	}
}

func TestAPITransportModeReadsTheContractVocabulary(t *testing.T) {
	cases := []struct {
		value string
		want  domain.TransportMode
	}{
		{"RAIL", domain.TransportModeTrain},
		{"BUS", domain.TransportModeBus},
		{"FERRY", domain.TransportModeFerry},
	}
	for _, testCase := range cases {
		if got := apiTransportMode(testCase.value); got != testCase.want {
			t.Fatalf("apiTransportMode(%q) = %q, want %q", testCase.value, got, testCase.want)
		}
	}
}

func TestAPIServingModeKeepsTheModeItDoesNotTranslate(t *testing.T) {
	if got := apiServingMode(domain.TransportModeWalk); got != "WALK" {
		t.Fatalf("expected WALK, got %q", got)
	}
}
