#!/usr/bin/env bash
cd "$(dirname "$0")" && . ./lib.sh
ensure_curl_pod

future_time() { python3 - "$1" <<'PY'
import sys, datetime
print((datetime.datetime.now(datetime.UTC)+datetime.timedelta(seconds=int(sys.argv[1]))).strftime('%Y-%m-%dT%H:%M:%SZ'))
PY
}
past_time() { python3 - "$1" <<'PY'
import sys, datetime
print((datetime.datetime.now(datetime.UTC)-datetime.timedelta(seconds=int(sys.argv[1]))).strftime('%Y-%m-%dT%H:%M:%SZ'))
PY
}
benefit_body() { local acct=$1 amount=$2 until=$3 reason=${4:-GOODWILL_COMP}; cat <<JSON
{"accountId":"$acct","benefitType":"BALANCE","balanceType":"PROMOTION_CREDIT","amount":{"currency":"CNY","minorUnits":$amount},"issuanceSource":"MANUAL_OPS","applicableScope":{"scopeType":"ANY_TRIP","currency":"CNY"},"redemptionRule":{"singleUse":false,"requiresReservation":false},"revocationRule":{},"validFrom":"$(past_time 60)","validUntil":"$until","businessReason":{"reasonType":"MANUAL_OPS","reasonCode":"$reason","referenceType":"MANUAL_ACTION","referenceId":"act-$(uuid7)"}}
JSON
}

stream_notification_template_for_recipient() { # TEMPLATE RECIPIENT_REF -> yes/empty
  k exec "$(redis_pod)" -- redis-cli XREVRANGE events:notification + - COUNT 300 > /tmp/wallet-notification-stream.txt 2>/dev/null
  TEMPLATE="$1" RECIPIENT="$2" python3 - << 'PYEX'
import re, os, json
raw = open("/tmp/wallet-notification-stream.txt").read()
template = os.environ["TEMPLATE"]
recipient = os.environ["RECIPIENT"]
for m in re.finditer(r'\{.*\}', raw):
    try:
        e = json.loads(m.group(0).encode().decode('unicode_escape'))
    except Exception:
        continue
    payload = e.get("payload", {})
    if e.get("eventType") == "NotificationScheduled" and payload.get("templateCode") == template and payload.get("recipientRef") == recipient:
        print("yes")
        break
PYEX
}
benefit_cost_seen() { # ACCOUNT BENEFIT EVENT_TYPE -> yes/empty
  req GET finance-settlement "/api/v1/benefit-costs?accountId=$1&limit=50&offset=0"
  BENEFIT="$2" EVENT_TYPE="$3" BODY="$RESP" python3 - << 'PYEX'
import json, os
data = json.loads(os.environ.get("BODY", "{}"))
for item in data.get("items", []):
    if item.get("benefitId") == os.environ["BENEFIT"] and item.get("eventType") == os.environ["EVENT_TYPE"]:
        print("yes")
        break
PYEX
}
wallet_amounts() {
  local acct=$1
  req GET wallet-promotion "/api/v1/wallet-accounts/$acct"
  echo "$RESP" | python3 -c 'import sys,json; d=json.load(sys.stdin); b=d["balances"][0]; print(b["availableBalance"]["minorUnits"], b["reservedBalance"]["minorUnits"], b["redeemedBalance"]["minorUnits"])'
}

echo "== wallet-promotion issue/query/reserve/redeem/reverse"
ACCT="acc-$(uuid7)"; BODY=$(benefit_body "$ACCT" 1000 "$(future_time 3600)")
req POST wallet-promotion /api/v1/benefits "$BODY"; check_code 201 "issue benefit"; BEN=$(jget "['benefitId']")
req GET wallet-promotion "/api/v1/benefits/$BEN"; check_code 200 "get issued benefit"; [ "$(jget "['status']")" = ISSUED ] && ok "issued status observable" || bad "issued status mismatch"
read A R D < <(wallet_amounts "$ACCT"); [ "$A $R $D" = "1000 0 0" ] && ok "wallet credited" || bad "wallet after issue $A/$R/$D"
FIN_COST=""; NOTIFY_ISSUED=""
for attempt in 1 2 3 4 5; do
  FIN_COST=$(benefit_cost_seen "$ACCT" "$BEN" BenefitIssued)
  NOTIFY_ISSUED=$(stream_notification_template_for_recipient wallet_benefit_issued "$ACCT")
  [ "$FIN_COST" = yes ] && [ "$NOTIFY_ISSUED" = yes ] && break
  sleep 3
