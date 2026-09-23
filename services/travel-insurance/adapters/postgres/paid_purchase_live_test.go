package postgres

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"reflect"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/travel-insurance/application"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

func TestPaidInsuranceConcurrentIssuanceAndReferenceValidation(t *testing.T) {
	if os.Getenv("TEST_INSURANCE_PURCHASE") == "" {
		t.Skip("recorded paid insurance required")
	}
	input, err := os.ReadFile(os.Getenv("TEST_INSURANCE_PURCHASE"))
	if err != nil {
		t.Fatal(err)
	}
	var purchase struct {
		Order    string `json:"order"`
		Account  string `json:"account"`
		Traveler string `json:"traveler"`
		Segment  string `json:"seg"`
		Item     string `json:"ancillary_order_item"`
		Payment  string `json:"payment_intent"`
		Request  struct {
			Steps map[string]struct {
				Response map[string]any `json:"response"`
			} `json:"steps"`
		} `json:"ancillary_request"`
	}
	if err := json.Unmarshal(input, &purchase); err != nil {
		t.Fatal(err)
	}
	base := os.Getenv("TEST_SERVICE_BASE_TEMPLATE")
	response, err := http.Get(fmt.Sprintf(base, "journey-order") + "/api/v1/journey-orders/" + purchase.Order)
	if err != nil {
		t.Fatal(err)
	}
	var order struct {
		Segments []struct {
			Ref       string    `json:"segmentRef"`
			Departure time.Time `json:"departureTime"`
			Arrival   time.Time `json:"arrivalTime"`
		} `json:"segments"`
	}
	if err := json.NewDecoder(response.Body).Decode(&order); err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	command := application.IssuePolicyCommand{ProductCode: "DELAY_INSURANCE", ProductVersion: "v1", JourneyOrderID: purchase.Order, AccountID: purchase.Account, TravelerRef: purchase.Traveler, SegmentRefs: []string{purchase.Segment}, AncillaryOrderItemID: purchase.Item, PaymentIntentID: purchase.Request.Steps["anc-payment"].Response["paymentIntentId"].(string)}
	for _, segment := range order.Segments {
		if segment.Ref == purchase.Segment {
			command.CoverageStartAt = segment.Departure
			command.CoverageEndAt = segment.Arrival
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	admin, err := pgxpool.New(ctx, os.Getenv("TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	schema := fmt.Sprintf("insurance_paid_%d", time.Now().UnixNano())
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
	migrations, err := storage.LoadMigrations(os.DirFS("../../migrations"), ".")
	if err != nil {
		t.Fatal(err)
	}
	if err := storage.NewMigrationRunner(pool).Run(ctx, migrations); err != nil {
		t.Fatal(err)
	}
	tx := NewTransactor(pool)
	verifier := application.PurchaseVerifier{Client: &http.Client{Timeout: 5 * time.Second}, BaseURLTemplate: base}
	service := application.NewInsuranceService(application.ServiceConfig{Repository: NewRepositoryWithProvider(tx), Publisher: NewOutboxPublisherWithProvider(tx), UnitOfWork: tx.Within, VerifyPurchase: verifier.Verify})
	type result struct {
		policy domain.Policy
		err    error
	}
	results := make(chan result, 16)
	for i := 0; i < 16; i++ {
		go func() { p, e := service.IssuePolicy(ctx, command); results <- result{p, e} }()
	}
	id := ""
	for i := 0; i < 16; i++ {
		r := <-results
		if r.err != nil {
			t.Fatal(r.err)
		}
		if id == "" {
			id = r.policy.ID
		}
		if id != r.policy.ID {
			t.Fatal("concurrent purchase produced another policy")
		}
	}
	var count int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM policy_snapshots").Scan(&count); err != nil || count != 1 {
		t.Fatalf("policy count=%d error=%v", count, err)
	}
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM outbox").Scan(&count); err != nil || count != 1 {
		t.Fatalf("event count=%d error=%v", count, err)
	}
	if reflect.ValueOf(service).Elem().FieldByName("policies").Len() != 0 {
		t.Fatal("database policies retained in memory")
	}
	wrong := command
	wrong.PaymentIntentID = purchase.Payment
	if _, err := service.IssuePolicy(ctx, wrong); err == nil {
		t.Fatal("ticket payment accepted as insurance premium")
	}
	wrong = command
	wrong.CoverageStartAt = command.CoverageStartAt.Add(-time.Hour)
	if _, err := service.IssuePolicy(ctx, wrong); err == nil {
		t.Fatal("coverage outside purchased journey accepted")
	}
	wrong = command
	wrong.AncillaryOrderItemID = purchase.Order
	if _, err := service.IssuePolicy(ctx, wrong); err == nil {
		t.Fatal("unrelated ancillary reference accepted")
	}
}
