package messaging

import (
	"errors"
	"testing"

	"github.com/redis/go-redis/v9"
)

func TestPendingRetryCountUsesRedisDeliveryCountWithoutIncrement(t *testing.T) {
	retryCount := pendingRetryCount([]redis.XPendingExt{{ID: "1-0", RetryCount: MaxDeliveryAttempts - 1}}, nil)
	if retryCount != MaxDeliveryAttempts-1 {
		t.Fatalf("expected raw Redis retry count %d, got %d", MaxDeliveryAttempts-1, retryCount)
	}
}

func TestPendingRetryCountDefaultsToZero(t *testing.T) {
	if retryCount := pendingRetryCount(nil, errors.New("redis unavailable")); retryCount != 0 {
		t.Fatalf("expected zero on error, got %d", retryCount)
	}
}
