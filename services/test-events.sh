#!/usr/bin/env bash
# End-to-end test for the ex8 event-driven layer.
#
# Spec:
#   1. property.listed     fires when a new listing is created
#   2. property.price-changed fires when a listing price is updated
#   3. property.hot        fires when a for-sale property is viewed
#
# Test: arrange a buyer + property + listing, trigger each event, and
# assert the notification-consumer's stdout log got the expected lines.
# Exits non-zero on any miss.

set -euo pipefail
cd "$(dirname "$0")"

: "${MONGO_URI:=mongodb://localhost:27017}"
export MONGO_URI

CONSUMER_LOG=/tmp/notification-consumer.log
GATEWAY=http://localhost:7070

# Sanity: stack must already be running. Start it if not.
if ! curl -sf "$GATEWAY/" >/dev/null 2>&1; then
    echo "stack not running — booting via run-all.sh"
    ./run-all.sh >/dev/null
    sleep 2
fi

# Truncate the consumer log so we only assert against this test run.
: > "$CONSUMER_LOG"

# Bonus: drop the demo DB so we don't collide with prior buyers/listings.
mongosh --quiet --eval 'use("nsw_property_data"); db.dropDatabase()' >/dev/null 2>&1 || true
sleep 1

PASS=0
FAIL=0
check() {
    local label=$1
    local needle=$2
    if grep -qF -- "$needle" "$CONSUMER_LOG"; then
        echo "  ✓ $label"
        PASS=$((PASS + 1))
    else
        echo "  ✗ $label  (expected substring: $needle)"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== arrange ==="
BUYER_JSON=$(curl -s -X POST "$GATEWAY/purchaser" -H 'Content-Type: application/json' \
    -d '{"name":"Event Buyer","email":"events@test.com","postcodes":["2770"]}')
BUYER=$(echo "$BUYER_JSON" | python3 -c 'import json,sys;print(json.load(sys.stdin)["purchaserId"])')
echo "buyer: $BUYER"

PROP=$(curl -s -X POST "$GATEWAY/property" -H 'Content-Type: application/json' \
    -d '{"postcode":"2770","propertyPrice":"500000","address":"42 Event Lane"}' \
    | python3 -c 'import json,sys;print(json.load(sys.stdin)["propertyID"])')
echo "property: $PROP"

echo
echo "=== act 1: property.listed ==="
LISTING=$(curl -s -X POST "$GATEWAY/listing" -H 'Content-Type: application/json' \
    -d "{\"propertyId\":\"$PROP\",\"price\":520000}" \
    | python3 -c 'import json,sys;print(json.load(sys.stdin)["listingId"])')
echo "listing: $LISTING"
sleep 1

echo
echo "=== act 2: property.price-changed ==="
curl -s -X POST "$GATEWAY/listing/$LISTING/price" -H 'Content-Type: application/json' \
    -d '{"price":450000}' > /dev/null
sleep 1

echo
echo "=== act 3: property.hot (3 views) ==="
for _ in 1 2 3; do curl -s "$GATEWAY/property/$PROP" > /dev/null; sleep 0.2; done
sleep 2

echo
echo "=== assert delivery messages ==="
check "buyer received 'listed' notice for 2770"              "eventType=listed"
check "buyer received 'listed' message body mentions 2770"   "went on sale"
check "buyer received 'price-changed' notice"                "eventType=price-changed"
check "buyer received 'price-changed' includes new price"    "450000.0"
check "buyer received at least one 'hot' notice"             "eventType=hot"
check "buyer received hot view_count=3 (last view)"          "3 views and counting"
check "routing key targets the buyer's id"                   "purchaser.$BUYER"

echo
echo "=== summary ==="
echo "passed: $PASS    failed: $FAIL"
if [ $FAIL -gt 0 ]; then
    echo
    echo "--- consumer log dump ---"
    cat "$CONSUMER_LOG"
    exit 1
fi
echo "all event assertions hold ✓"
