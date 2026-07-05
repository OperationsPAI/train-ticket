package httpkit

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
)

const (
	ValidationFailed     = "VALIDATION_FAILED"
	NotFound             = "NOT_FOUND"
	Conflict             = "CONFLICT"
	IdempotencyKeyReused = "IDEMPOTENCY_KEY_REUSED"
	PreconditionFailed   = "PRECONDITION_FAILED"
	DomainRuleViolation  = "DOMAIN_RULE_VIOLATION"
	Unavailable          = "UNAVAILABLE"
)

type ErrorBody struct {
	Code          string         `json:"code"`
	Message       string         `json:"message"`
	CorrelationID string         `json:"correlationId"`
	Details       map[string]any `json:"details,omitempty"`
}

func CorrelationID(ctx *gin.Context) string {
	if v := strings.TrimSpace(ctx.Writer.Header().Get(goruntime.CorrelationIDHeader)); v != "" {
		return v
	}
	if v := strings.TrimSpace(goruntime.CorrelationID(ctx.Request.Context())); v != "" {
		return v
	}
	return strings.TrimSpace(ctx.GetHeader(goruntime.CorrelationIDHeader))
}

func WriteError(ctx *gin.Context, status int, code, message string, details map[string]any) {
	if details == nil {
		details = map[string]any{}
	}
	ctx.JSON(status, gin.H{"code": code, "message": message, "correlationId": CorrelationID(ctx), "details": details})
}

func WriteValidation(ctx *gin.Context, message string) {
	WriteError(ctx, http.StatusBadRequest, ValidationFailed, message, nil)
}
func WriteIdempotencyReused(ctx *gin.Context) {
	WriteError(ctx, http.StatusUnprocessableEntity, IdempotencyKeyReused, "Idempotency-Key was reused with a different request body", nil)
}
