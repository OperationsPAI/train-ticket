#!/usr/bin/env bash
# Invoicing e2e: title CRUD, deterministic SIM blue invoice, rejection,
# red-flush observation/completion, itinerary projection, and title 412.
cd "$(dirname "$0")" && . ./lib.sh
ensure_curl_pod

inv_req() { # METHOD PATH BODY -> RESP/LAST_CODE
  local m=$1 p=$2 body=${3:-} out
  out=$(k exec -i e2e-curl -- curl -s -w $'\n%{http_code}' -X "$m" "http://invoicing:8080$p" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid7)" ${body:+-d "$body"} 2>/dev/null)
  LAST_CODE=$(echo "$out" | tail -1); RESP=$(echo "$out" | sed '$d')
}
stream_has() { # EVENT NEEDLE
  k exec "$(redis_pod)" -- redis-cli XREVRANGE events:invoicing + - COUNT 120 > /tmp/invoicing-events.txt 2>/dev/null || true
  ET="$1" NEEDLE="$2" python3 - <<'PY'
import json, os, re
raw=open('/tmp/invoicing-events.txt').read()
for m in re.finditer(r'\{.*\}', raw):
    try:
        e=json.loads(m.group(0).encode().decode('unicode_escape'))
        if e.get('eventType') == os.environ['ET'] and os.environ['NEEDLE'] in json.dumps(e.get('payload',{})):
            print('yes'); break
    except Exception: pass
PY
}
finance_basis() { # ORDER -> amountBasis from consumed finance-settlement facts
  local order=$1 invoice_id code out
  for _ in $(seq 1 12); do
    req POST finance-settlement /api/v1/invoices "{\"orderId\":\"$order\"}"
    code=$LAST_CODE
    if [ "$code" = 201 ] || [ "$code" = 200 ]; then break; fi
    sleep 2
  done
  [ "$code" = 201 ] || [ "$code" = 200 ] || { echo "{}"; return; }
  invoice_id=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('invoiceId',''))" 2>/dev/null)
  for _ in $(seq 1 20); do
    k exec "$(redis_pod)" -- redis-cli XREVRANGE events:finance-settlement + - COUNT 200 > /tmp/finance-events.txt 2>/dev/null || true
    out=$(python3 - "$order" "$invoice_id" <<'PY'
import json, sys, re, hashlib
order, invoice_id = sys.argv[1], sys.argv[2]
raw=open('/tmp/finance-events.txt').read()
for m in re.finditer(r'\{.*\}', raw):
    try:
        e=json.loads(m.group(0).encode().decode('unicode_escape'))
    except Exception:
        continue
    if e.get('eventType') != 'InvoiceGenerated':
        continue
    payload=e.get('payload',{})
    if payload.get('orderId') != order or (invoice_id and payload.get('invoiceId') != invoice_id):
        continue
    total=payload.get('totalAmount') or {"currency":"CNY","minorUnits":0}
    basis={"basisType":"FINANCE_INVOICE","revenueRecognitionIds":sorted(payload.get('revenueRecognitionIds') or []),"financeInvoiceId":payload.get('invoiceId'),"taxLines":[{"taxCode":"VAT_SIM","taxRateBasisPoints":0,"taxableAmount":total,"taxAmount":{"currency":total.get('currency','CNY'),"minorUnits":0}}],"totalAmount":total}
    basis["amountBasisHash"]="sha256:"+hashlib.sha256(json.dumps(payload,separators=(',',':')).encode()).hexdigest()
    print(json.dumps(basis,separators=(',',':')))
    break
PY
)
    [ -n "$out" ] && { echo "$out"; return; }
    sleep 2
  done
  echo "{}"
}

echo "== 23-invoicing: title, blue invoice, red flush, itinerary"
# Build real purchase chains instead of injecting producer events.
bash ./01-seed.sh >/tmp/23-seed.out 2>&1 || { cat /tmp/23-seed.out; bad "seed chain failed"; summary; exit 0; }
bash ./02-purchase.sh >/tmp/23-purchase-a.out 2>&1 || { cat /tmp/23-purchase-a.out; bad "purchase chain A failed"; summary; exit 0; }
. ./.refs.env
MAIN_ACCT=$ACCT; MAIN_TVL=$TVL; MAIN_SEG=$SEG; MAIN_ORDER=$ORDER; MAIN_SB=$SB; MAIN_ENT=$ENT
inv_req POST /api/v1/invoice-titles "{\"accountId\":\"$MAIN_ACCT\",\"titleType\":\"PERSONAL\",\"titleName\":\"个人\",\"setAsDefault\":true}"
check_code 201 "create invoice title"
TITLE=$(jget "['titleId']"); TVER=$(jget "['version']")
sleep 4
BASIS=$(finance_basis "$MAIN_ORDER")
for _retry in $(seq 1 15); do
  inv_req POST /api/v1/e-invoice-requests "{\"accountId\":\"$MAIN_ACCT\",\"orderId\":\"$MAIN_ORDER\",\"titleId\":\"$TITLE\",\"titleVersion\":$TVER,\"invoiceScope\":{\"scopeType\":\"ORDER\"},\"amountBasis\":$BASIS,\"recipientEmail\":\"e2e@example.com\",\"simSeedRef\":\"accept-e2e\"}"
  [ "$LAST_CODE" = 201 ] && break
  sleep 2
