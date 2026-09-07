package main

import (
	"context"
	"net/url"
	"time"
)

// jmap is a shorthand for the untyped JSON object literals these request
// bodies are built from.
type jmap = map[string]interface{}

// MaybeWalletPurchaseBenefit runs the wallet benefit branch of a purchase:
// issue a promotion credit for the buying account, reserve it against a
// pseudo-order ref, then redeem the reservation.
//
// Gated by wallet_promotion.p_purchase_reserve_redeem. Distinct from the ops
// sweep (ops.p_wallet_manual_issue), which only issues a benefit and reads it
// back -- the reserve and redeem transitions are exercised ONLY here, so
// without this branch wallet-promotion keeps issuance coverage but loses its
// redemption state machine entirely.
//
// Returns the benefit id and wallet account for the long-tail read probes.
func MaybeWalletPurchaseBenefit(ctx context.Context, p *Providers, accountID string) (benefitID, walletAccount string) {
	if p.Rng.Float64() >= p.Cfg.Wallet.PPurchaseReserveRedeem {
		return "", ""
	}

	amount := p.Cfg.Wallet.PurchaseBenefitMinorUnits
	if amount <= 0 {
		amount = 100
	}
	currency := p.Currency()
	now := time.Now().UTC()

	_, issued, err := p.API.Request(ctx, "POST", "wallet-promotion", "/api/v1/benefits",
		jmap{
			"accountId":       accountID,
			"benefitType":     "BALANCE",
			"balanceType":     "PROMOTION_CREDIT",
			"amount":          jmap{"currency": currency, "minorUnits": amount},
			"issuanceSource":  "MANUAL_OPS",
			"applicableScope": jmap{"scopeType": "ANY_TRIP", "currency": currency},
			// requiresReservation is false so the redeem below still succeeds
			// if the reserve call is rejected; the reserve is issued anyway so
			// the reservation path gets exercised.
			"redemptionRule": jmap{"singleUse": false, "requiresReservation": false},
			"revocationRule": jmap{},
			"validFrom":      NowISO(),
			"validUntil":     ISO(now.Add(7 * 24 * time.Hour)),
			"businessReason": jmap{
				"reasonType":    "MANUAL_OPS",
				"reasonCode":    "LOADGEN_MANUAL_OPS",
				"referenceType": "MANUAL_ACTION",
				"referenceId":   "act-" + UUID7(),
			},
		}, nil, []int{201}, "wallet-issue")
	if err != nil {
		p.Stats.RecordError("wallet:issue")
		return "", ""
	}
	benefitID = getString(issued, "benefitId")
	if benefitID == "" {
		p.Stats.RecordError("wallet:issue:no_id")
		return "", ""
	}
	p.Stats.RecordJourney("wallet:issued")

	// Reserve/redeem must move exactly what the benefit actually carries;
	// wallet-promotion rejects an amount above the available balance.
	if avail, ok := issued["availableAmount"].(map[string]interface{}); ok {
		if v := getInt(avail, "minorUnits", 0); v > 0 {
			amount = v
		}
	}

	ref := "ord-" + UUID7()
	money := jmap{"currency": currency, "minorUnits": amount}

	_, _, err = p.API.Request(ctx, "POST", "wallet-promotion",
		"/api/v1/benefits/"+url.PathEscape(benefitID)+"/reserve",
		jmap{
			"amount":               money,
			"reservationRef":       ref,
			"reservationExpiresAt": ISO(now.Add(10 * time.Minute)),
			"businessReason": jmap{
				"reasonType":    "ORDER_PURCHASE",
				"reasonCode":    "LOADGEN_BENEFIT_RESERVE",
				"referenceType": "ORDER",
				"referenceId":   ref,
			},
		}, nil, []int{200}, "wallet-reserve")
	if err != nil {
		p.Stats.RecordError("wallet:reserve")
		// The benefit exists and is still worth probing, so return it.
		return benefitID, accountID
	}

	_, _, err = p.API.Request(ctx, "POST", "wallet-promotion",
		"/api/v1/benefits/"+url.PathEscape(benefitID)+"/redeem",
		jmap{
			"amount":         money,
			"redemptionRef":  ref,
			"reservationRef": ref,
			"businessReason": jmap{
				"reasonType":    "ORDER_PURCHASE",
				"reasonCode":    "LOADGEN_BENEFIT_USE",
				"referenceType": "ORDER",
				"referenceId":   ref,
			},
		}, nil, []int{200}, "wallet-redeem")
	if err != nil {
		p.Stats.RecordError("wallet:redeem")
		return benefitID, accountID
	}

	p.Stats.RecordJourney("wallet:reserve_redeem")
	return benefitID, accountID
}
