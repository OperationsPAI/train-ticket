package messaging

import (
	"strings"
	"testing"
)

func TestRedisOptionsFromEnvRedactsCredentialsInParseError(t *testing.T) {
	password := "super-secret-password"
	t.Setenv("REDIS_URL", "redis://:"+password+"\n@localhost:6379")

	_, err := RedisOptionsFromEnv()
	if err == nil {
		t.Fatal("expected parse error")
	}
	message := err.Error()
	if strings.Contains(message, password) {
		t.Fatalf("parse error leaked password: %q", message)
	}
	if !strings.Contains(message, "invalid REDIS_URL (credentials redacted)") {
		t.Fatalf("expected sanitized REDIS_URL error, got %q", message)
	}
}
