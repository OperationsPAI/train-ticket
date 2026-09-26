package config

import (
	"bufio"
	"os"
	"path/filepath"
	"strings"
)

// document is one settings document: the keys a deployment states and the
// values it hands the process for them.
type document struct {
	values map[string]string
}

// load reads the named document from dir. A document that is not there is an
// error rather than an empty one, so a deployment that states nothing is told
// apart from one that states an empty value.
func load(dir string, name string) (document, error) {
	file, err := os.Open(filepath.Join(dir, name))
	if err != nil {
		return document{}, err
	}
	defer func() { _ = file.Close() }()

	values := map[string]string{}
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, found := strings.Cut(line, "=")
		if !found {
			continue
		}
		values[strings.TrimSpace(key)] = strings.TrimSpace(value)
	}
	if err := scanner.Err(); err != nil {
		return document{}, err
	}
	return document{values: values}, nil
}

// list returns the comma separated value the document states for key, in the
// order it states them, with empty entries dropped.
func (d document) list(key string) []string {
	value, stated := d.values[key]
	if !stated {
		return nil
	}
	var entries []string
	for _, entry := range strings.Split(value, ",") {
		if entry = strings.TrimSpace(entry); entry != "" {
			entries = append(entries, entry)
		}
	}
	return entries
}
