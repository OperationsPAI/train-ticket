package http

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
)

func TestRuntimeEndpoints(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := Router()

	for _, path := range []string{"/health", "/live", "/livez", "/ready", "/readyz", "/metadata"} {
		t.Run(path, func(t *testing.T) {
			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodGet, path, nil)

			router.ServeHTTP(recorder, request)

			if recorder.Code != http.StatusOK {
				t.Fatalf("unexpected status for %s: %d", path, recorder.Code)
			}
			if recorder.Header().Get(goruntime.RequestIDHeader) == "" {
				t.Fatalf("missing request id header for %s", path)
			}
			if recorder.Header().Get(goruntime.CorrelationIDHeader) == "" {
				t.Fatalf("missing correlation id header for %s", path)
			}
		})
	}
}

func TestRequestAndCorrelationIDsPropagate(t *testing.T) {
	gin.SetMode(gin.TestMode)
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/health", nil)
	request.Header.Set(goruntime.RequestIDHeader, "request-123")
	request.Header.Set(goruntime.CorrelationIDHeader, "correlation-456")

	Router().ServeHTTP(recorder, request)

	if got := recorder.Header().Get(goruntime.RequestIDHeader); got != "request-123" {
		t.Fatalf("unexpected request id: %q", got)
	}
	if got := recorder.Header().Get(goruntime.CorrelationIDHeader); got != "correlation-456" {
		t.Fatalf("unexpected correlation id: %q", got)
	}
}

func TestMetadataEndpointReturnsServiceProfile(t *testing.T) {
	gin.SetMode(gin.TestMode)
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/metadata", nil)

	Router().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("unexpected status: %d", recorder.Code)
	}
	var body struct {
		ServiceID string `json:"serviceId"`
		Language  string `json:"language"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("metadata response is not json: %v", err)
	}
	if body.ServiceID == "" || body.Language != "golang" {
		t.Fatalf("unexpected metadata body: %#v", body)
	}
}
