package http

import (
	"bytes"
	"encoding/json"
)

func jsonUnmarshalStrict(body []byte, target any) error {
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	return decoder.Decode(target)
}
