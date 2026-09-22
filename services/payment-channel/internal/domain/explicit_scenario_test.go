package domain

import (
	"testing"
	"time"
)

func TestPaymentAndRefundAmountsDoNotSelectFailureScenarios(t *testing.T) {
	now := time.Now().UTC()
	for units := int64(100); units < 200; units++ {
		for _, seed := range []*FaultSeed{nil, {SeedVersion: "1", ScenarioCode: "NORMAL", SeedMaterialHash: "normal"}} {
			order, err := NewChannelOrder("", "pi-check", "ord-check", "purchase", ChannelAlipay, Money{"CNY", units}, "order-key", "cmd-check", "corr-check", seed, now)
			if err != nil {
				t.Fatal(err)
			}
			if err := order.Submit(order.Version, order.RequestFingerprint, now); err != nil {
				t.Fatal(err)
			}
			if order.Status != StatusSucceeded {
				t.Fatalf("amount=%d status=%s", units, order.Status)
			}
			refund, err := NewChannelRefund("", "rf-check", order.PaymentIntentID, order.ChannelOrderID, order.ChannelTransactionID, ChannelAlipay, order.Amount, "CUSTOMER_REQUEST", "refund-key", "cmd-check", "corr-check", seed, now)
			if err != nil {
				t.Fatal(err)
			}
			if err := refund.Submit(refund.Version, refund.RequestFingerprint, now); err != nil {
				t.Fatal(err)
			}
			if refund.Status != StatusSucceeded || refund.ChannelRefundTransactionID == "" {
				t.Fatalf("amount=%d refund=%s", units, refund.Status)
			}
		}
	}
}

func TestExplicitDeclineScenariosRemainAvailable(t *testing.T) {
	now := time.Now().UTC()
	seed := &FaultSeed{SeedVersion: "1", ScenarioCode: "DECLINED_ORDER", SeedMaterialHash: "decline"}
	order, err := NewChannelOrder("", "pi-check", "ord-check", "purchase", ChannelAlipay, Money{"CNY", 100}, "order-key", "cmd-check", "corr-check", seed, now)
	if err != nil {
		t.Fatal(err)
	}
	if err := order.Submit(order.Version, order.RequestFingerprint, now); err != nil {
		t.Fatal(err)
	}
	if order.Status != StatusFailed {
		t.Fatalf("order=%s", order.Status)
	}
	seed = &FaultSeed{SeedVersion: "1", ScenarioCode: "DECLINED_REFUND", SeedMaterialHash: "decline"}
	refund, err := NewChannelRefund("", "rf-check", "pi-check", "cho-check", "txn-check", ChannelAlipay, Money{"CNY", 100}, "CUSTOMER_REQUEST", "refund-key", "cmd-check", "corr-check", seed, now)
	if err != nil {
		t.Fatal(err)
	}
	if err := refund.Submit(refund.Version, refund.RequestFingerprint, now); err != nil {
		t.Fatal(err)
	}
	if refund.Status != StatusFailed {
		t.Fatalf("refund=%s", refund.Status)
	}
}