done
check_code 201 "request blue e-invoice"
REQ=$(jget "['invoiceRequestId']"); STATUS=$(jget "['status']"); EIN=$(jget "['eInvoiceId']")
[ "$STATUS" = ISSUED ] && ok "SIM accepted and blue invoice issued" || bad "blue invoice status $STATUS"
EISSUED=""
for _retry in $(seq 1 10); do
  EISSUED=$(stream_has EInvoiceIssued "$MAIN_ORDER")
  [ "$EISSUED" = yes ] && break
  sleep 2
done
[ "$EISSUED" = yes ] && ok "EInvoiceIssued event" || bad "missing EInvoiceIssued"
inv_req GET "/api/v1/e-invoices/$EIN"; check_code 200 "get issued invoice"

bash ./02-purchase.sh >/tmp/23-purchase-b.out 2>&1 || { cat /tmp/23-purchase-b.out; bad "purchase chain B failed"; summary; exit 0; }
. ./.refs.env
REJ_ACCT=$ACCT; REJ_ORDER=$ORDER
inv_req POST /api/v1/invoice-titles "{\"accountId\":\"$REJ_ACCT\",\"titleType\":\"PERSONAL\",\"titleName\":\"拒绝种子\",\"setAsDefault\":true}"
check_code 201 "create rejection title"
RTITLE=$(jget "['titleId']"); RTVER=$(jget "['version']")
sleep 4
RBASIS=$(finance_basis "$REJ_ORDER")
for _retry in $(seq 1 15); do
  inv_req POST /api/v1/e-invoice-requests "{\"accountId\":\"$REJ_ACCT\",\"orderId\":\"$REJ_ORDER\",\"titleId\":\"$RTITLE\",\"titleVersion\":$RTVER,\"invoiceScope\":{\"scopeType\":\"ORDER\"},\"amountBasis\":$RBASIS,\"simSeedRef\":\"reject-e2e\"}"
  [ "$LAST_CODE" = 201 ] && break
  sleep 2
done
check_code 201 "request rejected seed invoice"
[ "$(jget "['status']")" = REJECTED ] && ok "SIM deterministic rejection resource" || bad "expected REJECTED got $(jget "['status']")"

# Real refund/post-sales chain for the originally issued invoice.
req POST post-sales /api/v1/post-sales-cases "{\"journeyOrderId\":\"$MAIN_ORDER\",\"caseType\":\"REFUND\",\"scope\":{\"orderItemRefs\":[\"$MAIN_SB\"],\"segmentRefs\":[\"$MAIN_SEG\"],\"travelerRefs\":[\"$MAIN_TVL\"],\"entitlementRefs\":[\"$MAIN_ENT\"]},\"reasonCode\":\"CUSTOMER_REQUEST\",\"actorRef\":\"$MAIN_ACCT\"}"
check_code 201 "open real refund case"
CASE=$(jget "['caseId']")
req POST post-sales "/api/v1/post-sales-cases/$CASE/evaluate" '{}'; check_code 200 "evaluate refund"
req POST post-sales "/api/v1/post-sales-cases/$CASE/approve" '{}'; check_code 200 "approve refund"
sleep 10
[ "$(stream_has RefundWithoutRedFlushObserved "$CASE")" = yes ] && ok "RefundWithoutRedFlushObserved event" || bad "missing refund violation observation"
for i in $(seq 1 8); do inv_req GET "/api/v1/red-flushes?orderId=$MAIN_ORDER"; [ "$(jget "['items'][0]['status']")" = COMPLETED ] && break; sleep 1; done
[ "$(jget "['items'][0]['status']")" = COMPLETED ] && ok "red flush completed" || bad "red flush status $(jget "['items'][0]['status']")"

for _retry in $(seq 1 10); do
  inv_req GET "/api/v1/itinerary-receipts/generated?orderId=$MAIN_ORDER&travelerRefs=$MAIN_TVL&segmentRefs=$MAIN_SEG&receiptVersion=1"
  [ "$LAST_CODE" = 200 ] && break
  sleep 2
done
check_code 200 "generate itinerary receipt projection"
[ -n "$(jget "['artifactRef']")" ] && ok "itinerary artifact ref" || bad "missing itinerary artifact"

inv_req DELETE "/api/v1/invoice-titles/$TITLE" "{\"expectedVersion\":$TVER,\"reason\":\"E2E_DONE\"}"
check_code 200 "deactivate title"
NBASIS=$(finance_basis "$REJ_ORDER")
inv_req POST /api/v1/e-invoice-requests "{\"accountId\":\"$MAIN_ACCT\",\"orderId\":\"$REJ_ORDER\",\"titleId\":\"$TITLE\",\"titleVersion\":$TVER,\"invoiceScope\":{\"scopeType\":\"ORDER\"},\"amountBasis\":$NBASIS,\"simSeedRef\":\"accept-e2e\"}"
[ "$LAST_CODE" = 412 ] && ok "deactivated title invoice rejected 412" || bad "deactivated title got $LAST_CODE"
summary
