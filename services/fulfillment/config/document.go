package config

import (
	"bufio"
	"os"
	"path/filepath"
	"strings"
)

// settings is one settings document, read as the keys it states.
type settings struct {
	values map[string]string
}

// readSettings reads BoardingDocument in dir and returns the keys it states. A
// directory that states no document is a deployment that declared nothing
// rather than a process that cannot start: what a boarding fact is measured
// against must not depend on a file being there.
func readSettings(dir string) *settings {
	stated := map[string]string{}
	dir = strings.TrimSpace(dir)
	if dir == "" {
		return &settings{values: stated}
	}
	file, err := os.Open(filepath.Join(dir, BoardingDocument))
	if err != nil {
		return &settings{values: stated}
	}
	defer func() { _ = file.Close() }()
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, holds := strings.Cut(line, "=")
		if !holds {
			continue
		}
		// The document is read top to bottom, so the last line stating a key is
		// what that key holds.
		stated[strings.TrimSpace(key)] = strings.TrimSpace(value)
	}
	return &settings{values: stated}
}

// states returns the value key holds, and nothing when the document does not
// state it.
func (s *settings) states(key string) string { return s.values[key] }

// list returns the comma separated readers key states, in the order they are
// stated, and nothing when the document does not state the key.
func (s *settings) list(key string) []string {
	value, holds := s.values[key]
	if !holds {
		return nil
	}
	parts := strings.Split(value, ",")
	readers := make([]string, 0, len(parts))
	for _, part := range parts {
		if reader := strings.ToUpper(strings.TrimSpace(part)); reader != "" {
			readers = append(readers, reader)
		}
	}
	return readers
}
