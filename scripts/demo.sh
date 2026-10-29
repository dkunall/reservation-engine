#!/usr/bin/env bash
# Walks the main flows against a running stack (docker compose up, see README).
# Needs: curl, jq, openssl, and the same JWT_SECRET the app runs with.
#   JWT_SECRET=... scripts/demo.sh            # API defaults to http://localhost:7400
# Expiry step waits for the hold TTL, so start the app with HOLD_DEFAULT_TTL=PT20S EXPIRY_SWEEP_INTERVAL=PT2S
# to keep the run short.
set -euo pipefail
cd "$(dirname "$0")"
: "${JWT_SECRET:?set JWT_SECRET}"
BASE=${BASE:-http://localhost:7400}
API=$BASE/api/v1
EXPIRY_WAIT=${EXPIRY_WAIT:-30}

ADMIN=$(./dev-jwt.sh admin-1 ADMIN)
ALICE=$(./dev-jwt.sh alice USER)
BOB=$(./dev-jwt.sh bob USER)
uuid() { cat /proc/sys/kernel/random/uuid; }
step() { printf '\n== %s\n' "$*"; }
# call TOKEN METHOD PATH [BODY] -> prints "<status> <body>"
call() {
  local tok=$1 m=$2 p=$3 body=${4:-}
  local args=(-s -o /tmp/demo-body -w '%{http_code}' -X "$m" "$API$p" -H "Authorization: Bearer $tok"
              -H 'Content-Type: application/json')
  [[ $m == POST ]] && args+=(-H "Idempotency-Key: ${IDEM:-$(uuid)}")
  [[ -n $body ]] && args+=(-d "$body")
  local code; code=$(curl "${args[@]}")
  echo "$code $(cat /tmp/demo-body)"
}
mk() { call "$ADMIN" POST /resources "$1" | cut -d' ' -f2- | jq -r .id; }
status_of() { cut -d' ' -f1 <<<"$1"; }
json_of() { cut -d' ' -f2- <<<"$1"; }

step "health"
curl -s "$BASE/actuator/health/readiness"; echo
curl -s -o /dev/null -w 'no token -> %{http_code}\n' "$API/resources"

step "event GA: hold -> confirm -> cancel"
GA=$(mk '{"type":"EVENT_GA","name":"Demo Fest GA","price":{"amount":"49.99","currency":"USD"},"inventory":{"capacity":100}}')
r=$(call "$ALICE" POST /reservations "{\"resourceId\":\"$GA\",\"quantity\":2}")
RES=$(json_of "$r" | jq -r .id)
echo "hold: $(status_of "$r") $(json_of "$r" | jq -c '{status,quantity,total:.total,holdExpiresAt}')"
KEY=$(uuid)
r=$(IDEM=$KEY call "$ALICE" POST /reservations/$RES/confirm '{"paymentMethodToken":"tok_visa"}')
echo "confirm: $(status_of "$r") $(json_of "$r" | jq -r .status)"
code=$(curl -s -o /dev/null -D /tmp/demo-h -w '%{http_code}' -X POST "$API/reservations/$RES/confirm" \
  -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d '{"paymentMethodToken":"tok_visa"}')
echo "same key again: $code, replayed=$(grep -i '^idempotent-replayed' /tmp/demo-h | tr -d '\r' | cut -d' ' -f2)"
r=$(IDEM=$KEY call "$ALICE" POST /reservations/$RES/confirm '{"paymentMethodToken":"tok_other"}')
echo "same key, different body: $(status_of "$r") $(json_of "$r" | jq -r '.title + ": " + (.detail // "")')"
r=$(call "$ALICE" POST /reservations/$RES/cancel '{"reason":"cannot attend"}')
echo "cancel: $(status_of "$r") $(json_of "$r" | jq -c '{status,cancelledAt}')"
r=$(call "$BOB" GET /reservations/$RES)
echo "bob reads alice's reservation: $(status_of "$r")"

step "validation and payment errors (RFC 7807)"
r=$(call "$ALICE" POST /reservations '{"quantity":0}')
echo "bad body: $(status_of "$r") $(json_of "$r" | jq -c '{type,status,detail}')"
r=$(call "$ALICE" POST /reservations "{\"resourceId\":\"$GA\",\"quantity\":1}")
R2=$(json_of "$r" | jq -r .id)
r=$(call "$ALICE" POST /reservations/$R2/confirm '{"paymentMethodToken":"tok_decline"}')
echo "declined card: $(status_of "$r") $(json_of "$r" | jq -c '{title,status,detail}')"

step "flight seat 1A: 30 concurrent holds"
FL=$(mk '{"type":"FLIGHT","name":"6E-201 BLR-DEL","attributes":{"departure":"2026-12-01T06:00:00Z"},"price":{"amount":"5200","currency":"INR"},"inventory":{"unitCodes":["1A","1B","1C"]}}')
for i in $(seq 1 30); do
  tok=$(./dev-jwt.sh "user-$i" USER)
  curl -s -o /dev/null -w '%{http_code}\n' -X POST "$API/reservations" -H "Authorization: Bearer $tok" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" \
    -d "{\"resourceId\":\"$FL\",\"unitCodes\":[\"1A\"]}" &
done > /tmp/demo-race
wait
echo "results: $(sort /tmp/demo-race | uniq -c | tr -s ' ' | tr '\n' ',')"

step "hotel room: overlapping stays"
HT=$(mk '{"type":"HOTEL_ROOM","name":"Deluxe King","price":{"amount":"120.00","currency":"EUR"},"inventory":{"unitCodes":["101"]}}')
r=$(call "$ALICE" POST /reservations "{\"resourceId\":\"$HT\",\"startsAt\":\"2026-12-10T14:00:00Z\",\"endsAt\":\"2026-12-12T11:00:00Z\"}")
HOLD_HT=$(json_of "$r" | jq -r .id)
echo "alice 10-12 Dec: $(status_of "$r") total=$(json_of "$r" | jq -c .total)"
r=$(call "$BOB" POST /reservations "{\"resourceId\":\"$HT\",\"startsAt\":\"2026-12-11T14:00:00Z\",\"endsAt\":\"2026-12-13T11:00:00Z\"}")
echo "bob 11-13 Dec (overlaps): $(status_of "$r") $(json_of "$r" | jq -r '.title + ": " + (.detail // "")')"
r=$(call "$BOB" POST /reservations "{\"resourceId\":\"$HT\",\"startsAt\":\"2026-12-12T11:00:00Z\",\"endsAt\":\"2026-12-14T11:00:00Z\"}")
echo "bob 12-14 Dec (back to back): $(status_of "$r")"

step "itinerary: flight + hotel + bus, all or nothing"
F2=$(mk '{"type":"FLIGHT","name":"6E-777","price":{"amount":"4000","currency":"INR"},"inventory":{"unitCodes":["1A"]}}')
H2=$(mk '{"type":"HOTEL_ROOM","name":"Goa Resort","price":{"amount":"3000","currency":"INR"},"inventory":{"unitCodes":["201"]}}')
B2=$(mk '{"type":"BUS","name":"Airport shuttle","price":{"amount":"250","currency":"INR"},"inventory":{"capacity":40}}')
items="{\"items\":[{\"resourceId\":\"$F2\",\"unitCodes\":[\"1A\"]},{\"resourceId\":\"$H2\",\"startsAt\":\"2026-12-01T14:00:00Z\",\"endsAt\":\"2026-12-03T11:00:00Z\"},{\"resourceId\":\"$B2\",\"quantity\":2}]}"
r=$(call "$ALICE" POST /itineraries "$items")
IT=$(json_of "$r" | jq -r .id)
echo "alice: $(status_of "$r") $(json_of "$r" | jq -c '{status,total:.total,items:(.items|length)}')"
r=$(call "$BOB" POST /itineraries "$items")
echo "bob, same seat: $(status_of "$r") $(json_of "$r" | jq -r '.title + ": " + (.detail // "")')"
r=$(call "$BOB" GET "/resources/$B2/availability?quantity=1")
echo "bus seats left after bob's failed itinerary: $(json_of "$r" | jq -c "{capacity,availableQuantity}")"
r=$(call "$ALICE" POST /itineraries/$IT/confirm)
echo "confirm itinerary: $(status_of "$r") $(json_of "$r" | jq -r .status)"

step "waitlist: sold out -> join -> auto-promoted on cancel"
W1=$(mk '{"type":"EVENT_GA","name":"Tiny gig","price":{"amount":"10","currency":"USD"},"inventory":{"capacity":1}}')
r=$(call "$ALICE" POST /reservations "{\"resourceId\":\"$W1\",\"quantity\":1}")
WR=$(json_of "$r" | jq -r .id)
r=$(call "$BOB" POST /reservations "{\"resourceId\":\"$W1\",\"quantity\":1}")
echo "bob when sold out: $(status_of "$r")"
r=$(call "$BOB" POST /waitlist "{\"resourceId\":\"$W1\",\"quantity\":1,\"maxWaitMinutes\":60}")
WL=$(json_of "$r" | jq -r .id)
echo "bob joins waitlist: $(status_of "$r") $(json_of "$r" | jq -r .status)"
call "$ALICE" POST /reservations/$WR/cancel '{"reason":"plans changed"}' >/dev/null
for _ in $(seq 1 20); do
  s=$(call "$BOB" GET /waitlist/$WL | cut -d' ' -f2-)
  [[ $(jq -r .status <<<"$s") == PROMOTED ]] && break; sleep 1
done
echo "bob's entry: $(jq -c '{status,promotedReservationId}' <<<"$s")"

step "hold expiry"
echo "alice's hotel hold before: $(call "$ALICE" GET /reservations/$HOLD_HT | cut -d' ' -f2- | jq -r .status)"
for _ in $(seq 1 "$EXPIRY_WAIT"); do
  st=$(call "$ALICE" GET /reservations/$HOLD_HT | cut -d' ' -f2- | jq -r .status)
  [[ $st == EXPIRED ]] && break; sleep 2
done
echo "after waiting: $st"
r=$(call "$BOB" POST /reservations "{\"resourceId\":\"$HT\",\"startsAt\":\"2026-12-11T14:00:00Z\",\"endsAt\":\"2026-12-12T11:00:00Z\"}")
echo "bob retries the freed dates: $(status_of "$r")"

step "correlation id and metrics"
curl -s -o /dev/null -D - "$API/resource-types" -H "Authorization: Bearer $ALICE" -H 'X-Correlation-Id: demo-123' \
  | grep -i '^x-correlation-id' | tr -d '\r'
curl -s "$BASE/actuator/prometheus" -H "Authorization: Bearer $ADMIN" | grep -E '^reservation_' | grep -v _bucket | sort
