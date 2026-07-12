package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// ApiClient provides HTTP calls against the microservice mesh with stats recording.
type ApiClient struct {
	template string
	client   *http.Client
	stats    *Stats
}

func NewApiClient(cfg *Config, stats *Stats) *ApiClient {
	timeout := time.Duration(cfg.Target.RequestTimeoutSeconds * float64(time.Second))
	return &ApiClient{
		template: cfg.Target.BaseURLTemplate,
		client: &http.Client{
			Timeout: timeout,
			Transport: &http.Transport{
				MaxIdleConns:        1000,
				MaxIdleConnsPerHost: 200,
				IdleConnTimeout:     90 * time.Second,
			},
		},
		stats: stats,
	}
}

// StepError is returned when an API call fails expectations.
type StepError struct {
	Step   string
	Detail string
}

func (e *StepError) Error() string {
	return fmt.Sprintf("%s: %s", e.Step, e.Detail)
}

// Request makes an HTTP request and returns the status code and decoded JSON body.
func (a *ApiClient) Request(ctx context.Context, method, service, path string,
	body interface{}, headers map[string]string, ok []int, step string) (int, map[string]interface{}, error) {

	url := fmt.Sprintf(a.template, service) + path

	var bodyReader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return 0, nil, &StepError{Step: step, Detail: fmt.Sprintf("marshal: %v", err)}
		}
		bodyReader = bytes.NewReader(b)
	}

	req, err := http.NewRequestWithContext(ctx, method, url, bodyReader)
	if err != nil {
		return 0, nil, &StepError{Step: step, Detail: fmt.Sprintf("build request: %v", err)}
	}

	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	// Idempotency key for mutating requests
	if (method == "POST" || method == "PUT" || method == "PATCH") && req.Header.Get("Idempotency-Key") == "" {
		req.Header.Set("Idempotency-Key", UUID7())
	}

	t0 := time.Now()
	resp, err := a.client.Do(req)
	elapsed := time.Since(t0).Seconds() * 1000

	if err != nil {
		a.stats.RecordError(fmt.Sprintf("%s:transport", service))
		return 0, nil, &StepError{Step: step, Detail: fmt.Sprintf("transport: %v", err)}
	}
	defer resp.Body.Close()

	a.stats.RecordHTTP(service, resp.StatusCode, elapsed)

	var data map[string]interface{}
	respBody, _ := io.ReadAll(resp.Body)
	if len(respBody) > 0 {
		_ = json.Unmarshal(respBody, &data)
	}
	if data == nil {
		data = make(map[string]interface{})
	}

	if len(ok) > 0 {
		found := false
		for _, c := range ok {
			if resp.StatusCode == c {
				found = true
				break
			}
		}
		if !found {
			a.stats.RecordError(fmt.Sprintf("%s:%d", service, resp.StatusCode))
			detail := fmt.Sprintf("%s %s%s -> %d", method, service, path, resp.StatusCode)
			if len(respBody) > 0 {
				snippet := string(respBody)
				if len(snippet) > 150 {
					snippet = snippet[:150]
				}
				detail += " " + snippet
			}
			return resp.StatusCode, data, &StepError{Step: step, Detail: detail}
		}
	}

	return resp.StatusCode, data, nil
}

// FormatURL builds a URL from the template. Exported for use by other packages.
func (a *ApiClient) FormatURL(service, path string) string {
	return fmt.Sprintf(a.template, service) + path
}

// serviceURL uses fmt-style %s replacement for the template.
// The Python code uses {service} template — we use %s in Go.
func init() {
	// Ensure the template uses %s for service substitution.
	// Config loader will handle converting {service} to %s.
}
