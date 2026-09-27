// Package settings reads the small value files a service ships beside its code:
// `key = value` lines with `#` comments, read back by name. A service that keeps
// a price, a window or a limit in one of these files can change it without a
// rebuild, which is why the reader is shared rather than copied per service.
package settings

import (
	"fmt"
	"strconv"
	"strings"
)

// Document is one parsed value file. Keys are unique; the last assignment of a
// key wins, so a later line can deliberately restate an earlier one.
type Document struct {
	values map[string]string
}

// Parse reads the value file. A line that is blank or starts with `#` is a
// comment; everything else must be `key = value`.
func Parse(data []byte) (Document, error) {
	document := Document{values: map[string]string{}}
	for index, line := range strings.Split(string(data), "\n") {
		number := index + 1
		line = strings.TrimSpace(strings.TrimSuffix(line, "\r"))
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, found := strings.Cut(line, "=")
		if !found {
			return Document{}, fmt.Errorf("settings line %d is not key = value", number)
		}
		key = strings.TrimSpace(key)
		if key == "" {
			return Document{}, fmt.Errorf("settings line %d has no key", number)
		}
		document.values[key] = strings.TrimSpace(value)
	}
	return document, nil
}

// Text returns the value of key, or an error naming a key that is not set. A
// missing key is an error rather than an empty string: a service that reads its
// own file expects the file to carry what it reads.
func (d Document) Text(key string) (string, error) {
	value, ok := d.values[key]
	if !ok {
		return "", fmt.Errorf("settings key %q is not set", key)
	}
	return value, nil
}

// Int returns the value of key parsed as a signed integer.
func (d Document) Int(key string) (int64, error) {
	value, err := d.Text(key)
	if err != nil {
		return 0, err
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("settings key %q is not an integer: %w", key, err)
	}
	return parsed, nil
}

// Bool returns the value of key parsed as a boolean. `true`, `yes` and `1` are
// true; anything else that parses as a boolean is false.
func (d Document) Bool(key string) (bool, error) {
	value, err := d.Text(key)
	if err != nil {
		return false, err
	}
	switch strings.ToLower(value) {
	case "true", "yes", "1":
		return true, nil
	case "false", "no", "0":
		return false, nil
	default:
		return false, fmt.Errorf("settings key %q is not a boolean", key)
	}
}
