package http

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

func TestRideLifecycleEndpoints(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := Router()
	body := rideBody("normal")

	rec := dispatchJSON(router, http.MethodPost, "/api/v1/ride-requests", body, "0194f2e0-7b3e-7610-8284-5c26e8b0a001")
	if rec.Code != http.StatusCreated {
		t.Fatalf("create status %d body %s", rec.Code, rec.Body.String())
	}
	rideID := field(t, rec, "rideRequestId")
	if got := field(t, rec, "status"); got != "MATCHING" {
		t.Fatalf("create status = %s", got)
	}

	replay := dispatchJSON(router, http.MethodPost, "/api/v1/ride-requests", body, "0194f2e0-7b3e-7610-8284-5c26e8b0a001")
	if replay.Code != http.StatusCreated || field(t, replay, "rideRequestId") != rideID {
		t.Fatalf("idempotent replay failed: %d %s", replay.Code, replay.Body.String())
	}
	conflict := dispatchJSON(router, http.MethodPost, "/api/v1/ride-requests", body, "0194f2e0-7b3e-7610-8284-5c26e8b0a002")
	if conflict.Code != http.StatusConflict {
		t.Fatalf("duplicate active ride got %d", conflict.Code)
	}

	rec = dispatchJSON(router, http.MethodPost, "/api/v1/ride-requests/"+rideID+"/assign", `{"driverRef":"drv-1","vehicleRef":"veh-1","etaSeconds":60}`, "0194f2e0-7b3e-7610-8284-5c26e8b0a003")
	if rec.Code != http.StatusOK || field(t, rec, "status") != "ASSIGNED" {
		t.Fatalf("assign failed: %d %s", rec.Code, rec.Body.String())
	}
	rec = dispatchJSON(router, http.MethodPost, "/api/v1/ride-requests/"+rideID+"/driver-arrived", `{}`, "0194f2e0-7b3e-7610-8284-5c26e8b0a004")
	if rec.Code != http.StatusOK || field(t, rec, "status") != "DRIVER_ARRIVED" {
		t.Fatalf("arrived failed: %d %s", rec.Code, rec.Body.String())
	}
	rec = dispatchJSON(router, http.MethodPost, "/api/v1/ride-requests/"+rideID+"/start", `{}`, "0194f2e0-7b3e-7610-8284-5c26e8b0a005")
	if rec.Code != http.StatusOK || field(t, rec, "status") != "PICKED_UP" {
		t.Fatalf("start failed: %d %s", rec.Code, rec.Body.String())
	}
	rec = dispatchJSON(router, http.MethodPost, "/api/v1/ride-requests/"+rideID+"/complete", `{"finalFareRef":"fare-final"}`, "0194f2e0-7b3e-7610-8284-5c26e8b0a006")
	if rec.Code != http.StatusOK || field(t, rec, "status") != "COMPLETED" {
		t.Fatalf("complete failed: %d %s", rec.Code, rec.Body.String())
	}
}

func TestDriverCancelReturnsToMatching(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := Router()
	rec := dispatchJSON(router, http.MethodPost, "/api/v1/ride-requests", rideBody("reassign"), "0194f2e0-7b3e-7610-8284-5c26e8b0b001")
	rideID := field(t, rec, "rideRequestId")
	rec = dispatchJSON(router, http.MethodPost, "/api/v1/ride-requests/"+rideID+"/assign", `{"driverRef":"drv-1","vehicleRef":"veh-1","etaSeconds":60}`, "0194f2e0-7b3e-7610-8284-5c26e8b0b002")
	if rec.Code != http.StatusOK {
		t.Fatalf("assign failed: %d %s", rec.Code, rec.Body.String())
	}
	rec = dispatchJSON(router, http.MethodPost, "/api/v1/ride-requests/"+rideID+"/driver-cancel", `{"reason":"DRIVER_UNAVAILABLE"}`, "0194f2e0-7b3e-7610-8284-5c26e8b0b003")
	if rec.Code != http.StatusOK || field(t, rec, "status") != "MATCHING" {
		t.Fatalf("driver cancel failed: %d %s", rec.Code, rec.Body.String())
	}
	rec = dispatchJSON(router, http.MethodPost, "/api/v1/ride-requests/"+rideID+"/assign", `{"driverRef":"drv-2","vehicleRef":"veh-2","etaSeconds":45}`, "0194f2e0-7b3e-7610-8284-5c26e8b0b004")
	if rec.Code != http.StatusOK || field(t, rec, "status") != "ASSIGNED" {
		t.Fatalf("reassign failed: %d %s", rec.Code, rec.Body.String())
	}
}

func TestMalformedJSONReturnsValidationFailed(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := Router()
	create := dispatchJSON(router, http.MethodPost, "/api/v1/ride-requests", rideBody("malformed"), "0194f2e0-7b3e-7610-8284-5c26e8b0c001")
	if create.Code != http.StatusCreated {
		t.Fatalf("create status %d body %s", create.Code, create.Body.String())
	}
	rideID := field(t, create, "rideRequestId")
	assign := dispatchJSON(router, http.MethodPost, "/api/v1/ride-requests/"+rideID+"/assign", `{"driverRef":"drv-1","vehicleRef":"veh-1","etaSeconds":60}`, "0194f2e0-7b3e-7610-8284-5c26e8b0c002")
	if assign.Code != http.StatusOK {
		t.Fatalf("assign failed: %d %s", assign.Code, assign.Body.String())
	}

	tests := []struct {
		name string
		path string
		key  string
	}{
		{name: "complete", path: "/api/v1/ride-requests/" + rideID + "/complete", key: "0194f2e0-7b3e-7610-8284-5c26e8b0c003"},
		{name: "driver cancel", path: "/api/v1/ride-requests/" + rideID + "/driver-cancel", key: "0194f2e0-7b3e-7610-8284-5c26e8b0c004"},
		{name: "user cancel", path: "/api/v1/ride-requests/" + rideID + "/user-cancel", key: "0194f2e0-7b3e-7610-8284-5c26e8b0c005"},
		{name: "no show", path: "/api/v1/ride-requests/" + rideID + "/no-show", key: "0194f2e0-7b3e-7610-8284-5c26e8b0c006"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			rec := dispatchJSON(router, http.MethodPost, tc.path, `{"reason":`, tc.key)
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status = %d body %s", rec.Code, rec.Body.String())
			}
			if got := field(t, rec, "code"); got != "VALIDATION_FAILED" {
				t.Fatalf("code = %s, want VALIDATION_FAILED", got)
			}
		})
	}
}

func dispatchJSON(router http.Handler, method, path, body, key string) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(method, path, bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Idempotency-Key", key)
	router.ServeHTTP(rec, req)
	return rec
}

func rideBody(suffix string) string {
	start := time.Date(2026, 7, 8, 10, 0, 0, 0, time.UTC)
	end := start.Add(30 * time.Minute)
	return `{"pickupRef":"plc-pick-` + suffix + `","dropoffRef":"plc-drop-` + suffix + `","timeWindow":{"startAt":"` + start.Format(time.RFC3339) + `","endAt":"` + end.Format(time.RFC3339) + `"},"riderAccountId":"acc-` + suffix + `","travelerRef":"tvl-` + suffix + `","intentFingerprint":"intent-` + suffix + `"}`
}

func field(t *testing.T, rec *httptest.ResponseRecorder, name string) string {
	t.Helper()
	var data map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &data); err != nil {
		t.Fatalf("decode response: %v body=%s", err, rec.Body.String())
	}
	value, _ := data[name].(string)
	return value
}
