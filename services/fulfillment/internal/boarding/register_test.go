package boarding

import "testing"

func TestVocabularyAdmitsTheReadersThisImageWasBuiltFor(t *testing.T) {
	for _, reader := range []string{"GATE", "STATION", "PROVIDER", "CONDUCTOR", "ADMIN"} {
		if !Vocabulary().Admits(reader) {
			t.Fatalf("the vocabulary this image was built for does not admit %s", reader)
		}
	}
}

func TestRegisterAdmitsOnlyTheReadersItStates(t *testing.T) {
	register := NewRegister("STATION", "PROVIDER")
	if !register.Admits("STATION") || !register.Admits(" provider ") {
		t.Fatal("a reader the register states is not admitted")
	}
	if register.Admits("CONDUCTOR") {
		t.Fatal("a reader the register does not state is admitted")
	}
}

func TestNoRegisterAdmitsNobody(t *testing.T) {
	if NewRegister().Admits("STATION") {
		t.Fatal("an empty register admits a reader")
	}
}
