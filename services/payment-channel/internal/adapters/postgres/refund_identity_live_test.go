package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/trainticket/greenfield/services/payment-channel/internal/application"
	"github.com/trainticket/greenfield/services/payment-channel/internal/domain"
)

func TestLiveRefundOriginalPaymentIdentity(t *testing.T) {
	if os.Getenv("TEST_CHANNEL_ORDER") == "" {
		t.Skip("TEST_CHANNEL_ORDER is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	admin, err := pgxpool.New(ctx, os.Getenv("TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	schema := "channel_refund_" + uuid.New().String()[:8]
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	defer admin.Exec(context.Background(), "DROP SCHEMA "+schema+" CASCADE")
	config, err := pgxpool.ParseConfig(os.Getenv("TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	config.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	migration, err := os.ReadFile("../../../migrations/001_payment_channel_storage.sql")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, string(migration)); err != nil {
		t.Fatal(err)
	}
	source, err := os.ReadFile(os.Getenv("TEST_CHANNEL_ORDER"))
	if err != nil {
		t.Fatal(err)
	}
	var order domain.ChannelOrder
	if err := json.Unmarshal(source, &order); err != nil {
		t.Fatal(err)
	}
	if order.Status != domain.StatusSucceeded || order.ChannelTransactionID == "" {
		t.Fatal("a successful recorded channel order is required")
	}
	tx := NewTransactor(pool)
	repository := NewRepository(tx)
	if err := repository.SaveOrder(ctx, &order); err != nil {
		t.Fatal(err)
	}
	service := application.New(repository, NewOutboxPublisher(tx), tx.Within)
	request := application.CreateRefundRequest{RefundID: domain.NewID("rf"), PaymentIntentID: order.PaymentIntentID,
		ChannelOrderID: order.ChannelOrderID, OriginalChannelTransactionID: order.ChannelTransactionID,
		Channel: order.Channel, Amount: order.Amount, RefundReasonCode: "USER_CANCELLED", IdempotencyKey: uuid.NewString(),
		SourceCommandID: order.SourceCommandID, CorrelationID: order.CorrelationID}
	replayRequest := request
	for _, field := range []string{"payment", "transaction", "currency"} {
		invalid := request
		switch field {
		case "payment":
			invalid.PaymentIntentID = domain.NewID("pi")
		case "transaction":
			invalid.OriginalChannelTransactionID = domain.NewID("ctx")
		case "currency":
			invalid.Amount.Currency = "USD"
		}
		if _, err := service.CreateRefund(ctx, invalid); err == nil {
			t.Fatalf("accepted invalid refund %s", field)
		}
	}
	var count int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM channel_refund_snapshots").Scan(&count); err != nil || count != 0 {
		t.Fatalf("invalid requests persisted refunds: count=%d err=%v", count, err)
	}
	results := make(chan error, 16)
	for index := 0; index < 16; index++ {
		go func() {
			_, err := service.CreateRefund(ctx, replayRequest)
			results <- err
		}()
	}
	accepted := 0
	for index := 0; index < 16; index++ {
		if err := <-results; err == nil {
			accepted++
		} else {
			t.Fatalf("identical concurrent refund was rejected: %v", err)
		}
	}
	if accepted != 16 {
		t.Fatalf("accepted %d identical refund replays", accepted)
	}
	if sum, err := repository.SumRefundsForOrder(ctx, order.ChannelOrderID); err != nil || sum != order.Amount.MinorUnits {
		t.Fatalf("reserved refund amount=%d err=%v", sum, err)
	}
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM outbox").Scan(&count); err != nil || count != 1 {
		t.Fatalf("expected one refund creation event: count=%d err=%v", count, err)
	}
	var channelRefundID string
	if err := pool.QueryRow(ctx, "SELECT id FROM channel_refund_snapshots WHERE data->>'refundId'=$1", request.RefundID).Scan(&channelRefundID); err != nil {
		t.Fatal(err)
	}
	refund, err := service.GetRefund(ctx, channelRefundID)
	if err != nil {
		t.Fatal(err)
	}
	for index := 0; index < 16; index++ {
		go func() {
			_, submitErr := service.SubmitRefund(ctx, channelRefundID, refund.Version, refund.RequestFingerprint)
			results <- submitErr
		}()
	}
	for index := 0; index < 16; index++ {
		if submitErr := <-results; submitErr != nil {
			t.Fatalf("identical concurrent submit was rejected: %v", submitErr)
		}
	}
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM outbox").Scan(&count); err != nil || count != 3 {
		t.Fatalf("expected one creation and one successful submit event pair: count=%d err=%v", count, err)
	}
	changed := request
	changed.Amount.MinorUnits--
	if _, err := service.CreateRefund(ctx, changed); err == nil {
		t.Fatal("changed business refund replay was accepted")
	} else {
		var rejected *application.DomainError
		if !errors.As(err, &rejected) || rejected.Message != "refund identity was reused with different request details" {
			t.Fatalf("unexpected changed replay error: %v", err)
		}
	}
	second := request
	second.RefundID = domain.NewID("rf")
	second.IdempotencyKey = uuid.NewString()
	if _, err := service.CreateRefund(ctx, second); err == nil {
		t.Fatal("second full refund was accepted")
	} else {
		var rejected *application.DomainError
		if !errors.As(err, &rejected) || rejected.Message != "refund amount exceeds original order" {
			t.Fatalf("unexpected second refund error: %v", err)
		}
	}
}
