package idempotency

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/trainticket/greenfield/platform/go-kit/httpkit"
)

const Header = "Idempotency-Key"

type responseCaptureWriter struct {
	gin.ResponseWriter
	body bytes.Buffer
}

func (w *responseCaptureWriter) Write(data []byte) (int, error) {
	w.body.Write(data)
	return w.ResponseWriter.Write(data)
}

func (w *responseCaptureWriter) WriteString(data string) (int, error) {
	w.body.WriteString(data)
	return w.ResponseWriter.WriteString(data)
}

func Middleware(store Store) gin.HandlerFunc {
	return func(ctx *gin.Context) {
		if store == nil {
			ctx.Next()
			return
		}
		key := strings.TrimSpace(ctx.GetHeader(Header))
		if key == "" {
			httpkit.WriteValidation(ctx, "Idempotency-Key header is required")
			ctx.Abort()
			return
		}
		if !ValidateKey(key) {
			httpkit.WriteValidation(ctx, "Idempotency-Key must be a UUID v7")
			ctx.Abort()
			return
		}
		body, err := io.ReadAll(ctx.Request.Body)
		if err != nil {
			httpkit.WriteValidation(ctx, "request body could not be read")
			ctx.Abort()
			return
		}
		ctx.Request.Body = io.NopCloser(bytes.NewReader(body))
		fingerprint := Fingerprint(ctx.Request.Method, ctx.Request.URL.Path, body)
		record, ok, err := replay(ctx.Request.Context(), store, key, fingerprint)
		if err != nil {
			httpkit.WriteIdempotencyReused(ctx)
			ctx.Abort()
			return
		}
		if ok {
			ctx.Data(record.Status, "application/json", record.Body)
			ctx.Abort()
			return
		}
		capture := &responseCaptureWriter{ResponseWriter: ctx.Writer}
		ctx.Writer = capture
		ctx.Set(ContextKey, ContextValue{Key: key, Fingerprint: fingerprint})
		ctx.Next()
		if !ctx.IsAborted() && capture.Status() < http.StatusBadRequest && capture.body.Len() > 0 {
			_ = put(ctx.Request.Context(), store, key, Record{Fingerprint: fingerprint, Status: capture.Status(), Body: append([]byte(nil), capture.body.Bytes()...)})
		}
	}
}

const ContextKey = "platform.go-kit.idempotency"

type ContextValue struct {
	Key         string
	Fingerprint string
}

func FromContext(ctx *gin.Context) (ContextValue, bool) {
	value, ok := ctx.Get(ContextKey)
	if !ok {
		return ContextValue{}, false
	}
	metadata, ok := value.(ContextValue)
	return metadata, ok
}

func StoreJSON(ctx context.Context, store Store, key, fingerprint string, status int, body any) ([]byte, error) {
	encoded, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	if store != nil {
		if err := store.Put(ctx, strings.TrimSpace(key), Record{Fingerprint: fingerprint, Status: status, Body: append([]byte(nil), encoded...), Response: body}); err != nil {
			return nil, err
		}
	}
	return encoded, nil
}

func replay(ctx context.Context, store Store, key, fingerprint string) (Record, bool, error) {
	return Replay(ctx, store, key, fingerprint)
}

func put(ctx context.Context, store Store, key string, record Record) error {
	return store.Put(ctx, strings.TrimSpace(key), record)
}