done
[ "$FIN_COST" = yes ] && ok "finance benefit-costs contains issued $BEN" || bad "finance benefit-costs missing issued $BEN"
[ "$NOTIFY_ISSUED" = yes ] && ok "notification scheduled wallet_benefit_issued for $ACCT" || bad "notification missing wallet_benefit_issued for $ACCT"
RESREF="ord-$(uuid7)"; req POST wallet-promotion "/api/v1/benefits/$BEN/reserve" "{\"amount\":{\"currency\":\"CNY\",\"minorUnits\":400},\"reservationRef\":\"$RESREF\",\"reservationExpiresAt\":\"$(future_time 600)\",\"businessReason\":{\"reasonType\":\"ORDER_PURCHASE\",\"reasonCode\":\"ORDER_BENEFIT_RESERVE\",\"referenceType\":\"ORDER\",\"referenceId\":\"$RESREF\"}}"; check_code 200 "reserve benefit"
read A R D < <(wallet_amounts "$ACCT"); [ "$A $R $D" = "600 400 0" ] && ok "wallet frozen" || bad "wallet after reserve $A/$R/$D"
req POST wallet-promotion "/api/v1/benefits/$BEN/redeem" "{\"amount\":{\"currency\":\"CNY\",\"minorUnits\":400},\"redemptionRef\":\"$RESREF\",\"reservationRef\":\"$RESREF\",\"businessReason\":{\"reasonType\":\"ORDER_PURCHASE\",\"reasonCode\":\"ORDER_BENEFIT_USE\",\"referenceType\":\"ORDER\",\"referenceId\":\"$RESREF\"}}"; check_code 200 "redeem reserved benefit"; RED=$(jget "['redemption']['redemptionId']")
req POST wallet-promotion "/api/v1/benefits/$BEN/redeem" "{\"amount\":{\"currency\":\"CNY\",\"minorUnits\":300},\"redemptionRef\":\"$RESREF\",\"reservationRef\":\"$RESREF\",\"businessReason\":{\"reasonType\":\"ORDER_PURCHASE\",\"reasonCode\":\"ORDER_BENEFIT_USE\",\"referenceType\":\"ORDER\",\"referenceId\":\"$RESREF\"}}"; [ "$LAST_CODE" = 409 ] && ok "duplicate redemption conflict" || bad "duplicate redemption got $LAST_CODE"
req POST wallet-promotion "/api/v1/benefits/$BEN/reverse-redemption" "{\"redemptionId\":\"$RED\",\"amount\":{\"currency\":\"CNY\",\"minorUnits\":400},\"businessReason\":{\"reasonType\":\"REVERSAL\",\"reasonCode\":\"ORDER_REFUND\",\"referenceType\":\"ORDER\",\"referenceId\":\"$RESREF\"}}"; check_code 200 "reverse redemption"
req GET wallet-promotion "/api/v1/benefits/$BEN"; [ "$(jget "['status']")" = REVERSED ] && ok "reversed benefit rests observable" || bad "reversed status mismatch"
read A R D < <(wallet_amounts "$ACCT"); [ "$A $R $D" = "1000 0 0" ] && ok "wallet restored by reversal" || bad "wallet after reverse $A/$R/$D"

echo "== wallet-promotion expiry and revoke"
ACCT_E="acc-$(uuid7)"; req POST wallet-promotion /api/v1/benefits "$(benefit_body "$ACCT_E" 250 "$(future_time 2)" SHORT_VALIDITY)"; check_code 201 "issue short benefit"; BEN_E=$(jget "['benefitId']"); sleep 8
req GET wallet-promotion "/api/v1/benefits/$BEN_E"; [ "$(jget "['status']")" = EXPIRED ] && ok "expired benefit rests observable" || bad "expired status $(jget "['status']")"
read A R D < <(wallet_amounts "$ACCT_E"); [ "$A $R" = "0 0" ] && ok "expiry removes exposure" || bad "wallet after expiry $A/$R/$D"
FIN_EXPIRED=""
for attempt in 1 2 3 4 5; do
  FIN_EXPIRED=$(benefit_cost_seen "$ACCT_E" "$BEN_E" BenefitExpired)
  [ "$FIN_EXPIRED" = yes ] && break
  sleep 3
done
[ "$FIN_EXPIRED" = yes ] && ok "finance benefit-costs contains expired $BEN_E" || bad "finance benefit-costs missing expired $BEN_E"
ACCT_R="acc-$(uuid7)"; req POST wallet-promotion /api/v1/benefits "$(benefit_body "$ACCT_R" 300 "$(future_time 3600)" REVOKE_TEST)"; check_code 201 "issue revocable benefit"; BEN_R=$(jget "['benefitId']")
req POST wallet-promotion "/api/v1/benefits/$BEN_R/revoke" "{\"businessReason\":{\"reasonType\":\"CUSTOMER_SERVICE_ADJUSTMENT\",\"reasonCode\":\"ADMIN_REVOKE\",\"referenceType\":\"MANUAL_ACTION\",\"referenceId\":\"act-$(uuid7)\"}}"; check_code 200 "revoke benefit"
req GET wallet-promotion "/api/v1/benefits/$BEN_R"; [ "$(jget "['status']")" = REVOKED ] && ok "revoked benefit rests observable" || bad "revoked status mismatch"
read A R D < <(wallet_amounts "$ACCT_R"); [ "$A $R $D" = "0 0 0" ] && ok "revoke removes available" || bad "wallet after revoke $A/$R/$D"

summary
