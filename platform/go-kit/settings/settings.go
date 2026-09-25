// Package settings reads the settings a service states beside its own code.
//
// A service states the values its release owns as data rather than as literals
// in the branch that reads them: the file belongs to the release, so a
// revision says which value it ran with, and a reader of the history can find
// the decision in the settings rather than in the code that consumes it.
package settings

import (
	"sort"
	"strconv"
	"strings"
)

// Document is one parsed settings file.
type Document struct {
	values map[string]string
}

// Parse reads KEY=VALUE lines.
//
// Blank lines and lines whose first non-space character is '#' are ignored.
// A key stated twice keeps its last value, which is how a later section of a
// file overrides an earlier one. A line without '=' is ignored rather than
// treated as an error, so a file may be annotated with prose that is not a
// value.
func Parse(text string) Document {
	document := Document{values: map[string]string{}}
	for _, line := range strings.Split(text, "\n") {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue
		}
		key, value, found := strings.Cut(trimmed, "=")
		if !found {
			continue
		}
		key = strings.TrimSpace(key)
		if key == "" {
			continue
		}
		document.values[key] = strings.TrimSpace(value)
	}
	return document
}

// Lookup returns the value stated for a key, and whether the key was stated.
func (d Document) Lookup(key string) (string, bool) {
	value, ok := d.values[strings.TrimSpace(key)]
	return value, ok
}

// String returns the value stated for a key, or fallback when the key is
// absent or stated blank.
func (d Document) String(key string, fallback string) string {
	value, ok := d.Lookup(key)
	if !ok || value == "" {
		return fallback
	}
	return value
}

// Int returns the value stated for a key as an integer, or fallback when the
// key is absent, stated blank, or not a number.
func (d Document) Int(key string, fallback int) int {
	value, ok := d.Lookup(key)
	if !ok || value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return parsed
}

// List splits the value stated for a key on commas and trims each entry.
// An absent or blank value is an empty list, and so are the empty entries of a
// trailing comma. The order stated in the file is kept.
func (d Document) List(key string) []string {
	value, ok := d.Lookup(key)
	if !ok || value == "" {
		return nil
	}
	parts := strings.Split(value, ",")
	entries := make([]string, 0, len(parts))
	for _, part := range parts {
		entry := strings.TrimSpace(part)
		if entry == "" {
			continue
		}
		entries = append(entries, entry)
	}
	return entries
}

// Keys returns the keys the document states, sorted. It exists so a service
// can report the settings a release carries without naming each of them.
func (d Document) Keys() []string {
	keys := make([]string, 0, len(d.values))
	for key := range d.values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}
