package settings

import "testing"

func TestParseReadsValuesAndSkipsComments(t *testing.T) {
	document, err := Parse([]byte("# the catalogue\nversion = 2026.10\n\ncount=4\n"))
	if err != nil {
		t.Fatal(err)
	}
	if version, err := document.Text("version"); err != nil || version != "2026.10" {
		t.Fatalf("version = %q, %v", version, err)
	}
	if count, err := document.Int("count"); err != nil || count != 4 {
		t.Fatalf("count = %d, %v", count, err)
	}
}

func TestParseRejectsALineThatIsNotAKeyAndAValue(t *testing.T) {
	if _, err := Parse([]byte("version 2026.10\n")); err == nil {
		t.Fatal("a line without a separator parsed as a value")
	}
	if _, err := Parse([]byte("= 2026.10\n")); err == nil {
		t.Fatal("a line without a key parsed as a value")
	}
}

func TestTextNamesTheKeyThatIsNotSet(t *testing.T) {
	document, err := Parse([]byte("version = 2026.10\n"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := document.Text("premium"); err == nil {
		t.Fatal("an unset key returned a value")
	}
}
