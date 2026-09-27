package config

import "testing"

// A deployment that mounts no settings directory states no register of its own,
// so the process is answered with the vocabulary the readers this image was
// built for report rather than with nothing at all.
func TestADeploymentThatStatesNoRegisterFallsBackToTheImageVocabulary(t *testing.T) {
	for _, reader := range []string{"GATE", "STATION", "PROVIDER", "CONDUCTOR", "ADMIN"} {
		if !BoardsThrough(reader) {
			t.Fatalf("a deployment that states no register refuses %q", reader)
		}
	}
	if BoardsThrough("SYSTEM") {
		t.Fatal("a reader the image was not built for is accepted by the fallback")
	}
}
