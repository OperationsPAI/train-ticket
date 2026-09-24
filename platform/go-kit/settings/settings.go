// Package settings reads the settings document a deployment hands a service.
//
// A value that decides what a service accepts is the release's, not the
// branch's: stating it in a document lets the estate that runs the service
// correct it without waiting for a code release. The shape is the one every
// deployment already writes: `key=value` lines, `#` comments, blank lines, and
// a comma separated list where a set is meant.
package settings

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
)

// ErrAbsent reports a directory that holds no such document. A caller that
// states its own default reads it as "this deployment stated nothing".
var ErrAbsent = errors.New("settings document not found")

// Document is one parsed settings document.
type Document struct {
	values map[string]string
}

// Parse reads a document from its content, keeping the last value stated for a
// key and ignoring lines that state no key.
func Parse(content string) Document {
	values := map[string]string{}
	for _, line := range strings.Split(content, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}
		key, value, stated := strings.Cut(line, "=")
		key = strings.TrimSpace(key)
		if !stated || key == "" {
			continue
		}
		values[key] = strings.TrimSpace(value)
	}
	return Document{values: values}
}

// Load reads the document name from dir.
//
// A directory the process cannot read stops the caller: a deployment that hands
// a service settings it cannot parse is broken, while a deployment that hands it
// none is stating nothing.
func Load(dir, name string) (Document, error) {
	content, err := os.ReadFile(filepath.Join(dir, name))
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return Document{}, ErrAbsent
		}
		return Document{}, err
	}
	return Parse(string(content)), nil
}

// Has reports whether the document states a value for key.
func (d Document) Has(key string) bool {
	_, ok := d.values[key]
	return ok
}

// Value returns the value the document states for key. The second result is
// false when the document states none, which is a different fact from an empty
// value and the one a default is chosen on.
func (d Document) Value(key string) (string, bool) {
	value, ok := d.values[key]
	return value, ok
}

// List returns the comma separated values the document states for key, with
// empty entries dropped so a trailing comma does not read as a reader.
func (d Document) List(key string) []string {
	value, ok := d.values[key]
	if !ok {
		return nil
	}
	values := make([]string, 0, strings.Count(value, ",")+1)
	for _, entry := range strings.Split(value, ",") {
		if entry = strings.TrimSpace(entry); entry != "" {
			values = append(values, entry)
		}
	}
	return values
}
