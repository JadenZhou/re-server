# Exercise 7 — Microservices Architecture

The monolithic REServer is replaced by four independently-runnable jars. The
brief asked for an API gateway plus three context-bounded services. Each
service builds its own fat jar and runs on its own port; they communicate
only via HTTP (no shared in-process state).

Underlying store: **MongoDB**. All three data-owning services point at the
same Mongo URI but each owns a different set of collections. The
bounded-context rule is enforced by each service's `db/Db.java` and by what
each DAO queries — a code grep shows that no service touches another's
collections.

## The four services

| Service | Port | Collections it owns | Public to gateway |
|---|---|---|---|
| **gateway** | 7070 | none — pure orchestration | yes (only one clients hit) |
| **property-server** | 7071 | `properties`, `listings`, `property_pricing_updates` | gateway-only |
| **purchaser-server** | 7072 | `accounts` (Buyer docs with `postcode_interest` array) | gateway-only |
| **analytics-server** | 7073 | `property_views`, `postcode_searches` (NEW, ex7 only) | gateway-only |

The pre-ex7 monolith stored attention counters as fields on existing
documents (e.g. `properties.view_count`). For **strict bounded contexts**
those counters moved into the analytics service's own collections —
property-server and purchaser-server have no fields, no code, no awareness
of attention counts. The only way to read or write them is via HTTP to
analytics-server.

## Where orchestration lives

The brief flagged this as the key design decision. Two options were on the
table:

| Approach | Pros | Cons |
|---|---|---|
| **Gateway orchestrates** (chosen) | Single integration point; downstream services stay single-purpose; no service-to-service coupling | Gateway grows logic over time; single hop overhead per fan-out |
| Service-to-service chained calls | Less work in the gateway | Couples services to each other; harder to evolve; brief explicitly says "gateway is the client of the other three" |

We picked gateway-orchestrates throughout, with one helper class
(`notify.Notifier`) for the multi-step notifier flow.

## Fan-out per endpoint

| Client endpoint (port 7070) | Property | Purchaser | Analytics |
|---|---|---|---|
| `GET /property` | GET `/property` (+ optional `?minPrice`, `?maxPrice`) | — | — |
| `POST /property` | POST `/property` | — | — |
| `GET /property/{id}` | GET `/property/{id}` | — | POST `/views/property/{id}` (bump) |
| `GET /property/postcode/{pc}` | GET `/property/postcode/{pc}` | — | POST `/searches/postcode/{pc}` (bump, only on non-empty) |
| `POST /listing` | POST `/listing` | — | — |
| `POST /listing/seed` | POST `/listing/seed` | — | — |
| `GET /listing/{id}` | GET `/listing/{id}?withHistory=true` | — | — |
| `POST /listing/{id}/price` | POST `/listing/{id}/price` | — | — |
| `POST /purchaser` | — | POST `/purchaser` | — |
| `GET /purchaser/{id}` | — | GET `/purchaser/{id}` | — |
| `GET /purchaser/postcode/{pc}` | — | GET `/purchaser/postcode/{pc}` | POST `/searches/postcode/{pc}` (bump) |
| `POST /purchaser/{id}/interest` | — | POST `/purchaser/{id}/interest` | — |
| `DELETE /purchaser/{id}/interest/{pc}` | — | DELETE `/purchaser/{id}/interest/{pc}` | — |
| `POST /purchaser/seed` | — | POST `/purchaser/seed?count=N` | — |
| `GET /stats/property/{id}` | — | — | GET `/views/property/{id}` |
| `GET /stats/postcode/{pc}` | — | — | GET `/searches/postcode/{pc}` |
| `GET /notify` (orchestrated) | GET `/internal/for-sale` | GET `/purchaser` | — |

The analytics POSTs from the property/postcode endpoints are
fire-and-forget — the gateway returns the property/purchaser data even if
the analytics bump fails. This decouples client-visible behavior from the
analytics service's availability.

## HTML vs JSON (content negotiation)

