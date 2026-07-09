#!/usr/bin/env bash
cd "$(dirname "$0")" && . ./lib.sh

echo "== 22-paychan: payment-channel deterministic chain"
RUN="paychan-$(date +%s)-$RANDOM"
BASE="${BASE_URL:-http://localhost:8080}"
cmd_id() { python3 - <<'PY'
import time,random
ms=int(time.time()*1000); print('cmd-%08x-%04x-7%03x-%04x-%012x'%(ms>>16,ms&0xffff,random.getrandbits(12),0x8000|random.getrandbits(14),random.getrandbits(48)))
PY
}
uuid7() { python3 - <<'PY'
import time,random
ms=int(time.time()*1000); print('%08x-%04x-7%03x-%04x-%012x'%(ms>>16,ms&0xffff,random.getrandbits(12),0x8000|random.getrandbits(14),random.getrandbits(48)))
PY
}
post_pc() { local path=$1 body=$2; curl -sf -H "Content-Type: application/json" -H "Idempotency-Key: $(uuid7)" -H "X-Correlation-Id: corr-$(uuid7)" -d "$body" "$BASE/payment-channel$path"; }
# If gateway path routing is unavailable, direct service DNS/port-forward users can set PAYMENT_CHANNEL_URL.
if [ -n "${PAYMENT_CHANNEL_URL:-}" ]; then BASE="$PAYMENT_CHANNEL_URL"; post_pc(){ local path=$1 body=$2; curl -sf -H "Content-Type: application/json" -H "Idempotency-Key: $(uuid7)" -H "X-Correlation-Id: corr-$(uuid7)" -d "$body" "$BASE$path"; }; fi
ORDER=$(post_pc /api/v1/channel-orders "{\"paymentIntentId\":\"pi-$RUN\",\"businessRef\":\"ord-$RUN\",\"purpose\":\"purchase\",\"channel\":\"ALIPAY_SIM\",\"amount\":{\"currency\":\"CNY\",\"minorUnits\":100},\"sourceCommandId\":\"$(cmd_id)\",\"correlationId\":\"corr-$(uuid7)\"}") || bad "create order"
OID=$(echo "$ORDER"|python3 -c 'import sys,json;print(json.load(sys.stdin)["channelOrderId"])')
VER=$(echo "$ORDER"|python3 -c 'import sys,json;print(json.load(sys.stdin)["version"])')
FP=$(echo "$ORDER"|python3 -c 'import sys,json;print(json.load(sys.stdin)["requestFingerprint"])')
SUB=$(post_pc /api/v1/channel-orders/$OID/submit "{\"expectedVersion\":$VER,\"requestFingerprint\":\"$FP\"}") || bad "submit order"
echo "$SUB"|grep -q '"status":"SUCCEEDED"' && ok "channel order succeeded" || bad "channel order not succeeded"
TXN=$(echo "$SUB"|python3 -c 'import sys,json;print(json.load(sys.stdin)["channelTransactionId"])')
RF=$(post_pc /api/v1/channel-refunds "{\"refundId\":\"rf-$RUN\",\"paymentIntentId\":\"pi-$RUN\",\"channelOrderId\":\"$OID\",\"originalChannelTransactionId\":\"$TXN\",\"channel\":\"ALIPAY_SIM\",\"amount\":{\"currency\":\"CNY\",\"minorUnits\":40},\"refundReasonCode\":\"USER\",\"sourceCommandId\":\"$(cmd_id)\",\"correlationId\":\"corr-$(uuid7)\"}") || bad "create refund"
RID=$(echo "$RF"|python3 -c 'import sys,json;print(json.load(sys.stdin)["channelRefundId"])'); RVER=$(echo "$RF"|python3 -c 'import sys,json;print(json.load(sys.stdin)["version"])'); RFP=$(echo "$RF"|python3 -c 'import sys,json;print(json.load(sys.stdin)["requestFingerprint"])')
post_pc /api/v1/channel-refunds/$RID/submit "{\"expectedVersion\":$RVER,\"requestFingerprint\":\"$RFP\"}" | grep -q '"status":"SUCCEEDED"' && ok "channel refund succeeded" || bad "refund failed"
MISS=$(post_pc /api/v1/channel-orders "{\"paymentIntentId\":\"pi-miss-$RUN\",\"businessRef\":\"ord-miss-$RUN\",\"purpose\":\"purchase\",\"channel\":\"WECHAT_SIM\",\"amount\":{\"currency\":\"CNY\",\"minorUnits\":101},\"sourceCommandId\":\"$(cmd_id)\",\"correlationId\":\"corr-$(uuid7)\",\"faultSeed\":{\"seedVersion\":\"v1\",\"scenarioCode\":\"MISSED_ORDER\",\"seedMaterialHash\":\"$RUN\"}}") || bad "create missed"
MOID=$(echo "$MISS"|python3 -c 'import sys,json;print(json.load(sys.stdin)["channelOrderId"])'); MVER=$(echo "$MISS"|python3 -c 'import sys,json;print(json.load(sys.stdin)["version"])'); MFP=$(echo "$MISS"|python3 -c 'import sys,json;print(json.load(sys.stdin)["requestFingerprint"])')
post_pc /api/v1/channel-orders/$MOID/submit "{\"expectedVersion\":$MVER,\"requestFingerprint\":\"$MFP\"}" | grep -q '"status":"MISSED"' && ok "missed seed applied" || bad "missed seed"
post_pc /api/v1/channel-orders/$MOID/query "{\"expectedVersion\":$((MVER+2)),\"queryReasonCode\":\"PAYMENT_TIMEOUT\"}" | grep -q '"status":"SUCCEEDED"' && ok "query recovery" || bad "query recovery"
ST=$(post_pc /api/v1/channel-statements/generate "{\"channel\":\"ALIPAY_SIM\",\"statementDate\":\"2026-07-10\",\"currency\":\"CNY\",\"seedVersion\":\"v1\",\"scenarioCodes\":[\"AMOUNT_MISMATCH\"],\"operatorRef\":\"e2e\",\"reasonCode\":\"E2E\"}") || bad "statement gen"
SID=$(echo "$ST"|python3 -c 'import sys,json;print(json.load(sys.stdin)["channelStatementId"])'); SH=$(echo "$ST"|python3 -c 'import sys,json;print(json.load(sys.stdin)["statementHash"])'); SVER=$(echo "$ST"|python3 -c 'import sys,json;print(json.load(sys.stdin)["version"])')
post_pc /api/v1/channel-statements/$SID/freeze "{\"statementHash\":\"$SH\",\"expectedVersion\":$SVER,\"operatorRef\":\"e2e\",\"reasonCode\":\"E2E\"}" | grep -q '"status":"FROZEN"' && ok "statement frozen" || bad "statement freeze"
D=$(post_pc /api/v1/channel-discrepancies "{\"channelStatementId\":\"$SID\",\"differenceType\":\"AMOUNT_MISMATCH\",\"expectedAmount\":{\"currency\":\"CNY\",\"minorUnits\":100},\"actualAmount\":{\"currency\":\"CNY\",\"minorUnits\":101},\"evidenceRef\":\"ev-$RUN\"}") || bad "open discrepancy"
DID=$(echo "$D"|python3 -c 'import sys,json;print(json.load(sys.stdin)["discrepancyId"])'); DVER=$(echo "$D"|python3 -c 'import sys,json;print(json.load(sys.stdin)["version"])')
post_pc /api/v1/channel-discrepancies/$DID/resolve "{\"resolutionStatus\":\"RESOLVED\",\"resolutionRef\":\"res-$RUN\",\"operatorRef\":\"e2e\",\"reasonCode\":\"MATCHED\",\"expectedVersion\":$DVER}" | grep -q '"status":"RESOLVED"' && ok "discrepancy resolved" || bad "resolve discrepancy"
summary
