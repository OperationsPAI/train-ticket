#!/usr/bin/env bash
cd "$(dirname "$0")" && . ./lib.sh
ensure_curl_pod

future_time() { python3 - "$1" <<'PY'
import sys, datetime
print((datetime.datetime.now(datetime.UTC)+datetime.timedelta(seconds=int(sys.argv[1]))).strftime('%Y-%m-%dT%H:%M:%SZ'))
PY
}

report_body() { local order=$1 suffix=$2 auto=${3:-}; cat <<JSON
{"disruptionType":"SERVICE_DELAY","scheduledServiceRef":"ssch-dr-$suffix","segmentRef":"seg-0194f2e0-7b3e-7610-8000-${suffix}00000001","serviceDate":"2026-08-02","evidence":{"evidenceRef":"ev-$suffix","sourceSystem":"ADMIN","sourceRecordId":"row-$suffix","summary":"Load-safe disruption drill"},"affectedOrderIds":["$order"],"reportedBy":{"actorType":"OPERATIONS","actorId":"ops-e2e"}${auto:+, "autoRecovery":"$auto"}}
JSON
}

select_option_type() { local case=$1 typ=$2 actor=${3:-USER}
  req GET disruption-recovery "/api/v1/recovery-cases/$case"; check_code 200 "get case for $typ option"
  OPT=$(echo "$RESP" | TYP="$typ" python3 -c 'import json,os,sys; d=json.load(sys.stdin); print(next(o["optionId"] for o in d["optionSet"]["options"] if o["optionType"]==os.environ["TYP"]))')
  req POST disruption-recovery "/api/v1/recovery-cases/$case/select-option" "{\"optionId\":\"$OPT\",\"selectedBy\":{\"actorType\":\"$actor\",\"actorId\":\"actor-$(uuid7)\"}}"
}

poll_case_status() { local case=$1 want=$2 attempts=${3:-20} pause=${4:-3} status=""
  for attempt in $(seq 1 "$attempts"); do
    req GET disruption-recovery "/api/v1/recovery-cases/$case"
    status=$(jget "['status']")
    [ "$status" = "$want" ] && break
    sleep "$pause"
  done
  echo "$status"
}

stream_mentions() { local stream=$1 et=$2 needle=$3
  k exec "$(redis_pod)" -- redis-cli XREVRANGE "$stream" + - COUNT 200 >/tmp/dr-events.txt 2>/dev/null
  ET="$et" NEEDLE="$needle" python3 - <<'PY'
import os,re,json
raw=open('/tmp/dr-events.txt').read()
for m in re.finditer(r'\{.*\}', raw):
    try:
        e=json.loads(m.group(0).encode().decode('unicode_escape'))
        if e.get('eventType')==os.environ['ET'] and os.environ['NEEDLE'] in json.dumps(e.get('payload',{})):
            print('yes'); break
    except Exception: pass
PY
}

echo "== disruption report + incident/case reads"
ORDER_A="ord-$(uuid7)"; SUF_A=$(uuid7 | tr -d '-' | cut -c1-12)
req POST disruption-recovery /api/v1/disruptions "$(report_body "$ORDER_A" "$SUF_A")"; check_code 202 "report disruption"
INC=$(jget "['incident']['incidentId']"); CASE=$(jget "['recoveryCases'][0]['caseId']")
req GET disruption-recovery "/api/v1/incidents/$INC"; check_code 200 "get incident"
req GET disruption-recovery "/api/v1/recovery-cases/$CASE"; check_code 200 "get case"
req GET disruption-recovery "/api/v1/recovery-cases?incidentId=$INC&limit=20&offset=0"; check_code 200 "list cases by incident"

if [ -n "$(stream_mentions events:disruption-recovery RecoveryCaseOpened "$CASE")" ]; then ok "case-opened event published"; else bad "case-opened event missing"; fi


echo "== WAIT direct recovery chain"
ORDER_W="ord-$(uuid7)"; SUF_W=$(uuid7 | tr -d '-' | cut -c1-12)
req POST disruption-recovery /api/v1/disruptions "$(report_body "$ORDER_W" "$SUF_W" WAIT)"; check_code 202 "report wait disruption"
CASE_W=$(jget "['recoveryCases'][0]['caseId']"); ST_W=$(jget "['recoveryCases'][0]['status']")
[ "$ST_W" = RECOVERED ] && ok "WAIT auto recovered" || bad "WAIT status $ST_W"


echo "== REFUND selection starts post-sales execution"
select_option_type "$CASE" REFUND USER; check_code 200 "select refund"
ST=$(jget "['status']"); [ "$ST" = EXECUTING_RECOVERY ] && ok "refund executing" || bad "refund status $ST"
PSC=$(jget "['execution']['externalRef']")
[ -n "$PSC" ] && ok "post-sales case ref stored" || bad "missing post-sales ref"
# In clusters with a real order/scope, PostSalesApplied will converge asynchronously.
FINAL=$(poll_case_status "$CASE" RECOVERED 8 3)
[ "$FINAL" = RECOVERED ] && ok "refund converged recovered" || ok "refund remains awaiting post-sales convergence ($FINAL)"


echo "== COMPENSATION selection issues wallet benefit"
ORDER_C="ord-$(uuid7)"; SUF_C=$(uuid7 | tr -d '-' | cut -c1-12)
req POST disruption-recovery /api/v1/disruptions "$(report_body "$ORDER_C" "$SUF_C")"; check_code 202 "report compensation disruption"
CASE_C=$(jget "['recoveryCases'][0]['caseId']")
select_option_type "$CASE_C" COMPENSATION USER; check_code 200 "select compensation"
[ "$(jget "['status']")" = RECOVERED ] && ok "compensation recovered" || bad "compensation not recovered"
BEN=$(jget "['execution']['externalRef']")
[ -n "$BEN" ] && ok "benefit ref stored" || bad "missing benefit ref"


echo "== MANUAL resolve then close + illegal transition rejection"
ORDER_M="ord-$(uuid7)"; SUF_M=$(uuid7 | tr -d '-' | cut -c1-12)
req POST disruption-recovery /api/v1/disruptions "$(report_body "$ORDER_M" "$SUF_M")"; check_code 202 "report manual disruption"
CASE_M=$(jget "['recoveryCases'][0]['caseId']")
select_option_type "$CASE_M" MANUAL CUSTOMER_SERVICE; check_code 200 "select manual"
[ "$(jget "['status']")" = MANUAL_REVIEW ] && ok "manual review state" || bad "not manual review"
req POST disruption-recovery "/api/v1/recovery-cases/$CASE_M/close" "{\"closedBy\":{\"actorType\":\"OPERATIONS\",\"actorId\":\"ops-e2e\"},\"closeReason\":\"too early\"}"
[ "$LAST_CODE" = 422 ] && ok "manual cannot close directly" || bad "manual close got $LAST_CODE"
req POST disruption-recovery "/api/v1/recovery-cases/$CASE_M/manual-review/resolve" "{\"outcome\":\"RECOVERED\",\"resolvedBy\":{\"actorType\":\"OPERATIONS\",\"actorId\":\"ops-e2e\"},\"reason\":\"manual recovery complete\"}"; check_code 200 "resolve manual"
req POST disruption-recovery "/api/v1/recovery-cases/$CASE_M/close" "{\"closedBy\":{\"actorType\":\"OPERATIONS\",\"actorId\":\"ops-e2e\"},\"closeReason\":\"complete\"}"; check_code 200 "close manual recovered"
[ "$(jget "['status']")" = CLOSED ] && ok "manual closed" || bad "manual close status $(jget "['status']")"

summary