The pre-ex7 monolith rendered HTML for GET endpoints. The split moved that
formatting concern out of each service: internal services emit JSON only,
and the **gateway** renders HTML on top of those JSON responses when the
caller asks for it.

The gateway picks a representation per request:
- `Accept: text/html` (browser) → HTML page
- `?format=html` query param → HTML page (overrides Accept; useful from Postman)
- Anything else → JSON (Postman, curl, other services)

POST/DELETE responses are JSON regardless (matches the monolith's plain-text
behavior, just structured).

## HTTP client

`java.net.http.HttpClient` (built into Java 21). One `client.ServiceClient`
instance per gateway wraps it, knows the three downstream base URLs, and
handles JSON (de)serialization via Jackson (already bundled with Javalin).

The whole client is ~120 lines and has no Spring / Feign / etc. — pure JDK.

## Running it

### Build all jars

```bash
cd services
mvn -DskipTests package          # builds 4 jars (one per module)
```

### Start the stack (uses local Mongo)

```bash
# A local Mongo daemon at localhost:27017 (brew services start mongodb-community)
# or an Atlas URI — anything the driver can connect to.
export MONGO_URI=mongodb://localhost:27017

cd services && ./run-all.sh
# ... hit the gateway with the existing Postman collection ...
./stop-all.sh
```

`run-all.sh` spawns all four services in the background with shared
`MONGO_URI`, then polls each port until it accepts connections.

### Loading real data

This branch doesn't ship a Mongo CSV loader (the data-loading utility on
`alternate/db` writes SQLite). For the demo you can:

- Use the gateway's `POST /property` and `POST /listing/seed` endpoints to
  create properties and listings by hand.
- Use `POST /purchaser/seed?count=N` to bulk-create synthetic buyers.
- Or check out `feat/ex5-access-counters`, run that branch's loader once
  to populate the shared Mongo, then switch back to this branch — the four
  services see the existing data unchanged.

## Demo path the instructor sees

```bash
# 1. all four services up
curl -s http://localhost:7070/   # "Real Estate gateway is running"
curl -s http://localhost:7071/   # "property-server up"
curl -s http://localhost:7072/   # "purchaser-server up"
curl -s http://localhost:7073/   # "analytics-server up"

# 2. create a buyer (one hop: gateway -> purchaser-server)
curl -X POST http://localhost:7070/purchaser \
  -H 'Content-Type: application/json' \
  -d '{"name":"Demo","email":"d@x.com","postcodes":["2770"]}'
# → {"purchaserId":"6a0d..."}

# 3. create a property + listing (one hop each: gateway -> property-server)
curl -X POST http://localhost:7070/property \
  -H 'Content-Type: application/json' \
  -d '{"postcode":"2770","propertyPrice":"500000","address":"1 Test St"}'
# → {"propertyID":"6a0d...", ...}

curl -X POST http://localhost:7070/listing \
  -H 'Content-Type: application/json' \
  -d '{"propertyId":"<id>","price":520000}'

# 4. read property 3x (gateway -> property-server + analytics-server bump)
for i in 1 2 3; do curl -s http://localhost:7070/property/<id> > /dev/null; done
curl -s http://localhost:7070/stats/property/<id>
# → {"propertyId":"...","count":3}     # analytics-server recorded all 3

# 5. /notify orchestration (gateway calls property-server + purchaser-server)
curl -s http://localhost:7070/notify | python3 -m json.tool
# → one notification per buyer with matching for-sale properties

# 6. HTML page (open in browser)
open 'http://localhost:7070/notify?format=html'
```

## Bounded-context proof

Each service can demonstrably *only* read its own collections, because its
`db/Db.java` just exposes the shared `MongoDatabase` and the DAO files
spell out which collections they touch:

```bash
# Each service's DAOs name their collections inline:
grep -nE 'getCollection|database\(\)\.getCollection' \
    services/{analytics,property,purchaser}-server/src/main/java/**/*.java

# analytics-server  -> property_views, postcode_searches
# property-server   -> properties, listings, property_pricing_updates
# purchaser-server  -> accounts
```

A reviewer can verify the rule with one grep that no service queries
another's collections.
